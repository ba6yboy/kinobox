package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.util.Duration;
import ua.kinobox.data.Title;

import java.util.Locale;

/**
 * Верхня частина головного екрана: великий фон і опис тайтла, що зараз у фокусі.
 * Фон розміщується окремо (на весь екран), щоб ряди карток «лежали» поверх нього.
 */
public final class Billboard {

    private final Region artBack = new Region();
    private final Region artFront = new Region();
    private final StackPane art = new StackPane(artBack, artFront);

    private final Label name = new Label();
    private final Label rating = new Label();
    private final Label year = new Label();
    private final Label kind = new Label();
    private final Label genres = new Label();
    private final Label overview = new Label();
    private final VBox info;

    private Title shown;

    public Billboard() {
        var fadeLeft = new Region();
        fadeLeft.getStyleClass().add("billboard-fade-left");
        var fadeBottom = new Region();
        fadeBottom.getStyleClass().add("billboard-fade-bottom");
        art.getChildren().addAll(fadeLeft, fadeBottom);
        art.setMouseTransparent(true);

        name.getStyleClass().add("billboard-title");
        name.setWrapText(true);
        rating.getStyleClass().add("rating");
        var meta = new HBox(18, rating, year, kind, genres);
        meta.getStyleClass().add("billboard-meta");
        meta.setAlignment(Pos.CENTER_LEFT);
        overview.getStyleClass().add("billboard-overview");
        overview.setWrapText(true);
        overview.setMaxWidth(680);

        info = new VBox(14, name, meta, overview);
        info.getStyleClass().add("billboard-info");
        info.setAlignment(Pos.BOTTOM_LEFT);
        info.setMouseTransparent(true);
    }

    /**
     * Фон на весь екран.
     */
    public StackPane art() {
        return art;
    }

    /**
     * Текстовий блок над рядами карток.
     */
    public VBox info() {
        return info;
    }

    public void show(Title t) {
        if (t == null || t.equals(shown)) {
            return;
        }
        shown = t;

        name.setText(t.name());
        rating.setText(String.format(Locale.ROOT, "★ %.1f", t.rating()));
        year.setText(t.year() > 0 ? String.valueOf(t.year()) : "");
        kind.setText(t.kindLabel());
        genres.setText(String.join(" · ", t.genres()));
        overview.setText(t.overview());

        // Одразу — кольорова заглушка, щоб фон змінився без затримки
        fadeTo(Artwork.backdropBackground(t.accentHue()));

        // Потім — справжній кадр, коли завантажиться
        if (t.backdropUrl() != null) {
            ImageCache.get(t.backdropUrl(), Artwork.BACKDROP_WIDTH, Artwork.BACKDROP_HEIGHT).thenAccept(img -> {
                if (img != null) {
                    Platform.runLater(() -> {
                        // Поки картинка вантажилась, користувач міг перейти на іншу картку
                        if (t.equals(shown)) {
                            fadeTo(Artwork.imageBackground(img));
                        }
                    });
                }
            });
        }

        info.setOpacity(0.25);
        var infoFade = new FadeTransition(Duration.millis(220), info);
        infoFade.setToValue(1);
        infoFade.play();
    }

    /**
     * Банер без тайтлу: власний заголовок і текст на фірмовому фоні (порожні розділи тощо).
     */
    public void showMessage(String title, String text) {
        // Скидаємо поточний тайтл: тоді наступний show(t) спрацює навіть для того самого тайтлу,
        // а картинка, що ще вантажиться для попереднього, вже не підмінить цей фон
        shown = null;

        name.setText(title);
        rating.setText("");
        year.setText("");
        kind.setText("");
        genres.setText("");
        overview.setText(text);

        fadeTo(Artwork.brandBackground());

        info.setOpacity(0.25);
        var infoFade = new FadeTransition(Duration.millis(220), info);
        infoFade.setToValue(1);
        infoFade.play();
    }

    /**
     * Перехресне згасання: старий фон іде назад, новий проявляється поверх.
     */
    private void fadeTo(Background background) {
        artBack.setBackground(artFront.getBackground());
        artFront.setBackground(background);
        artFront.setOpacity(0);
        var artFade = new FadeTransition(Duration.millis(450), artFront);
        artFade.setToValue(1);
        artFade.play();
    }
}
