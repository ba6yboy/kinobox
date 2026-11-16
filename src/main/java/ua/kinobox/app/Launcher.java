package ua.kinobox.app;

/**
 * Точка входу без наслідування від {@code Application}: дозволяє запускати
 * застосунок із класичного classpath (IDE, fat-jar, jpackage) без module-info.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        KinoBoxApp.main(args);
    }
}
