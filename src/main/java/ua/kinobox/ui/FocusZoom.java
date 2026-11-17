package ua.kinobox.ui;

import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.util.Duration;

/** Плавне збільшення елемента у фокусі — головний візуальний сигнал для керування з дивана. */
public final class FocusZoom {

    private FocusZoom() {
    }

    public static <T extends Node> T install(T node, double scale) {
        var zoom = new ScaleTransition(Duration.millis(140), node);
        node.focusedProperty().addListener((obs, was, focused) -> {
            zoom.stop();
            zoom.setToX(focused ? scale : 1.0);
            zoom.setToY(focused ? scale : 1.0);
            zoom.play();
            node.setViewOrder(focused ? -1 : 0); // поверх сусідів, не змінюючи порядок у контейнері
        });
        return node;
    }
}
