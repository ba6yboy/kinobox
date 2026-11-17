package ua.kinobox.ui;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/** «Налаштування»: кеш, історія переглядів, «Про програму» з обов'язковою атрибуцією TMDB. */
public final class SettingsScreen implements Screen {

    private static final String VERSION = "0.1.0";
    /** Текст атрибуції — дослівно, як вимагає TMDB. */
    private static final String TMDB_NOTICE =
            "This product uses the TMDB API but is not endorsed or certified by TMDB.";
    private static final String TMDB_LOGO = "/ua/kinobox/tmdb-logo.png";
    private static final double TMDB_LOGO_HEIGHT = 28; // менше за назву застосунку — так вимагають правила TMDB

    private final VBox root = new VBox(32);
    private final Label cacheSize = new Label("Підрахунок…");
    private final Button clearCache;
    private final Consumer<String> notify;

    public SettingsScreen(boolean tmdbConnected, Runnable onClearHistory, Consumer<String> notify) {
        this.notify = notify;
        root.getStyleClass().add("settings");

        var title = new Label("Налаштування");
        title.getStyleClass().add("settings-title");

        // ---------- Кеш ----------
        cacheSize.getStyleClass().add("settings-text");
        clearCache = settingsButton("Очистити кеш зображень");
        clearCache.setOnAction(e -> clearCache());

        // ---------- Історія ----------
        var historyText = new Label("«Продовжити перегляд» і збережені позиції у відео");
        historyText.getStyleClass().add("settings-text");
        var clearHistory = settingsButton("Очистити історію переглядів");
        confirmTwice(clearHistory, () -> {
            onClearHistory.run();
            notify.accept("Історію переглядів очищено");
        });

        root.getChildren().addAll(
                title,
                section("Кеш зображень", cacheSize, clearCache),
                section("Історія", historyText, clearHistory),
                aboutSection(tmdbConnected));

        refreshCacheSize();
    }

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return clearCache;
    }

    private void refreshCacheSize() {
        ImageCache.diskSize().thenAccept(bytes ->
                Platform.runLater(() -> cacheSize.setText("Зайнято: " + formatSize(bytes))));
    }

    private void clearCache() {
        // Кнопку не вимикаємо (setDisable): вимкнений елемент втрачає фокус, і геймпад «загубиться»
        clearCache.setText("Очищення…");
        ImageCache.clearDisk().whenComplete((done, error) -> Platform.runLater(() -> {
            clearCache.setText("Очистити кеш зображень");
            if (error != null) {
                notify.accept("Не вдалося повністю очистити кеш");
            } else {
                notify.accept("Кеш зображень очищено");
            }
            refreshCacheSize();
        }));
    }

    private Node aboutSection(boolean tmdbConnected) {
        var name = new Label("KinoBox " + VERSION);
        name.getStyleClass().add("settings-app-name");

        var source = new Label(tmdbConnected
                ? "Каталог: The Movie Database (TMDB)"
                : "Каталог: демо-дані (немає токена TMDB)");
        source.getStyleClass().add("settings-text");

        var notice = new Label(TMDB_NOTICE);
        notice.getStyleClass().add("settings-text");
        notice.setWrapText(true);

        var box = section("Про програму", name, source);
        tmdbLogo().ifPresent(box.getChildren()::add);
        box.getChildren().add(notice);

        var justWatch = new Label("Дані «Де подивитися» надає JustWatch.");
        justWatch.getStyleClass().add("settings-text");
        box.getChildren().add(justWatch);

        return box;
    }

    // ---------- Допоміжне ----------

    private static String formatSize(long bytes) {
        return String.format(Locale.forLanguageTag("uk"), "%.1f МБ", bytes / 1024.0 / 1024.0);
    }

    /** Офіційний логотип TMDB з ресурсів, якщо файл додано в проєкт. */
    private static Optional<ImageView> tmdbLogo() {
        var url = SettingsScreen.class.getResource(TMDB_LOGO);
        if (url == null) {
            System.err.println("Немає " + TMDB_LOGO + " — додайте офіційний логотип TMDB (див. README)");
            return Optional.empty();
        }
        // Ширина 0 = рахується автоматично за пропорціями
        return Optional.of(new ImageView(new Image(url.toExternalForm(), 0, TMDB_LOGO_HEIGHT, true, true)));
    }

    private static VBox section(String heading, Node... content) {
        var label = new Label(heading);
        label.getStyleClass().add("settings-heading");
        var box = new VBox(10, label);
        box.getChildren().addAll(content);
        return box;
    }

    private static Button settingsButton(String text) {
        var button = FocusZoom.install(new Button(text), 1.04);
        button.getStyleClass().setAll("secondary-button");
        return button;
    }

    /**
     * Небезпечна дія з підтвердженням: перше натискання лише просить підтвердити,
     * друге протягом 3 секунд — виконує. Діалогові вікна з геймпадом незручні.
     */
    private static void confirmTwice(Button button, Runnable action) {
        String original = button.getText();
        var waiting = new PauseTransition(Duration.seconds(3));
        Runnable reset = () -> {
            button.setText(original);
            button.getStyleClass().remove("danger");
        };
        waiting.setOnFinished(e -> reset.run());

        button.setOnAction(e -> {
            if (waiting.getStatus() == Animation.Status.RUNNING) {
                waiting.stop();
                reset.run();
                action.run();
            } else {
                button.setText("Натисніть ще раз, щоб підтвердити");
                button.getStyleClass().add("danger");
                waiting.playFromStart();
            }
        });
    }
}
