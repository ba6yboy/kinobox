package ua.kinobox.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Історія переглядів: які тайтли і коли відкривали в плеєрі.
 * Файл — JSON (~/.kinobox/history.json), новіші записи першими.
 */
public final class WatchHistory {

    private static final int MAX_ENTRIES = 20;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path file;
    private final List<Entry> entries = new ArrayList<>();
    public WatchHistory(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                // TypeReference підказує Jackson повний тип: «список записів», а не просто «список чогось»
                entries.addAll(JSON.readValue(file.toFile(), new TypeReference<List<Entry>>() {
                }));
            } catch (IOException e) {
                System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            }
        }
    }

    /** Ключ прогресу того, що дивилися в цьому тайтлі останнім (для серіалу — конкретна серія). */
    public Optional<String> lastSource(String titleId) {
        return entries.stream()
                .filter(e -> e.title().id().equals(titleId))
                .map(Entry::source)
                .findFirst();
    }

    public static WatchHistory inUserHome() {
        return new WatchHistory(Path.of(System.getProperty("user.home"), ".kinobox", "history.json"));
    }

    /**
     * Записи від найсвіжішого до найстаршого.
     */
    public List<Entry> recent() {
        return List.copyOf(entries);
    }

    /**
     * Тайтл відкрили в плеєрі: переносимо (або додаємо) його на початок списку.
     */
    public void record(Title title, String source) {
        entries.removeIf(e -> e.title().id().equals(title.id()));
        entries.addFirst(new Entry(title, source, System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) {
            entries.removeLast();
        }
        save();
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + file + ": " + e.getMessage());
        }
    }

    public void clear() {
        entries.clear();
        save();
    }

    /**
     * Один запис: що дивилися, яке відео, коли (мс від 1970 року).
     */
    public record Entry(Title title, String source, long watchedAt) {
    }
}