package ua.kinobox.data;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

import java.io.IOException;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * Позиції перегляду між запусками застосунку.
 * Файл — java.util.Properties: «ключ_прогресу=позиція/тривалість» (мс).
 * Дані в пам'яті — ObservableMap, тож інтерфейс може підписатися на зміни. Лише FX-потік.
 */
public final class ProgressStore {

    /** Менше 30 секунд — ще не перегляд, не зберігаємо. */
    private static final long MIN_POSITION_MS = 30_000;
    /** Для коротких відео поріг «ще не почали» — не більше цієї частки тривалості. */
    private static final double MIN_POSITION_SHARE = 0.05;
    /** Після 95% тривалості вважаємо переглянутим і позицію видаляємо. */
    private static final double FINISHED_SHARE = 0.95;

    private final Path file;
    private final ObservableMap<String, Progress> entries = FXCollections.observableHashMap();

    public ProgressStore(Path file) {
        this.file = file;
        // Пошкоджений файл не ламає застосунок — просто починаємо з чистого аркуша
        UserFiles.readProperties(file).ifPresent(data -> {
            for (String key : data.stringPropertyNames()) {
                Progress p = parse(data.getProperty(key));
                if (p != null) {
                    entries.put(key, p);
                }
            }
        });
    }

    /** Файл у домашній теці: ~/.kinobox/progress.properties */
    public static ProgressStore inUserHome() {
        return new ProgressStore(UserFiles.resolve("progress.properties"));
    }

    /** Збережена позиція або «порожньо», якщо відео ще не дивилися. */
    public OptionalLong position(String key) {
        Progress p = entries.get(key);
        return p == null ? OptionalLong.empty() : OptionalLong.of(p.positionMs());
    }

    /**
     * Прогрес конкретного відео, який сам оновлюється при кожному збереженні.
     * Значення null означає «ще не дивилися».
     */
    public ObservableValue<Progress> progressOf(String key) {
        return Bindings.valueAt(entries, key);
    }

    public void save(String key, long positionMs, long lengthMs) {
        // «Ще не почали»: 30 секунд, але для коротких відео — не більше 5% тривалості
        long minPosition = lengthMs > 0
                ? Math.min(MIN_POSITION_MS, (long) (lengthMs * MIN_POSITION_SHARE))
                : MIN_POSITION_MS;
        boolean barelyStarted = positionMs < minPosition;
        boolean finished = lengthMs > 0 && positionMs > lengthMs * FINISHED_SHARE;
        if (barelyStarted || finished) {
            entries.remove(key);
        } else {
            entries.put(key, new Progress(positionMs, lengthMs));
        }
        write();
    }

    public void clear(String key) {
        entries.remove(key);
        write();
    }

    /** Видалити всі збережені позиції. */
    public void clearAll() {
        entries.clear();
        write();
    }

    /** Формат «позиція/тривалість»; старі записи містять лише позицію — їх теж приймаємо. */
    private static Progress parse(String value) {
        String[] parts = value.split("/");
        try {
            long position = Long.parseLong(parts[0]);
            long length = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
            return position >= 0 && length >= 0 ? new Progress(position, length) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void write() {
        var data = new Properties();
        entries.forEach((key, p) -> data.setProperty(key, p.positionMs() + "/" + p.lengthMs()));
        try {
            UserFiles.writeProperties(file, data, "KinoBox watch progress: position/length, ms");
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + file + ": " + e.getMessage());
        }
    }

    /** Позиція і тривалість відео в мілісекундах. */
    public record Progress(long positionMs, long lengthMs) {

        /** Частка переглянутого 0..1 — для смужки прогресу. */
        public double share() {
            return lengthMs > 0 ? Math.min(1.0, (double) positionMs / lengthMs) : 0;
        }
    }
}
