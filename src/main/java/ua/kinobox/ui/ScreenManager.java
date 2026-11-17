package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import ua.kinobox.input.Command;
import ua.kinobox.nav.SpatialNavigator;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Стек екранів: {@link #push} відкриває новий поверх поточного, «Назад» повертає
 * на попередній і відновлює фокус там, де він був.
 */
public final class ScreenManager {

    /** На скільки карток стрибають L1/R1. */
    private static final int PAGE_STEP = 5;

    private final StackPane layer = new StackPane();
    private final Deque<Entry> stack = new ArrayDeque<>();
    private final ReadOnlyObjectWrapper<Screen> current = new ReadOnlyObjectWrapper<>();
    private Runnable onEdge = () -> {
    };

    /** Екран, що зараз зверху стеку. На нього можна підписатися (наприклад, щоб сховати підказки). */
    public ReadOnlyObjectProperty<Screen> currentProperty() {
        return current.getReadOnlyProperty();
    }

    public StackPane layer() {
        return layer;
    }

    /** Викликається, коли далі рухатися нікуди (наприклад, для вібрації DualSense). */
    public void setOnEdge(Runnable onEdge) {
        this.onEdge = onEdge;
    }

    public void push(Screen screen) {
        Entry top = stack.peek();
        if (top != null) {
            top.lastFocus[0] = layer.getScene() != null ? layer.getScene().getFocusOwner() : null;
            top.screen.view().setMouseTransparent(true);
        }
        stack.push(new Entry(screen, new Node[1]));
        current.set(screen);
        Parent view = screen.view();
        layer.getChildren().add(view);

        var fade = new FadeTransition(Duration.millis(180), view);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.play();

        screen.onShown();
        focusInitial(screen);
    }

    public boolean pop() {
        if (stack.size() <= 1) {
            return false;
        }
        Entry removed = stack.pop();
        removed.screen.onHidden();
        current.set(stack.peek().screen);
        Parent view = removed.screen.view();
        var fade = new FadeTransition(Duration.millis(140), view);
        fade.setToValue(0);
        fade.setOnFinished(e -> layer.getChildren().remove(view));
        fade.play();

        Entry top = stack.peek();
        top.screen.view().setMouseTransparent(false);
        if (top.lastFocus[0] != null && top.lastFocus[0].getScene() != null) {
            SpatialNavigator.focus(top.lastFocus[0]);
        } else {
            focusInitial(top.screen);
        }
        return true;
    }

    public void handle(Command command) {
        Entry top = stack.peek();
        if (top == null || top.screen.handle(command)) {
            return;
        }

        Screen screen = top.screen;
        Node focused = layer.getScene().getFocusOwner();

        // Фокус загубився (наприклад, клік мишею у порожнє місце) — повертаємо його
        if (focused == null || !isInside(focused, screen.view())) {
            focusInitial(screen);
            return;
        }

        switch (command) {
            case UP, DOWN, LEFT, RIGHT -> {
                if (SpatialNavigator.move(screen.view(), focused, command,
                        screen.navigationFilter(focused, command)) == null) {
                    onEdge.run();
                }
            }
            case PAGE_LEFT, PAGE_RIGHT -> {
                Command dir = command == Command.PAGE_LEFT ? Command.LEFT : Command.RIGHT;
                Node current = focused;
                for (int i = 0; i < PAGE_STEP; i++) {
                    Node next = SpatialNavigator.move(screen.view(), current, dir,
                            screen.navigationFilter(current, dir));
                    if (next == null) {
                        break;
                    }
                    current = next;
                }
            }
            case ACCEPT -> {
                if (focused instanceof ButtonBase button) {
                    button.fire();
                }
            }
            case BACK -> pop();
            case MENU, FULLSCREEN, SECONDARY -> {
                // за замовчуванням нічого: ці кнопки обробляють самі екрани
            }
        }
    }

    private static void focusInitial(Screen screen) {
        Node target = screen.initialFocus();
        if (target == null) {
            target = SpatialNavigator.first(screen.view());
        }
        if (target != null) {
            Node t = target;
            // після layout-проходу, щоб координати для прокрутки вже були пораховані
            Platform.runLater(() -> SpatialNavigator.focus(t));
        }
    }

    private static boolean isInside(Node node, Parent root) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == root) {
                return true;
            }
        }
        return false;
    }

    /** Екран у стеку і вузол, що мав фокус, коли поверх відкрили інший екран (масив — щоб змінювати в record). */
    private record Entry(Screen screen, Node[] lastFocus) {
    }
}
