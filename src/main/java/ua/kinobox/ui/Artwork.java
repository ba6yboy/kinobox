package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import ua.kinobox.data.Title;

/**
 * Зображення для карток і банерів. Поки картинка вантажиться (або якщо її немає),
 * показується кольорова заглушка, тож UI виглядає цілісно.
 */
public final class Artwork {

    /** Розмір, до якого декодуємо великі фони (банер, деталі). */
    public static final double BACKDROP_WIDTH = 1280;
    public static final double BACKDROP_HEIGHT = 720;

    private Artwork() {
    }

    /** Горизонтальна (16:9) картка з назвою, як у TV-інтерфейсах. */
    public static StackPane card(Title t, double w, double h) {
        var pane = new StackPane();
        pane.setMinSize(w, h);
        pane.setPrefSize(w, h);
        pane.setMaxSize(w, h);

        // Заглушка завжди знизу; картинка проявиться поверх неї, коли завантажиться
        pane.getChildren().add(placeholder(t.accentHue(), w, h));
        if (t.backdropUrl() != null) {
            pane.getChildren().add(image(t.backdropUrl(), w, h));
        }

        var shade = new Region();
        shade.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 0, 1, true,
                CycleMethod.NO_CYCLE, new Stop(0.45, Color.TRANSPARENT), new Stop(1, Color.rgb(0, 0, 0, 0.85))),
                null, null)));

        var name = new Label(t.name());
        name.getStyleClass().add("card-title");
        name.setWrapText(true);
        name.setMaxWidth(w - 24);
        StackPane.setAlignment(name, Pos.BOTTOM_LEFT);
        StackPane.setMargin(name, new Insets(0, 12, 10, 12));

        var badge = new Label(t.kind() == Title.Kind.SERIES ? "СЕРІАЛ" : "ФІЛЬМ");
        badge.getStyleClass().add("card-badge");
        StackPane.setAlignment(badge, Pos.TOP_LEFT);
        StackPane.setMargin(badge, new Insets(10, 0, 0, 12));

        pane.getChildren().addAll(shade, name, badge);

        var clip = new Rectangle(w, h);
        clip.setArcWidth(12);
        clip.setArcHeight(12);
        pane.setClip(clip);
        return pane;
    }

    /** Великий фон (сторінка деталей): одразу градієнт, а коли завантажиться — кадр з фільму. */
    public static Region backdrop(Title t) {
        var region = new Region();
        region.setBackground(backdropBackground(t.accentHue()));
        if (t.backdropUrl() != null) {
            ImageCache.get(t.backdropUrl(), BACKDROP_WIDTH, BACKDROP_HEIGHT).thenAccept(img -> {
                if (img != null) {
                    Platform.runLater(() -> region.setBackground(imageBackground(img)));
                }
            });
        }
        return region;
    }

    /** Кадр з епізоду: кольорова заглушка, поверх — зображення, заокруглені кути. */
    public static StackPane still(String url, double hue, double w, double h) {
        var pane = new StackPane(placeholder(hue, w, h));
        pane.setMinSize(w, h);
        pane.setPrefSize(w, h);
        pane.setMaxSize(w, h);
        if (url != null) {
            pane.getChildren().add(image(url, w, h));
        }
        var clip = new Rectangle(w, h);
        clip.setArcWidth(10);
        clip.setArcHeight(10);
        pane.setClip(clip);
        return pane;
    }

    /** Кольоровий градієнт-заглушка. */
    public static Background backdropBackground(double hue) {
        var base = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.hsb(hue, 0.55, 0.42)),
                new Stop(0.55, Color.hsb(hue + 35, 0.65, 0.20)),
                new Stop(1, Color.hsb(hue + 70, 0.70, 0.08)));
        var glow = new RadialGradient(0, 0, 0.72, 0.35, 0.55, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.hsb(hue - 20, 0.45, 0.85, 0.55)),
                new Stop(1, Color.TRANSPARENT));
        return new Background(new BackgroundFill(base, null, null), new BackgroundFill(glow, null, null));
    }

    /** Фірмовий фон KinoBox: темний, з червоним відсвітом. Для банерів без конкретного тайтлу. */
    public static Background brandBackground() {
        var base = new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#2a0f12")),
                new Stop(0.6, Color.web("#140a0c")),
                new Stop(1, Color.web("#0f0f12")));
        var glow = new RadialGradient(0, 0, 0.75, 0.3, 0.6, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#e5383b", 0.35)),
                new Stop(1, Color.TRANSPARENT));
        return new Background(new BackgroundFill(base, null, null), new BackgroundFill(glow, null, null));
    }

    /** Фон із зображенням у режимі «cover»: заповнює весь простір, зайве обрізається, як у CSS. */
    public static Background imageBackground(Image img) {
        var cover = new BackgroundSize(100, 100, true, true, false, true);
        return new Background(new BackgroundImage(img, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER, cover));
    }

    private static Node placeholder(double hue, double w, double h) {
        var region = new Region();
        region.setPrefSize(w, h);
        region.setBackground(backdropBackground(hue));
        return region;
    }

    private static Node image(String url, double w, double h) {
        var view = new ImageView();
        view.setFitWidth(w);
        view.setFitHeight(h);
        view.setOpacity(0);
        ImageCache.get(url, w, h).thenAccept(img -> {
            if (img != null) {
                Platform.runLater(() -> {
                    view.setImage(img);
                    var fade = new FadeTransition(Duration.millis(250), view);
                    fade.setToValue(1);
                    fade.play();
                });
            }
        });
        return view;
    }
}