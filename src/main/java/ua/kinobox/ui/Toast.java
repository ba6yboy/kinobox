package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * Коротке повідомлення внизу екрана.
 */
public final class Toast {

    private final Label label = new Label();
    private SequentialTransition running;

    public Toast() {
        label.getStyleClass().add("toast");
        label.setOpacity(0);
        label.setMouseTransparent(true);
        StackPane.setAlignment(label, Pos.BOTTOM_CENTER);
        StackPane.setMargin(label, new Insets(0, 0, 56, 0));
    }

    public Label node() {
        return label;
    }

    public void show(String text) {
        if (running != null) {
            running.stop();
        }
        label.setText(text);
        var in = new FadeTransition(Duration.millis(160), label);
        in.setToValue(1);
        var out = new FadeTransition(Duration.millis(300), label);
        out.setToValue(0);
        running = new SequentialTransition(in, new PauseTransition(Duration.seconds(2.2)), out);
        running.play();
    }
}
