package ua.kinobox.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import ua.kinobox.input.Command;

import java.util.function.Predicate;

/**
 * Один екран застосунку (головна, деталі, плеєр, пошук...).
 */
public interface Screen {

    Parent view();

    /**
     * Куди ставити фокус при першому показі.
     */
    Node initialFocus();

    /**
     * Обробка команди до стандартної навігації.
     *
     * @return {@code true}, якщо команду оброблено і далі її передавати не треба
     */
    default boolean handle(Command command) {
        return false;
    }

    /**
     * Обмеження, куди можна перейти з {@code current} у напрямку {@code direction}.
     */
    default Predicate<Node> navigationFilter(Node current, Command direction) {
        return n -> true;
    }

    default void onShown() {
    }

    /**
     * Екран закрито кнопкою «Назад». Тут звільняють ресурси (наприклад, відеоплеєр).
     */
    default void onHidden() {
    }
}
