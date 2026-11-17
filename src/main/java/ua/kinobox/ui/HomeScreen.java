package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.collections.ObservableList;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ua.kinobox.data.CatalogRow;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;
import ua.kinobox.input.Command;
import ua.kinobox.nav.ScrollUtil;
import ua.kinobox.nav.SpatialNavigator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Головний екран у стилі TV-застосунків: зверху опис тайтла у фокусі,
 * знизу горизонтальні ряди карток, ліворуч бокове меню, що розгортається при фокусі.
 */
public final class HomeScreen implements Screen {

    private final Actions actions;
    /** Ряди «Головної» з каталогу — показуємо знову, коли повертаємось на «Головну». */
    private final List<CatalogRow> homeRows;

    private final StackPane root = new StackPane();
    private final Billboard billboard = new Billboard();
    private final VBox sidebar = new VBox();
    private final VBox rowsBox = new VBox();
    private final PauseTransition billboardDebounce = new PauseTransition(Duration.millis(110));
    /** Ряди, що з'являються і зникають самі: «Продовжити перегляд», «У вашій медіатеці», «Класика». */
    private final List<Node> dynamicRows = new ArrayList<>();

    private Section currentSection = Section.HOME;
    /** Підсвічений пункт меню; на нього стає фокус, коли меню відкривають кнопкою ○. */
    private Button activeItem;
    /** Остання картка у фокусі — туди повертаємось, коли закривають меню. */
    private PosterCard lastCard;

    public HomeScreen(List<CatalogRow> rows, Actions actions) {
        this.actions = actions;
        this.homeRows = rows;
        root.getStyleClass().add("home");

        rowsBox.getStyleClass().add("rows");
        setRows(rows);
        // Динамічні ряди перебудовуються, коли змінюється будь-який з їхніх списків
        actions.continueWatching().addListener((Observable obs) -> refreshDynamicRows());
        actions.library().addListener((Observable obs) -> refreshDynamicRows());
        actions.classics().addListener((Observable obs) -> refreshDynamicRows());

        var rowsScroll = new ScrollPane(rowsBox);
        rowsScroll.getStyleClass().setAll("page-scroll");
        rowsScroll.setFitToWidth(true);
        rowsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowsScroll.setFocusTraversable(false);
        ScrollUtil.snapToStart(rowsScroll);
        VBox.setVgrow(rowsScroll, Priority.ALWAYS);

        // Опис займає верхні ~45% висоти
        var info = billboard.info();
        info.prefHeightProperty().bind(root.heightProperty().multiply(0.45));
        info.setMinHeight(Region.USE_PREF_SIZE);

        var content = new VBox(info, rowsScroll);

        buildSidebar();
        StackPane.setAlignment(sidebar, Pos.TOP_LEFT);
        sidebar.setMaxWidth(Region.USE_PREF_SIZE);

        // Поки відкрите меню — приглушуємо решту екрана
        sidebar.focusWithinProperty().addListener((obs, was, open) -> {
            var dim = new FadeTransition(Duration.millis(180), content);
            dim.setToValue(open ? 0.3 : 1.0);
            dim.play();
        });

        root.getChildren().addAll(billboard.art(), content, sidebar);
    }

