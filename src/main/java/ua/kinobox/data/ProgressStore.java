package ua.kinobox.data;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * Позиції перегляду між запусками застосунку.
 * Файл — стандартний java.util.Properties: «шлях_до_відео=позиція/тривалість» (мс).
 * Дані в пам'яті — ObservableMap, тож інтерфейс може підписатися на зміни.
 */
public final class ProgressStore {

    /**
     * Менше 30 секунд — ще не перегляд, не зберігаємо.
     */
    private static final long MIN_POSITION_MS = 30_000;
    /**
     * Після 95% тривалості вважаємо переглянутим і позицію видаляємо.
     */
    private static final double FINISHED_SHARE = 0.95;
    private final Path file;
    private final ObservableMap<String, Progress> entries = FXCollections.observableHashMap();
    public ProgressStore(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            var data = new Properties();
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data.load(in);
            } catch (IOException e) {
                // Пошкоджений файл не повинен ламати застосунок — просто почнемо з чистого аркуша
                System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            }
            for (String source : data.stringPropertyNames()) {
                Progress p = parse(data.getProperty(source));
                if (p != null) {
                    entries.put(source, p);
                }
            }
        }
    }

    /**
     * Файл у домашній теці: C:\Users\<ім'я>\.kinobox\progress.properties
     */
    public static ProgressStore inUserHome() {
        return new ProgressStore(Path.of(System.getProperty("user.home"), ".kinobox", "progress.properties"));
    }

    /**
     * Формат «позиція/тривалість». Записи з кроку 4b-2 містять лише позицію — їх теж приймаємо.
     */
    private static Progress parse(String value) {
        String[] parts = value.split("/");
        try {
            long position = Long.parseLong(parts[0]);
            long length = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            return new Progress(position, length);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Збережена позиція або «порожньо», якщо відео ще не дивилися.
     */
    public OptionalLong position(String source) {
        Progress p = entries.get(source);
        return p == null ? OptionalLong.empty() : OptionalLong.of(p.positionMs());
    }

    /**
     * Прогрес конкретного відео, який сам оновлюється при кожному збереженні.
     * Значення null означає «ще не дивилися».
     */
    public ObservableValue<Progress> progressOf(String source) {
        return Bindings.valueAt(entries, source);
    }

    public void save(String source, long positionMs, long lengthMs) {
        boolean barelyStarted = positionMs < MIN_POSITION_MS;
        boolean finished = lengthMs > 0 && positionMs > lengthMs * FINISHED_SHARE;
        if (barelyStarted || finished) {
            entries.remove(source);
        } else {
            entries.put(source, new Progress(positionMs, lengthMs));
        }
        write();
    }

    public void clear(String source) {
        entries.remove(source);
        write();
    }

    private void write() {
        var data = new Properties();
        entries.forEach((source, p) -> data.setProperty(source, p.positionMs() + "/" + p.lengthMs()));
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                data.store(out, "KinoBox watch progress: position/length, ms");
            }
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + file + ": " + e.getMessage());
        }
    }

    /**
     * Видалити всі збережені позиції.
     */
    public void clearAll() {
        entries.clear();
        write();
    }

    /**
     * Позиція і тривалість відео в мілісекундах.
     */
    public record Progress(long positionMs, long lengthMs) {
        /**
         * Частка переглянутого 0..1 — для смужки прогресу.
         */
        public double share() {
            return lengthMs > 0 ? (double) positionMs / lengthMs : 0;
        }
    }
}