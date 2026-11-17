package ua.kinobox.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ua.kinobox.data.CatalogService;
import ua.kinobox.data.Title;
import ua.kinobox.input.Command;
import ua.kinobox.nav.SpatialNavigator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Повноекранний пошук у стилі Netflix: поле зверху, результати посередині,
 * екранна клавіатура на всю ширину знизу.
 *
 * <pre>
 *   □            стерти символ
 *   △            пробіл
 *   L1 / L2      очистити
 *   R1 / R2 / Options   шукати й перейти до результатів
 *   ○            з результатів — на клавіатуру; з клавіатури — закрити пошук
 * </pre>
 */
public final class SearchScreen implements Screen {

    /** Цифри й символи, що трапляються в назвах. Однакові для обох мов. */
    private static final List<String> DIGIT_ROW = List.of(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", ":");

    /** Стандартна українська розкладка ЙЦУКЕН; Ґ і апостроф — як на розширеній розкладці Windows. */
    private static final List<List<String>> UKRAINIAN = List.of(
            List.of("Й", "Ц", "У", "К", "Е", "Н", "Г", "Ш", "Щ", "З", "Х", "Ї"),
            List.of("Ф", "І", "В", "А", "П", "Р", "О", "Л", "Д", "Ж", "Є"),
            List.of("Ґ", "Я", "Ч", "С", "М", "И", "Т", "Ь", "Б", "Ю", "'"));

    private static final List<List<String>> ENGLISH = List.of(
            List.of("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            List.of("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            List.of("Z", "X", "C", "V", "B", "N", "M", "'", "."));

    /** Найдовший ряд — 12 клавіш. Сітка має вдвічі більше вузьких стовпців: клавіша займає два. */
    private static final int KEY_COLUMNS = 12;

    /** Рядків завжди 4, щоб клавіатура не змінювала висоту при перемиканні мови. */
    private static final int KEY_ROWS = 4;
    private static final double KEY_HEIGHT = 52;
    /** Шукаємо, коли користувач перестав набирати на стільки мілісекунд. */
    private static final Duration SEARCH_DELAY = Duration.millis(450);
    private static final int MIN_QUERY_LENGTH = 2;

    private final CatalogService catalog;
    private final Consumer<Title> openDetails;

    private final BorderPane root = new BorderPane();
    private final TextField query = new TextField();
    private final GridPane keys = new GridPane();
    private final Button languageToggle = new Button();
    private final FlowPane results = new FlowPane();
    private final Label status = new Label();
    private final PauseTransition debounce = new PauseTransition(SEARCH_DELAY);

    private boolean ukrainian = true;
    /** Після «Знайти» перенести фокус на перший результат, щойно він з'явиться. */
    private boolean focusResultsWhenReady;
    /** Запит, результати якого зараз на екрані. */
    private String shownQuery = "";
    private Button firstKey;
    /** Остання клавіша у фокусі — туди повертаємось кнопкою ○ з результатів. */
    private Button lastKey;

    public SearchScreen(CatalogService catalog, Consumer<Title> openDetails) {
        this.catalog = catalog;
        this.openDetails = openDetails;
        root.getStyleClass().add("search");

        // ---------- Зверху: поле пошуку ----------

        query.setPromptText("Пошук фільмів і серіалів");
        query.getStyleClass().add("search-field");
        query.setMaxWidth(Double.MAX_VALUE);
        root.setTop(query);

        // ---------- Посередині: результати ----------

        results.getStyleClass().add("search-results");
        results.setHgap(14);
        results.setVgap(18);

        var scroll = new ScrollPane(results);
        scroll.getStyleClass().setAll("page-scroll");
        scroll.setFitToWidth(true); // FlowPane переносить картки за шириною видимої області
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        status.getStyleClass().add("search-status");
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());
        status.setText("Введіть назву фільму чи серіалу");

        var resultsHint = Glyphs.hint("{O} — повернутися до клавіатури        {S} {T} {L1} — редагувати запит",
                "search-hint");
        // Видно лише тоді, коли фокус на одній з карток результатів
        resultsHint.visibleProperty().bind(results.focusWithinProperty());

        var center = new VBox(12, status, resultsHint, scroll);
        center.getStyleClass().add("search-center");
        root.setCenter(center);

        // ---------- Знизу: клавіатура на всю ширину ----------

        keys.getStyleClass().add("osk");
        keys.setHgap(8);
        keys.setVgap(8);
        for (int c = 0; c < KEY_COLUMNS * 2; c++) {
            // 24 вузькі стовпці однакової ширини; клавіша займає два,
            // тож ряд можна зсунути на «пів клавіші», як на справжній клавіатурі
            keys.getColumnConstraints().add(
                    new ColumnConstraints(20, 20, Double.MAX_VALUE, Priority.ALWAYS, HPos.CENTER, true));
        }
        for (int r = 0; r < KEY_ROWS; r++) {
            keys.getRowConstraints().add(new RowConstraints(KEY_HEIGHT));
        }

        setupKey(languageToggle, () -> {
            ukrainian = !ukrainian;
            buildKeys(); // фокус лишається на перемикачі: він не всередині сітки
        });
        languageToggle.getStyleClass().add("osk-action");
        buildKeys();

        var actions = new HBox(8,
                action(Glyphs.Pad.TRIANGLE, "Пробіл", () -> type(" ")),
                action(Glyphs.Pad.SQUARE, "Стерти", this::backspace),
                action(Glyphs.Pad.L1, "Очистити", query::clear),
                languageToggle,
                action(Glyphs.Pad.R1, "Знайти", this::submit));
        actions.getChildren().forEach(n -> HBox.setHgrow(n, Priority.ALWAYS));

        var keyboard = new VBox(10, keys, actions);
        keyboard.getStyleClass().add("search-keyboard");
        root.setBottom(keyboard);

        // Кожна зміна тексту перезапускає таймер; пошук — лише коли набір зупинився
        query.textProperty().addListener((obs, old, text) -> debounce.playFromStart());
        debounce.setOnFinished(e -> runSearch());
    }

    // ---------- Screen ----------

    @Override
    public boolean handle(Command command) {
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        switch (command) {
            case FULLSCREEN -> editFromAnywhere(focused, this::backspace);      // □
            case SECONDARY -> editFromAnywhere(focused, () -> type(" "));       // △
            case PAGE_LEFT -> editFromAnywhere(focused, query::clear);          // L1 / L2
            case PAGE_RIGHT, MENU -> submit();         // R1 / R2 / Options
            case ACCEPT -> {
                if (focused != query) {
                    return false;                      // ✕ на клавіші — звичайне натискання кнопки
                }
                submit();                              // Enter на фізичній клавіатурі в полі
            }
            case BACK -> {
                if (!isInResults(focused)) {
                    return false;                      // з клавіатури — ScreenManager закриє пошук
                }
                focusKeyboard();                       // з результатів — назад на клавіатуру
            }
            default -> {
                return false;                          // стрілки — звичайна навігація
            }
        }
        return true;
    }

    @Override
    public void onHidden() {
        debounce.stop();
    }

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return firstKey;
    }

