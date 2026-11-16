package ua.kinobox.nav;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.util.Duration;

/**
 * Плавно прокручує всі батьківські {@link ScrollPane}, щоб вузол у фокусі був видимим.
 */
public final class ScrollUtil {

    private static final Duration ANIMATION = Duration.millis(220);
    private static final String TIMELINE_KEY = "kinobox.scroll.timeline.";
    private static final String SNAP_START_KEY = "kinobox.scroll.snapStart";
    /**
     * Відступ від краю в'юпорту, щоб збільшена картка і сусіди лишалися видимими.
     */
    private static final double MARGIN_X = 60;
    private static final double MARGIN_Y = 90;
    /**
     * Відступ над рядом у режимі snapToStart — місце для назви ряду.
     */
    private static final double SNAP_MARGIN_Y = 56;

    private ScrollUtil() {
    }

    /**
     * Позначає вертикальний ScrollPane як «Netflix-стиль»: ряд у фокусі завжди
     * вирівнюється до верхнього краю, а не лише стає видимим.
     */
    public static void snapToStart(ScrollPane sp) {
        sp.getProperties().put(SNAP_START_KEY, Boolean.TRUE);
    }

    public static void ensureVisible(Node node) {
        for (Node p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof ScrollPane sp && sp.getContent() != null) {
                scroll(sp, node);
            }
        }
    }

    private static void scroll(ScrollPane sp, Node node) {
        Node content = sp.getContent();
        Bounds inContent = content.sceneToLocal(node.localToScene(node.getLayoutBounds()));
        Bounds viewport = sp.getViewportBounds();
        Bounds contentBounds = content.getLayoutBounds();

        boolean snap = Boolean.TRUE.equals(sp.getProperties().get(SNAP_START_KEY));
        if (!sp.isFitToWidth()) {
            double target = offset(sp.getHvalue(), contentBounds.getWidth(), viewport.getWidth(),
                    inContent.getMinX(), inContent.getMaxX(), MARGIN_X, false);
            animate(sp, sp.hvalueProperty(), target, "h");
        }
        if (!sp.isFitToHeight()) {
            double target = offset(sp.getVvalue(), contentBounds.getHeight(), viewport.getHeight(),
                    inContent.getMinY(), inContent.getMaxY(), snap ? SNAP_MARGIN_Y : MARGIN_Y, snap);
            animate(sp, sp.vvalueProperty(), target, "v");
        }
    }

    /**
     * @return нове значення скролу 0..1
     */
    static double offset(double value, double contentSize, double viewSize,
                         double min, double max, double margin, boolean snapStart) {
        double scrollable = contentSize - viewSize;
        if (scrollable <= 0) {
            return 0;
        }
        double current = value * scrollable;
        double next = current;
        if (snapStart) {
            next = min - margin;
        } else if (min - margin < current) {
            next = min - margin;
        } else if (max + margin > current + viewSize) {
            next = max + margin - viewSize;
        }
        next = Math.clamp(next, 0, scrollable);
        return next / scrollable;
    }

    private static void animate(ScrollPane sp, DoubleProperty property, double target, String axis) {
        if (Math.abs(property.get() - target) < 1e-4) {
            return;
        }
        Object running = sp.getProperties().get(TIMELINE_KEY + axis);
        if (running instanceof Timeline t) {
            t.stop();
        }
        var timeline = new Timeline(new KeyFrame(ANIMATION,
                new KeyValue(property, target, Interpolator.EASE_OUT)));
        sp.getProperties().put(TIMELINE_KEY + axis, timeline);
        timeline.play();
    }
}
