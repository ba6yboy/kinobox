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
import ua.kinobox.data.WatchOptions;
import ua.kinobox.input.GamepadInput;
import ua.kinobox.input.InputRouter;
import ua.kinobox.input.KeyboardInput;
import ua.kinobox.ui.DetailsScreen;
import ua.kinobox.ui.HomeScreen;
import ua.kinobox.ui.PlayerScreen;
import ua.kinobox.ui.ScreenManager;
import ua.kinobox.ui.SearchScreen;
import ua.kinobox.ui.SettingsScreen;
import ua.kinobox.ui.Splash;
import ua.kinobox.ui.Toast;
import ua.kinobox.video.ArchiveSource;
import ua.kinobox.video.CompositeSource;
import ua.kinobox.video.FileSource;
import ua.kinobox.video.JellyfinSource;
import ua.kinobox.video.VideoSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Застосунок: збирає екрани, джерела даних і пристрої введення докупи.
 *
 * <p>Аргументи запуску: {@code --windowed} — у вікні замість повного екрана;
 * {@code --video=<файл або URL>} — одне відео для всіх тайтлів (для розробки).
 */
public final class KinoBoxApp extends Application {

    /** Запуск у вікні замість повного екрана — зручно під час розробки. */
    private static final String WINDOWED_FLAG = "--windowed";
    private static final int LIBRARY_ROW_SIZE = 30;
    private static final int CLASSICS_ROW_SIZE = 20;

    private final InputRouter router = new InputRouter();
    private final ScreenManager screens = new ScreenManager();
    private final Toast toast = new Toast();
    private final Splash splash = new Splash();
    private final MyList myList = MyList.inUserHome();
    /** Один на весь застосунок: позиції перегляду всіх відео. */
    private final ProgressStore progressStore = ProgressStore.inUserHome();
    private final WatchHistory history = WatchHistory.inUserHome();
    /** Ряд «Продовжити перегляд». HomeScreen слухає цей список. */
    private final ObservableList<Title> continueWatching = FXCollections.observableArrayList();
    /** Ряд «У вашій медіатеці». */
    private final ObservableList<Title> libraryTitles = FXCollections.observableArrayList();
    /** Ряд «Класика з Internet Archive». */
    private final ObservableList<Title> classicTitles = FXCollections.observableArrayList();

    /** Є токен TMDB — справжній каталог, немає — демо-дані. Перемикається на демо, якщо TMDB недоступний. */
    private CatalogService catalog = TmdbClient.fromUserHome()
            .<CatalogService>map(TmdbCatalogService::new)
            .orElseGet(MockCatalogService::new);
    /** Звідки береться відео. Вибирається в start(): аргументи запуску доступні лише там. */
    private VideoSource videos;
    /** Окреме посилання на Archive: ряд «Класика» вміє лише він. null — запуск з --video. */
    private ArchiveSource archive;
    private GamepadInput gamepad;
    private Stage stage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        var root = new StackPane(screens.layer(), splash.node(), toast.node());
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
                return;
            }
            if (message != null) {
                toast.show(message);
            }
            loadLibraryRow();
        }));

        loadHome(catalog);
        loadClassicsRow();
    }

    @Override
    public void stop() {
        if (gamepad != null) {
            gamepad.stop();
        }
    }

    // ---------- Джерела даних ----------

    /**
     * Звідки брати відео:
     * {@code --video} (для розробки) → один файл для всього;
     * інакше → ваша медіатека Jellyfin (якщо налаштовано), потім Internet Archive.
     */
    private VideoSource chooseVideoSource() {
        String override = getParameters().getNamed().get("video");
        if (override != null) {
            return new FileSource(override);
        }
        var sources = new ArrayList<VideoSource>();
        JellyfinClient.fromUserHome().map(JellyfinLibrary::new).map(JellyfinSource::new).ifPresent(sources::add);
        archive = new ArchiveSource();
        sources.add(archive);
        return new CompositeSource(sources);
    }

    /** Головний екран. Якщо TMDB недоступний — переходимо на демо-дані. */
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
                    this::openDetails,
                    this::openSearch,
                    this::openSettings,
                    Platform::exit,
                    this::loadSection,
                    continueWatching,
                    t -> progressStore.progressOf(progressKey(t)),
                    libraryTitles,
                    classicTitles);
            screens.push(new HomeScreen(rows, actions));
            splash.hide();
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

    /** Ряд «Класика з Internet Archive». */
    private void loadClassicsRow() {
        if (archive == null) {
            return;
        }
        archive.classics(catalog, CLASSICS_ROW_SIZE).whenComplete((titles, error) -> Platform.runLater(() -> {
            if (error != null) {
                System.err.println("Ряд «Класика»: " + error.getMessage());
                return;
            }
            classicTitles.setAll(titles);
        }));
    }

    // ---------- Екрани ----------

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
            openSeries(title);
            return;
        }
        videos.movieUrl(title).whenComplete((url, error) -> Platform.runLater(() -> {
            if (error != null) {
                System.err.println("Відео «" + title.name() + "»: " + error.getMessage());
            }
            if (error != null || url.isEmpty()) {
                offerWatchElsewhere(title);
                return;
            }
            play(title, title, url.get(), title.id(), null);
        }));
    }

    /** Як у Netflix: продовжуємо останню серію; якщо її додивилися — вмикаємо наступну доступну. */
    private void openSeries(Title series) {
        var last = history.lastSource(series.id()).flatMap(EpisodeRef::parse);
        if (last.isEmpty()) {
            openEpisode(series, EpisodeRef.FIRST);
            return;
        }
        if (progressStore.position(last.get().key(series)).isPresent()) {
            openEpisode(series, last.get()); // серію не додивилися — продовжуємо її
            return;
        }
        videos.nextEpisode(series, last.get()).whenComplete((next, error) -> Platform.runLater(() -> {
            if (error != null || next.isEmpty()) {
                toast.show("Ви переглянули всі доступні серії «" + series.name() + "»");
                return;
            }
            openEpisode(series, next.get());
        }));
    }

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
                        // Немає всього серіалу — пропонуємо подивитися онлайн; немає лише цієї серії — повідомляємо
                        if (!Boolean.TRUE.equals(videos.availability(series).getValue())) {
                            offerWatchElsewhere(series);
                        } else {
                            toast.show(episode.label() + " «" + series.name() + "» немає у " + videos.name());
                        }
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

    /** Тайтлу немає в джерелі відео: якщо його можна подивитися онлайн — відкриваємо сторінку в браузері. */
    private void offerWatchElsewhere(Title title) {
        catalog.watchOptions(title).whenComplete((options, error) -> Platform.runLater(() -> {
            String link = error == null ? options.map(WatchOptions::link).orElse(null) : null;
            if (!isWebLink(link)) {
                toast.show("«" + title.name() + "» немає у " + videos.name());
                return;
            }
            // Повноекранний KinoBox закрив би браузер собою — згортаємо його
            stage.setIconified(true);
            getHostServices().showDocument(link);
        }));
    }

    /**
     * Лише https-сторінки: showDocument відкриває будь-що, що вміє Windows (файли, програми),
     * тож посилання з мережі перевіряємо, перш ніж передати системі.
     */
    private static boolean isWebLink(String link) {
        if (link == null) {
            return false;
        }
        try {
            URI uri = URI.create(link);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ---------- Прогрес і історія ----------

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

    // ---------- Пристрої ----------

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

    private void onGamepadChanged(String name) {
        toast.show(name != null ? "Підключено геймпад: " + name : "Геймпад відключено");
    }
}
