package ua.kinobox.app;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;
import ua.kinobox.data.CatalogRow;
import ua.kinobox.data.CatalogService;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.JellyfinClient;
import ua.kinobox.data.JellyfinLibrary;
import ua.kinobox.data.MockCatalogService;
import ua.kinobox.data.MyList;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;
import ua.kinobox.data.TmdbCatalogService;
import ua.kinobox.data.TmdbClient;
import ua.kinobox.data.WatchHistory;
import ua.kinobox.input.GamepadInput;
import ua.kinobox.input.InputRouter;
import ua.kinobox.input.KeyboardInput;
import ua.kinobox.ui.DetailsScreen;
import ua.kinobox.ui.HomeScreen;
import ua.kinobox.ui.PlayerScreen;
import ua.kinobox.ui.ScreenManager;
import ua.kinobox.ui.SearchScreen;
import ua.kinobox.ui.SettingsScreen;
import ua.kinobox.ui.Toast;
import ua.kinobox.video.FileSource;
import ua.kinobox.video.JellyfinSource;
import ua.kinobox.video.VideoSource;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class KinoBoxApp extends Application {

    /** Запуск у вікні замість повного екрана — зручно під час розробки. */
    private static final String WINDOWED_FLAG = "--windowed";
    /** Тестове відео для всіх тайтлів, якщо Jellyfin не налаштовано. Своє: аргумент --video=шлях_або_URL */
    private static final String DEMO_VIDEO = "E:\\JavaPRO\\Temporary\\Video Project.mp4";

    private final InputRouter router = new InputRouter();
    private final ScreenManager screens = new ScreenManager();
    private final Toast toast = new Toast();
    private final MyList myList = MyList.inUserHome();
    /** Один на весь застосунок: позиції перегляду всіх відео. */
    private final ProgressStore progressStore = ProgressStore.inUserHome();
    private final WatchHistory history = WatchHistory.inUserHome();
    /** Тайтли для ряду «Продовжити перегляд». HomeScreen слухає цей список. */
    private final ObservableList<Title> continueWatching = FXCollections.observableArrayList();

    /** Тайтли для ряду «У вашій медіатеці». HomeScreen слухає цей список. */
    private final ObservableList<Title> libraryTitles = FXCollections.observableArrayList();
    private static final int LIBRARY_ROW_SIZE = 30;

    /** Є токен TMDB — справжній каталог, немає — демо-дані. Може змінитися на демо, якщо TMDB недоступний. */
    private CatalogService catalog = TmdbClient.fromUserHome()
            .<CatalogService>map(TmdbCatalogService::new)
            .orElseGet(MockCatalogService::new);
    /** Звідки береться відео. Вибирається в start(): аргументи запуску доступні лише там. */
    private VideoSource videos;
    private GamepadInput gamepad;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        var root = new StackPane(screens.layer(), toast.node());
        root.getStyleClass().add("app-root");

        var scene = new Scene(root, 1600, 900, Color.web("#0f0f12"));
        scene.getStylesheets().add(Objects.requireNonNull(
                getClass().getResource("/ua/kinobox/theme/dark.css")).toExternalForm());

        KeyboardInput.attach(scene, router);
        router.setHandler(screens::handle);
        autoHideCursor(scene);

        gamepad = new GamepadInput(router, this::onGamepadChanged);
        screens.setOnEdge(() -> gamepad.pulse(0.18f, 35));
        if (!gamepad.start()) {
            toast.show("Геймпад недоступний — керування з клавіатури");
        }

        stage.setTitle("KinoBox");
        stage.setScene(scene);
        // Як на консолі: без рамки вікна і без підказки «Натисніть Esc, щоб вийти»
        stage.setFullScreenExitHint("");
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH); // Esc = «Назад», не вихід
        stage.setMaximized(true); // розмір, до якого повертаємось із повного екрана
        stage.setFullScreen(!getParameters().getRaw().contains(WINDOWED_FLAG));
        stage.show();

        refreshContinueWatching();
        // Плеєр щойно закрили — прогрес змінився, оновлюємо ряд
        screens.currentProperty().addListener((obs, previous, now) -> {
            if (previous instanceof PlayerScreen) {
                refreshContinueWatching();
            }
        });

        videos = chooseVideoSource();
        videos.start().whenComplete((message, error) -> Platform.runLater(() -> {
            if (error != null) {
                System.err.println("Джерело відео: " + error.getMessage());
                toast.show("Сервер з відео недоступний");
            } else {
                if (message != null) {
                    toast.show(message);
                }
                loadLibraryRow();
            }
        }));

        loadHome(catalog);
    }

    /** Звідки брати відео: аргумент --video (для розробки) → Jellyfin → тестовий файл. */
    private VideoSource chooseVideoSource() {
        String override = getParameters().getNamed().get("video");
        if (override != null) {
            return new FileSource(override);
        }
        return JellyfinClient.fromUserHome()
                .map(JellyfinLibrary::new)
                .<VideoSource>map(JellyfinSource::new)
                .orElseGet(() -> new FileSource(DEMO_VIDEO));
    }

    /** Ряд «У вашій медіатеці»: що недавно додали в джерело відео, з описами й кадрами з каталогу. */
    private void loadLibraryRow() {
        videos.recentlyAdded(LIBRARY_ROW_SIZE)
                .thenCompose(ids -> catalog.titles(ids))
                .whenComplete((titles, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        System.err.println("Ряд «У вашій медіатеці»: " + error.getMessage());
                        return;
                    }
                    libraryTitles.setAll(titles);
                }));
    }

    /** Курсор ховається через 2 с без руху миші — на великому екрані він лише заважає. */
    private static void autoHideCursor(Scene scene) {
        var idle = new PauseTransition(Duration.seconds(2));
        idle.setOnFinished(e -> scene.setCursor(Cursor.NONE));
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            scene.setCursor(Cursor.DEFAULT);
            idle.playFromStart();
        });
        idle.play();
    }

    // ---------- Екрани ----------

    /** Завантажує головний екран. Якщо джерело недоступне — переходимо на демо-дані. */
    private void loadHome(CatalogService source) {
        source.homeRows().whenComplete((rows, error) -> Platform.runLater(() -> {
            if (error != null) {
                System.err.println("Каталог недоступний: " + error.getMessage());
                if (!(source instanceof MockCatalogService)) {
                    toast.show("TMDB недоступний — показую демо-дані");
                    catalog = new MockCatalogService();
                    loadHome(catalog);
                }
                return;
            }
            var actions = new HomeScreen.Actions(
                    this::openDetails,                              // openDetails
                    this::openSearch,                               // openSearch
                    this::openSettings,                             // openSettings
                    Platform::exit,                                 // exit
                    toast::show,                                    // showMessage
                    this::loadSection,                              // sections
                    continueWatching,                               // continueWatching
                    t -> progressStore.progressOf(progressKey(t)),  // progressOf
                    libraryTitles);                                 // library
            screens.push(new HomeScreen(rows, actions));
        }));
    }

    /** Ряди для розділу бокового меню. */
    private CompletableFuture<List<CatalogRow>> loadSection(HomeScreen.Section section) {
        return switch (section) {
            case HOME -> catalog.homeRows();
            case MOVIES -> catalog.rows(Title.Kind.MOVIE);
            case SERIES -> catalog.rows(Title.Kind.SERIES);
            case MY_LIST -> CompletableFuture.completedFuture(myList.titles().isEmpty()
                    ? List.of()
                    : List.of(new CatalogRow("Мій список", myList.titles())));
        };
    }

    private void openDetails(Title title) {
        screens.push(new DetailsScreen(title, myList, toast::show, this::openPlayer,
                progressStore.progressOf(progressKey(title)), catalog, videos.availability(title),
                (series, episode) -> openEpisode(series, new EpisodeRef(episode.season(), episode.number()))));
    }

    private void openSearch() {
        screens.push(new SearchScreen(catalog, this::openDetails));
    }

    private void openSettings() {
        screens.push(new SettingsScreen(catalog instanceof TmdbCatalogService, this::clearHistory, toast::show));
    }

    // ---------- Відтворення ----------

    /** Головна кнопка «Дивитися»: фільм або серіал. */
    private void openPlayer(Title title) {
        if (title.kind() == Title.Kind.SERIES) {
            // Як у Netflix: продовжуємо останню серію; якщо її додивилися — вмикаємо наступну доступну
            var last = history.lastSource(title.id()).flatMap(EpisodeRef::parse);
            if (last.isEmpty()) {
                openEpisode(title, EpisodeRef.FIRST);
                return;
            }
            if (progressStore.position(last.get().key(title)).isPresent()) {
                openEpisode(title, last.get()); // серію не додивилися — продовжуємо її
                return;
            }
            videos.nextEpisode(title, last.get()).whenComplete((next, error) -> Platform.runLater(() -> {
                if (error != null || next.isEmpty()) {
                    toast.show("Ви переглянули всі доступні серії «" + title.name() + "»");
                    return;
                }
                openEpisode(title, next.get());
            }));
            return;
        }
        videos.movieUrl(title).whenComplete((url, error) -> Platform.runLater(() -> {
            if (error != null) {
                System.err.println("Відео «" + title.name() + "»: " + error.getMessage());
            }
            if (error != null || url.isEmpty()) {
                toast.show("«" + title.name() + "» немає у " + videos.name());
                return;
            }
            play(title, title, url.get(), title.id(), null);
        }));
    }

    /** Конкретна серія. */
    /** Конкретна серія; заодно дізнаємося, яка серія йде після неї. */
    private void openEpisode(Title series, EpisodeRef episode) {
        Title display = series.withName(series.name() + " · " + episode.label());

        // Локальний record: два результати, що приходять разом
        record Resolved(Optional<String> url, Optional<EpisodeRef> next) {
        }

        videos.episodeUrl(series, episode)
                .thenCombine(videos.nextEpisode(series, episode), Resolved::new)
                .whenComplete((resolved, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        System.err.println("Відео «" + series.name() + "» " + episode.label() + ": " + error.getMessage());
                    }
                    if (error != null || resolved.url().isEmpty()) {
                        toast.show(episode.label() + " «" + series.name() + "» немає у " + videos.name());
                        return;
                    }
                    PlayerScreen.Next next = resolved.next()
                            .map(n -> new PlayerScreen.Next(series.name() + " · " + n.label(), () -> {
                                screens.pop();          // закрити поточну серію (позиція збережеться)
                                openEpisode(series, n); // і відкрити наступну
                            }))
                            .orElse(null);
                    play(series, display, resolved.url().get(), episode.key(series), next);
                }));
    }

    /**
     * Відкрити плеєр і записати перегляд в історію.
     *
     * @param recordAs що показувати в «Продовжити перегляд» (для серії — сам серіал)
     * @param display  що показувати в плеєрі (для серії — «Назва · С1:Е2»)
     */
    private void play(Title recordAs, Title display, String url, String progressKey, PlayerScreen.Next next) {
        history.record(recordAs, progressKey);
        screens.push(new PlayerScreen(display, url, progressKey, progressStore, toast::show, next));
    }

    // ---------- Прогрес ----------

    /** Ключ прогресу: для фільму — id тайтлу, для серіалу — остання переглянута серія (або С1:Е1). */
    private String progressKey(Title title) {
        if (title.kind() != Title.Kind.SERIES) {
            return title.id();
        }
        return history.lastSource(title.id()).orElse(EpisodeRef.FIRST.key(title));
    }

    /** «Продовжити перегляд»: недавні тайтли, у яких є незавершена позиція. */
    private void refreshContinueWatching() {
        continueWatching.setAll(history.recent().stream()
                .filter(entry -> progressStore.position(entry.source()).isPresent())
                .map(WatchHistory.Entry::title)
                .toList());
    }

    /** Стирає історію і всі позиції перегляду; ряд «Продовжити перегляд» зникне. */
    private void clearHistory() {
        history.clear();
        progressStore.clearAll();
        refreshContinueWatching();
    }

    private void onGamepadChanged(String name) {
        toast.show(name != null ? "Підключено геймпад: " + name : "Геймпад відключено");
    }

    @Override
    public void stop() {
        if (gamepad != null) {
            gamepad.stop();
        }
    }
}