    // ---------- Screen ----------

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return firstCard();
    }

    @Override
    public boolean handle(Command command) {
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (focused == null) {
            return false;
        }
        if (isInside(focused, sidebar)) {
            // Стрілка вправо або ○ — закрити меню й повернутися саме туди, де були
            if (command == Command.RIGHT || command == Command.BACK) {
                if (lastCard != null && lastCard.getScene() != null) {
                    SpatialNavigator.focus(lastCard);
                } else {
                    focusFirstCard();
                }
                return true;
            }
            return false;
        }
        switch (command) {
            case LEFT -> {
                // Вліво з першої картки ряду — відкриваємо меню; інакше — просто сусідня картка
                if (SpatialNavigator.move(root, focused, Command.LEFT, sameRow(focused)) == null) {
                    SpatialNavigator.focus(activeItem);
                }
                return true;
            }
            case BACK -> {
                SpatialNavigator.focus(activeItem); // ○ на картці — відкрити меню на поточному розділі
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public Predicate<Node> navigationFilter(Node current, Command direction) {
        if (isInside(current, sidebar)) {
            return n -> isInside(n, sidebar);    // вгору/вниз лише по меню
        }
        if (direction == Command.LEFT || direction == Command.RIGHT) {
            return sameRow(current);             // не перестрибувати на інші ряди
        }
        return n -> !isInside(n, sidebar);       // у меню потрапляємо лише стрілкою вліво
    }

    // ---------- Ряди ----------

    /** Показати ряди: замінює весь вміст під банером. */
    private void setRows(List<CatalogRow> rows) {
        rowsBox.getChildren().clear();
        for (CatalogRow row : rows) {
            rowsBox.getChildren().add(buildRow(row, false));
        }
        refreshDynamicRows(); // сам перевірить, чи це «Головна»
        rows.stream()
                .filter(row -> !row.items().isEmpty())
                .findFirst()
                .ifPresent(row -> billboard.show(row.items().getFirst()));
    }

    private Node buildRow(CatalogRow row, boolean withProgress) {
        var cards = new HBox();
        cards.getStyleClass().add("row-cards");
        for (Title t : row.items()) {
            var card = new PosterCard(t);
            if (withProgress) {
                card.showProgress(actions.progressOf().apply(t));
            }
            card.setOnAction(e -> actions.openDetails().accept(t));
            card.focusedProperty().addListener((obs, was, focused) -> {
                if (focused) {
                    lastCard = card;
                    billboardDebounce.setOnFinished(e -> billboard.show(t));
                    billboardDebounce.playFromStart();
                }
            });
            cards.getChildren().add(card);
        }

        var scroll = new ScrollPane(cards);
        scroll.getStyleClass().setAll("row-scroll");
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);

        var label = new Label(row.title());
        label.getStyleClass().add("row-title");
        var box = new VBox(label, scroll);
        box.getStyleClass().add("row");
        // Запам'ятовуємо першу картку ряду: її можна знайти одразу, ще до того,
        // як ScrollPane відмалюється і його вміст з'явиться в дереві вузлів
        box.setUserData(cards.getChildren().isEmpty() ? null : cards.getChildren().getFirst());
        return box;
    }

    /** Перебудувати динамічні ряди над звичайними (лише на «Головній»). */
    private void refreshDynamicRows() {
        // Якщо фокус у ряду, який зараз зникне, JavaFX сам перекине його на перший елемент сцени —
        // у бокове меню. Тому запам'ятовуємо це і після перебудови повертаємо фокус на картки.
        Node focused = root.getScene() == null ? null : root.getScene().getFocusOwner();
        boolean focusInDynamicRow = focused != null
                && dynamicRows.stream().anyMatch(row -> isInside(focused, row));

        rowsBox.getChildren().removeAll(dynamicRows);
        dynamicRows.clear();
        if (currentSection == Section.HOME) {
            addDynamicRow("Продовжити перегляд", actions.continueWatching(), true);
            addDynamicRow("У вашій медіатеці", actions.library(), false);
            addDynamicRow("Класика з Internet Archive", actions.classics(), false);
            rowsBox.getChildren().addAll(0, dynamicRows);
        }

        // Повертаємо фокус на картки, якщо:
        //  - фокус був у ряду, що щойно зник, або
        //  - на жодній картці фокусу ще не було (старт: перша картка могла зникнути,
        //    не встигнувши з'явитися на екрані, — і фокус застряг би в боковому меню)
        if (focusInDynamicRow || lastCard == null) {
            Platform.runLater(this::focusFirstCard);
        }
    }

    private void addDynamicRow(String title, List<Title> titles, boolean withProgress) {
        if (!titles.isEmpty()) {
            dynamicRows.add(buildRow(new CatalogRow(title, List.copyOf(titles)), withProgress));
        }
    }

    /** Перша картка на екрані (або кнопка дії порожнього розділу); null — рядів немає. */
    private Node firstCard() {
        for (Node row : rowsBox.getChildren()) {
            if (row.getUserData() instanceof Node card) {
                return card;
            }
        }
        return null;
    }

    private void focusFirstCard() {
        Node first = firstCard();
        if (first != null) {
            SpatialNavigator.focus(first);
        }
    }

    // ---------- Розділи і бокове меню ----------

    private void showSection(Section section, Button item) {
        currentSection = section;
        activeItem.getStyleClass().remove("active");
        activeItem = item;
        item.getStyleClass().add("active");

        if (section == Section.HOME) {
            setRows(homeRows);
            Platform.runLater(this::focusFirstCard); // після layout, щоб прокрутка знала координати
            return;
        }
        rowsBox.getChildren().setAll(sectionStatus("Завантаження…", null, null));
        actions.sections().apply(section).whenComplete((rows, error) -> Platform.runLater(() -> {
            // Поки вантажилось, користувач міг вибрати інший розділ
            if (currentSection != section) {
                return;
            }
            if (error != null) {
                System.err.println("Розділ " + section + ": " + error.getMessage());
                rowsBox.getChildren().setAll(sectionStatus("Не вдалося завантажити розділ",
                        "Спробувати ще раз", () -> showSection(section, item)));
            } else if (rows.isEmpty() && section == Section.MY_LIST) {
                billboard.showMessage("Мій список",
                        "Додавайте фільми й серіали кнопкою «Мій список» на їхній сторінці — вони збережуться тут.");
                rowsBox.getChildren().setAll(
                        sectionStatus("Список порожній", "Знайти фільм чи серіал", actions.openSearch()));
            } else if (rows.isEmpty()) {
                rowsBox.getChildren().setAll(sectionStatus("Тут поки порожньо", null, null));
            } else {
                setRows(rows);
            }
            Platform.runLater(this::focusFirstCard);
        }));
    }

    /**
     * Напис замість рядів («Завантаження…», «Тут поки порожньо») і, за потреби, кнопка дії.
     * Кнопка забирає фокус із меню — так меню закривається і користувач бачить, що можна зробити далі.
     */
    private Node sectionStatus(String text, String actionText, Runnable action) {
        var label = new Label(text);
        label.getStyleClass().add("section-status-text");
        label.setWrapText(true);

        var box = new VBox(20, label);
        box.getStyleClass().add("section-status");

        if (actionText != null) {
            var button = FocusZoom.install(new Button(actionText), 1.06);
            button.getStyleClass().setAll("secondary-button");
            button.setOnAction(e -> action.run());
            box.getChildren().add(button);
            box.setUserData(button); // firstCard() знайде кнопку так само, як першу картку ряду
        }
        return box;
    }

    private void buildSidebar() {
        sidebar.getStyleClass().add("sidebar");
        var home = sectionItem("Головна", Icons.HOME, Section.HOME);
        sidebar.getChildren().addAll(
                sideItem("Пошук", Icons.SEARCH, actions.openSearch()),
                home,
                sectionItem("Серіали", Icons.TV, Section.SERIES),
                sectionItem("Фільми", Icons.MOVIE, Section.MOVIES),
                sectionItem("Мій список", Icons.ADD, Section.MY_LIST),
                sideItem("Налаштування", Icons.SETTINGS, actions.openSettings()),
                // у повноекранному режимі немає хрестика вікна — потрібен явний вихід
                sideItem("Вийти", Icons.EXIT, actions.exit()));
        home.getStyleClass().add("active");
        activeItem = home;
    }

    /** Пункт меню, що відкриває розділ. Дії потрібне посилання на саму кнопку, тому ставимо її після створення. */
    private Button sectionItem(String text, String icon, Section section) {
        var item = sideItem(text, icon, null);
        item.setOnAction(e -> showSection(section, item));
        return item;
    }

    private static Button sideItem(String text, String icon, Runnable action) {
        var b = new Button(text, Icons.of(icon));
        b.getStyleClass().setAll("side-item");
        b.setMaxWidth(Double.MAX_VALUE);
        if (action != null) {
            b.setOnAction(e -> action.run());
        }
        return b;
    }

    // ---------- Допоміжне ----------

    /** Фільтр «лише картки того самого ряду». */
    private static Predicate<Node> sameRow(Node card) {
        Node row = card.getParent();
        return n -> n.getParent() == row;
    }

    /** Чи лежить вузол усередині контейнера (на будь-якій глибині). */
    private static boolean isInside(Node node, Node container) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == container) {
                return true;
            }
        }
        return false;
    }

    /** Розділи бокового меню. */
    public enum Section { HOME, MOVIES, SERIES, MY_LIST }

    /**
     * Усе, що головний екран отримує від застосунку: дії та джерела даних.
     * Один record замість довгого списку параметрів конструктора.
     */
    public record Actions(
            Consumer<Title> openDetails,
            Runnable openSearch,
            Runnable openSettings,
            Runnable exit,
            Function<Section, CompletableFuture<List<CatalogRow>>> sections,
            ObservableList<Title> continueWatching,
            Function<Title, ObservableValue<ProgressStore.Progress>> progressOf,
            ObservableList<Title> library,
            ObservableList<Title> classics) {
    }
}
