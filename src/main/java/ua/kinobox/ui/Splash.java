package ua.kinobox.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/** Заставка на старті: логотип і анімація, поки вантажиться каталог. */
public final class Splash {

    /** Менше цього заставку не показуємо — інакше при швидкому старті вона лише «блимне». */
    private static final Duration MIN_SHOWN = Duration.millis(900);
    private static final double TRACK_WIDTH = 220;
    private static final double BAR_WIDTH = 70;

    private final StackPane root = new StackPane();
    private final long shownAt = System.nanoTime();
    private final FadeTransition pulse;
    private final TranslateTransition run;

    public Splash() {
        root.getStyleClass().add("splash");
        root.setMouseTransparent(true);

        var logo = new Label("KINOBOX");
        logo.getStyleClass().add("splash-logo");

        // Смужка завантаження: сіра доріжка, по якій біжить червоний відрізок
        var track = new Region();
        track.getStyleClass().add("splash-track");
        var bar = new Region();
        bar.getStyleClass().add("splash-bar");
        bar.setMaxSize(BAR_WIDTH, 4);
        StackPane.setAlignment(bar, Pos.CENTER_LEFT);
        var progress = new StackPane(track, bar);
        progress.setMinSize(TRACK_WIDTH, 4);
        progress.setMaxSize(TRACK_WIDTH, 4);
        progress.setClip(new Rectangle(TRACK_WIDTH, 4)); // відрізок не виходить за межі доріжки

        var status = new Label("Завантаження каталогу…");
        status.getStyleClass().add("splash-status");

        var box = new VBox(26, logo, progress, status);
        box.setAlignment(Pos.CENTER);
        root.getChildren().add(box);

        // Логотип «дихає»: прозорість туди й назад без кінця
        pulse = new FadeTransition(Duration.millis(1100), logo);
        pulse.setFromValue(1);
        pulse.setToValue(0.55);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();

        // Відрізок пробігає доріжку зліва направо, знову і знову
        run = new TranslateTransition(Duration.millis(1100), bar);
        run.setFromX(-BAR_WIDTH);
        run.setToX(TRACK_WIDTH);
        run.setInterpolator(Interpolator.EASE_BOTH);
        run.setCycleCount(Animation.INDEFINITE);
        run.play();
    }

    public Node node() {
        return root;
    }

    /** Плавно сховати й прибрати зі сцени — але не раніше, ніж через MIN_SHOWN від появи. */
    public void hide() {
        double shownMs = (System.nanoTime() - shownAt) / 1_000_000.0;
        var wait = new PauseTransition(Duration.millis(Math.max(0, MIN_SHOWN.toMillis() - shownMs)));
        var fade = new FadeTransition(Duration.millis(400), root);
        fade.setToValue(0);
        var sequence = new SequentialTransition(wait, fade);
        sequence.setOnFinished(e -> {
            pulse.stop();
            run.stop();
            if (root.getParent() instanceof Pane parent) {
                parent.getChildren().remove(root); // анімації зупинено, вузол більше не потрібен
            }
        });
        sequence.play();
    }
}