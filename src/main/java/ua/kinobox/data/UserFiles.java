package ua.kinobox.data;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Файли KinoBox у домашній теці користувача: C:\Users\<ім'я>\.kinobox\.
 * Налаштування (токени), історія, «Мій список», позиції перегляду, кеш зображень.
 */
public final class UserFiles {

    /** Коренева тека всіх даних застосунку. */
    public static final Path DIR = Path.of(System.getProperty("user.home"), ".kinobox");

    private UserFiles() {
    }

    /** Шлях усередині {@link #DIR}, наприклад {@code resolve("cache", "images")}. */
    public static Path resolve(String first, String... more) {
        return DIR.resolve(Path.of(first, more));
    }

    /** Властивості з файлу; порожньо — якщо файлу немає або його не вдалося прочитати. */
    public static Optional<Properties> readProperties(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
            return Optional.of(props);
        } catch (IOException | IllegalArgumentException e) { // IllegalArgumentException — зіпсований \\u-escape
            System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Зберегти властивості атомарно (див. {@link #writeAtomically}). */
    public static void writeProperties(Path file, Properties props, String comment) throws IOException {
        var text = new StringWriter();
        props.store(text, comment);
        writeAtomically(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Запис «все або нічого»: спершу у тимчасовий файл поруч, потім підміна одним кроком.
     * Якщо застосунок закриють посеред запису, старий файл лишиться цілим, а не обрізаним.
     */
    public static void writeAtomically(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(temp, content);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
