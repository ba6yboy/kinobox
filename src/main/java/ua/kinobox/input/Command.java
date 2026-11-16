package ua.kinobox.input;

/**
 * Абстрактні команди інтерфейсу. Клавіатура, DualSense і (за потреби) пульт
 * перетворюються на ці команди, тому екрани нічого не знають про конкретний пристрій.
 */
public enum Command {
    UP, DOWN, LEFT, RIGHT,
    /**
     * Хрестик ✕ / Enter
     */
    ACCEPT,
    /**
     * Коло ○ / Esc / Backspace
     */
    BACK,
    /**
     * Options / M
     */
    MENU,
    /**
     * Трикутник △ — додаткова дія екрана (у пошуку — пробіл)
     */
    SECONDARY,
    /**
     * Квадрат □ / F / F11 — перемкнути повний екран
     */
    FULLSCREEN,
    /**
     * L1 / Page Up — стрибок на кілька карток вліво
     */
    PAGE_LEFT,
    /**
     * R1 / Page Down — стрибок на кілька карток вправо
     */
    PAGE_RIGHT;

    public boolean isDirection() {
        return this == UP || this == DOWN || this == LEFT || this == RIGHT;
    }

    /**
     * Повторюється, якщо кнопку утримувати.
     */
    public boolean isRepeatable() {
        return isDirection() || this == PAGE_LEFT || this == PAGE_RIGHT;
    }
}
