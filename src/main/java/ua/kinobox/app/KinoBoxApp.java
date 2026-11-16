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
import ua.kinobox.data.*;
import ua.kinobox.input.GamepadInput;
import ua.kinobox.input.InputRouter;
import ua.kinobox.input.KeyboardInput;
import ua.kinobox.ui.*;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class KinoBoxApp extends Application {

    /**
     * Запуск у вікні замість повного екрана — зручно під час розробки.
     */
    private static final String WINDOWED_FLAG = "--windowed";
    private static final String DEMO_VIDEO =
            "E:\\JavaPRO\\Temporary\\Video Project.mp4";
    private final InputRouter router = new InputRouter();
    private final ScreenManager screens = new ScreenManager();
    private final Toast toast = new Toast();
    private final MyList myList = MyList.inUserHome();
    /**
     * Один на весь застосунок: позиції перегляду всіх відео.
     */
    private final ProgressStore progressStore = ProgressStore.inUserHome();

    private final WatchHistory history = WatchHistory.inUserHome();
    /**
     * Тайтли для ряду «Продовжити перегляд». HomeScreen слухає цей список.
     */
    private final ObservableList<Title> continueWatching = FXCollections.observableArrayList();
    /**
     * Є токен TMDB — справжній каталог, немає — демо-дані. Може змінитися на демо, якщо TMDB недоступний.
     */
    private CatalogService catalog = TmdbClient.fromUserHome()
            .<CatalogService>map(TmdbCatalogService::new)
            .orElseGet(MockCatalogService::new);
    private GamepadInput gamepad;

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Курсор ховається через 2 с без руху миші — на великому екрані він лише заважає.
     */
    private static void autoHideCursor(Scene scene) {
        var idle = new PauseTransition(Duration.seconds(2));
        idle.setOnFinished(e -> scene.setCursor(Cursor.NONE));
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            scene.setCursor(Cursor.DEFAULT);
            idle.playFromStart();
        });
        idle.play();
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

        loadHome(catalog);
    }

    /**
     * Завантажує головний екран. Якщо джерело недоступне — переходимо на демо-дані.
     */
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
                    t -> progressStore.progressOf(videoFor(t)));    // progressOf
            screens.push(new HomeScreen(rows, actions));
        }));
    }

    private void openDetails(Title title) {
        screens.push(new DetailsScreen(title, myList, toast::show, this::openPlayer,
                progressStore.progressOf(videoFor(title)), catalog));
    }

    private void openSearch() {
        screens.push(new SearchScreen(catalog, this::openDetails));
    }

    private void openPlayer(Title title) {
        history.record(title, videoFor(title));
        screens.push(new PlayerScreen(title, videoFor(title), progressStore, toast::show));
    }

    /**
     * Яке відео відкривати для тайтлу. Поки одне на всі; з TMDB/Jellyfin тут буде справжнє посилання.
     */
    private String videoFor(Title title) {
        return getParameters().getNamed().getOrDefault("video", DEMO_VIDEO);
    }

    private void onGamepadChanged(String name) {
        if (name != null) {
            toast.show("Підключено геймпад: " + name);
        } else {
            toast.show("Геймпад відключено");
        }
    }

    /**
     * «Продовжити перегляд»: недавні тайтли, у яких є незавершена позиція.
     */
    private void refreshContinueWatching() {
        continueWatching.setAll(history.recent().stream()
                .filter(entry -> progressStore.position(entry.source()).isPresent())
                .map(WatchHistory.Entry::title)
                .toList());
    }

    /**
     * Ряди для розділу бокового меню.
     */
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

    private void openSettings() {
        screens.push(new SettingsScreen(catalog instanceof TmdbCatalogService, this::clearHistory, toast::show));
    }

    /**
     * Стирає історію і всі позиції перегляду; ряд «Продовжити перегляд» зникне.
     */
    private void clearHistory() {
        history.clear();
        progressStore.clearAll();
        refreshContinueWatching();
    }

    @Override
    public void stop() {
        if (gamepad != null) {
            gamepad.stop();
        }
    }
}
