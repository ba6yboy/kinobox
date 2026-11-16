package ua.kinobox.input;

import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Клавіатура як рівноправний пристрій керування.
 * Перехоплює стрілки до того, як їх обробить стандартний фокус-обхід JavaFX.
 */
public final class KeyboardInput {

    private KeyboardInput() {
    }

    public static void attach(Scene scene, InputRouter router) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            Command cmd = map(e.getCode());
            if (cmd == null) {
                return;
            }
            // У текстовому полі літери, ліво/право і Backspace мають редагувати текст
            if (scene.getFocusOwner() instanceof TextInputControl
                    && (e.getCode().isLetterKey() || e.getCode() == KeyCode.SPACE
                    || cmd == Command.LEFT || cmd == Command.RIGHT || e.getCode() == KeyCode.BACK_SPACE)) {
                return;
            }
            e.consume();
            router.dispatch(cmd);
        });
    }

    static Command map(KeyCode code) {
        return switch (code) {
            case UP, W -> Command.UP;
            case DOWN, S -> Command.DOWN;
            case LEFT, A -> Command.LEFT;
            case RIGHT, D -> Command.RIGHT;
            case ENTER, SPACE -> Command.ACCEPT;
            case ESCAPE, BACK_SPACE -> Command.BACK;
            case M, CONTEXT_MENU -> Command.MENU;
            case F, F11 -> Command.FULLSCREEN;
            case PAGE_UP, Q -> Command.PAGE_LEFT;
            case PAGE_DOWN, E -> Command.PAGE_RIGHT;
            default -> null;
        };
    }
}
