package ua.kinobox.nav;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import ua.kinobox.input.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Просторова навігація як на Smart TV: фокус переходить до найближчого
 * фокусованого елемента в напрямку натиснутої стрілки, незалежно від структури дерева вузлів.
 *
 * <p>Логіка вибору ({@link #pick}) не залежить від JavaFX-сцени і покрита юніт-тестами.
 */
public final class SpatialNavigator {

    /** Наскільки сильніше штрафуємо зсув упоперек напрямку руху, ніж відстань уздовж нього. */
    static final double ORTHOGONAL_WEIGHT = 2.5;
    /** Допуск на перекриття (масштабовані картки трохи заходять одна на одну). */
    static final double OVERLAP_TOLERANCE = 4.0;

    private SpatialNavigator() {
    }

    /**
     * Переносить фокус у заданому напрямку в межах {@code root}.
     *
     * @return елемент, що отримав фокус, або {@code null}, якщо в тому напрямку нічого немає
     */
    public static Node move(Parent root, Node current, Command direction) {
        return move(root, current, direction, n -> true);
    }

    /**
     * @param allowed додатковий фільтр кандидатів (наприклад, бокове меню доступне лише стрілкою вліво)
     */
    public static Node move(Parent root, Node current, Command direction, Predicate<Node> allowed) {
        List<Node> candidates = new ArrayList<>();
        collectFocusable(root, candidates);
        if (candidates.isEmpty()) {
            return null;
        }
        if (current == null || !candidates.contains(current)) {
            Node first = candidates.getFirst();
            focus(first);
            return first;
        }
        candidates.remove(current);
        candidates.removeIf(allowed.negate());

        Bounds from = sceneBounds(current);
        List<Bounds> boxes = candidates.stream().map(SpatialNavigator::sceneBounds).toList();
        int index = pick(from, boxes, direction);
        if (index < 0) {
            return null;
        }
        Node target = candidates.get(index);
        focus(target);
        return target;
    }

    /**
     * Перший фокусований елемент у {@code root} або {@code null}.
     */
    public static Node first(Parent root) {
        List<Node> candidates = new ArrayList<>();
        collectFocusable(root, candidates);
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    /** Фокус на вузол і прокрутка до нього. Якщо вузол ще не на сцені — зробимо це, щойно він там з'явиться. */
    public static void focus(Node node) {
        if (node.getScene() == null) {
            // Вузол ще не на екрані: наприклад, картка всередині ScrollPane, який не встиг відмалюватися.
            // requestFocus() зараз тихо нічого не зробить — тож чекаємо, доки вузол потрапить на сцену.
            node.sceneProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends Scene> obs, Scene old, Scene scene) {
                    if (scene != null) {
                        node.sceneProperty().removeListener(this); // слухач одноразовий
                        Platform.runLater(() -> focus(node));      // після layout — щоб прокрутка знала координати
                    }
                }
            });
            return;
        }
        node.requestFocus();
        ScrollUtil.ensureVisible(node);
    }

    /**
     * Чиста функція вибору кандидата.
     *
     * @return індекс найкращого кандидата або -1
     */
    public static int pick(Bounds from, List<Bounds> candidates, Command direction) {
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Bounds c = candidates.get(i);
            if (!isInDirection(from, c, direction)) {
                continue;
            }
            double score = score(from, c, direction);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    static boolean isInDirection(Bounds from, Bounds c, Command dir) {
        return switch (dir) {
            case RIGHT -> c.getMinX() >= from.getMaxX() - OVERLAP_TOLERANCE && centerX(c) > centerX(from);
            case LEFT -> c.getMaxX() <= from.getMinX() + OVERLAP_TOLERANCE && centerX(c) < centerX(from);
            case DOWN -> c.getMinY() >= from.getMaxY() - OVERLAP_TOLERANCE && centerY(c) > centerY(from);
            case UP -> c.getMaxY() <= from.getMinY() + OVERLAP_TOLERANCE && centerY(c) < centerY(from);
            default -> false;
        };
    }

    static double score(Bounds from, Bounds c, Command dir) {
        double primary;
        double orthogonal;
        switch (dir) {
            case RIGHT -> {
                primary = Math.max(0, c.getMinX() - from.getMaxX());
                orthogonal = gap(from.getMinY(), from.getMaxY(), c.getMinY(), c.getMaxY(), centerY(from), centerY(c));
            }
            case LEFT -> {
                primary = Math.max(0, from.getMinX() - c.getMaxX());
                orthogonal = gap(from.getMinY(), from.getMaxY(), c.getMinY(), c.getMaxY(), centerY(from), centerY(c));
            }
            case DOWN -> {
                primary = Math.max(0, c.getMinY() - from.getMaxY());
                orthogonal = gap(from.getMinX(), from.getMaxX(), c.getMinX(), c.getMaxX(), centerX(from), centerX(c));
            }
            case UP -> {
                primary = Math.max(0, from.getMinY() - c.getMaxY());
                orthogonal = gap(from.getMinX(), from.getMaxX(), c.getMinX(), c.getMaxX(), centerX(from), centerX(c));
            }
            default -> throw new IllegalArgumentException("Не напрямок: " + dir);
        }
        return primary + ORTHOGONAL_WEIGHT * orthogonal;
    }

    /**
     * Поперечна відстань. Якщо проєкції перекриваються — враховуємо лише різницю центрів
     * зі зменшеною вагою, щоб із двох «вирівняних» елементів обирався найрівніший.
     */
    private static double gap(double aMin, double aMax, double bMin, double bMax, double aCenter, double bCenter) {
        boolean overlaps = aMin < bMax && bMin < aMax;
        double centers = Math.abs(aCenter - bCenter);
        if (overlaps) {
            return centers * 0.1;
        }
        return Math.max(bMin - aMax, aMin - bMax) + centers * 0.1;
    }

    private static double centerX(Bounds b) {
        return (b.getMinX() + b.getMaxX()) / 2;
    }

    private static double centerY(Bounds b) {
        return (b.getMinY() + b.getMaxY()) / 2;
    }

    private static Bounds sceneBounds(Node n) {
        return n.localToScene(n.getLayoutBounds());
    }

    private static void collectFocusable(Parent parent, List<Node> out) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (!child.isVisible() || child.isDisabled()) {
                continue;
            }
            if (child.isFocusTraversable()) {
                out.add(child);
            } else if (child instanceof Parent p) {
                collectFocusable(p, out);
            }
        }
    }
}