    // ---------- Клавіатура ----------

    private void buildKeys() {
        keys.getChildren().clear();
        var rows = new ArrayList<List<String>>();
        rows.add(DIGIT_ROW);
        rows.addAll(ukrainian ? UKRAINIAN : ENGLISH);

        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            // Відступ у вузьких стовпцях: (24 − 2·n) / 2 = 12 − n → ряд стоїть по центру
            int offset = KEY_COLUMNS - row.size();
            for (int i = 0; i < row.size(); i++) {
                String symbol = row.get(i);
                var button = new Button(symbol);
                setupKey(button, () -> type(symbol.toLowerCase(Locale.ROOT)));
                // add(вузол, стовпець, рядок, скільки стовпців займає, скільки рядків)
                keys.add(button, offset + i * 2, r, 2, 1);
                if (r == 1 && i == 0) {
                    firstKey = button; // початковий фокус — перша літера (Й / Q), а не цифра
                }
            }
        }
        languageToggle.setText(ukrainian ? "ABC" : "АБВ");
    }

    private Button action(Glyphs.Pad pad, String text, Runnable onPress) {
        var button = new Button(text, Glyphs.of(pad));
        setupKey(button, onPress);
        button.getStyleClass().add("osk-action");
        return button;
    }

    /** Спільні налаштування для всіх клавіш: стиль, розтягування, запам'ятовування фокуса. */
    private void setupKey(Button button, Runnable onPress) {
        button.getStyleClass().setAll("osk-key");
        button.setPrefWidth(40);                      // однакова «база» → однакова ширина після розтягування
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMaxHeight(Double.MAX_VALUE);
        button.setOnAction(e -> onPress.run());
        button.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                lastKey = button;
            }
        });
    }

    private void type(String text) {
        query.appendText(text);
    }

    private void backspace() {
        String text = query.getText();
        if (!text.isEmpty()) {
            query.setText(text.substring(0, text.length() - 1));
        }
    }

    private void focusKeyboard() {
        // Після перемикання мови стара клавіша могла зникнути зі сцени
        Button target = lastKey != null && lastKey.getScene() != null ? lastKey : firstKey;
        SpatialNavigator.focus(target);
    }

    /** Редагування запиту з будь-якого місця: якщо фокус у результатах — спершу повертаємося на клавіатуру. */
    private void editFromAnywhere(Node focused, Runnable edit) {
        if (isInResults(focused)) {
            focusKeyboard();
        }
        edit.run();
    }

    // ---------- Пошук ----------

    /** «Enter»: шукати одразу, без очікування, і перейти до результатів. */
    private void submit() {
        debounce.stop();
        String text = query.getText().strip();
        // Результати для цього тексту вже на екрані — просто переходимо до них
        if (text.equals(shownQuery) && !results.getChildren().isEmpty()) {
            SpatialNavigator.focus(results.getChildren().getFirst());
            return;
        }
        focusResultsWhenReady = true;
        runSearch();
    }

    private void runSearch() {
        String text = query.getText().strip();
        results.getChildren().clear();
        shownQuery = "";
        if (text.length() < MIN_QUERY_LENGTH) {
            focusResultsWhenReady = false;
            status.setText("Введіть назву фільму чи серіалу");
            return;
        }
        status.setText("Пошук…");
        catalog.search(text).whenComplete((found, error) -> Platform.runLater(() -> {
            // Поки йшла відповідь, текст у полі міг змінитися — тоді вона вже неактуальна
            if (!text.equals(query.getText().strip())) {
                return;
            }
            if (error != null) {
                System.err.println("Пошук «" + text + "»: " + error.getMessage());
                status.setText("Не вдалося виконати пошук");
                focusResultsWhenReady = false;
                return;
            }
            shownQuery = text;
            status.setText(found.isEmpty() ? "Нічого не знайдено за запитом «" + text + "»" : "");
            for (Title title : found) {
                var card = new PosterCard(title);
                card.setOnAction(e -> openDetails.accept(title));
                results.getChildren().add(card);
            }
            if (focusResultsWhenReady && !results.getChildren().isEmpty()) {
                SpatialNavigator.focus(results.getChildren().getFirst());
            }
            focusResultsWhenReady = false;
        }));
    }

    private boolean isInResults(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == results) {
                return true;
            }
        }
        return false;
    }
}
