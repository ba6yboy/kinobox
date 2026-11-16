package ua.kinobox.ui;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;

/**
 * Картка фільму/серіалу. Це {@link Button}, тож ✕/Enter і клік мишею працюють однаково.
 */
public final class PosterCard extends Button {

    public static final double WIDTH = 288;
    public static final double HEIGHT = 162;
    private static final double FOCUS_SCALE = 1.09;

    private final Title title;

    public PosterCard(Title title) {
        this.title = title;
        getStyleClass().setAll("poster-card");
        setGraphic(Artwork.card(title, WIDTH, HEIGHT));
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        FocusZoom.install(this, FOCUS_SCALE);
    }

    /**
     * Тонка смужка переглянутого внизу картки (для ряду «Продовжити перегляд»).
     */
    public void showProgress(ObservableValue<ProgressStore.Progress> progress) {
        var bar = new ProgressBar();
        bar.getStyleClass().add("card-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMaxHeight(Region.USE_PREF_SIZE); // інакше StackPane розтягне смужку на всю висоту картки
        bar.progressProperty().bind(progress.map(ProgressStore.Progress::share).orElse(0.0));
        StackPane.setAlignment(bar, Pos.BOTTOM_CENTER);
        ((StackPane) getGraphic()).getChildren().add(bar);
    }

    public Title title() {
        return title;
    }
}
