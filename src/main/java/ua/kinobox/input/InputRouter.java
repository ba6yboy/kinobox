package ua.kinobox.input;

import javafx.application.Platform;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Єдина точка, куди всі пристрої надсилають команди. Доставка завжди в FX-потоці.
 */
public final class InputRouter {

    private Consumer<Command> handler = cmd -> {
    };

    public void setHandler(Consumer<Command> handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    public void dispatch(Command command) {
        if (Platform.isFxApplicationThread()) {
            handler.accept(command);
        } else {
            Platform.runLater(() -> handler.accept(command));
        }
    }
}
