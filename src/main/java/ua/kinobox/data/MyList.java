package ua.kinobox.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * «Мій список»: збережені тайтли, нові першими. Файл — ~/.kinobox/mylist.json
 */
public final class MyList {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final List<Title> titles = new ArrayList<>();

    public MyList(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                titles.addAll(JSON.readValue(file.toFile(), new TypeReference<List<Title>>() {
                }));
            } catch (IOException e) {
                System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            }
        }
    }

    public static MyList inUserHome() {
        return new MyList(Path.of(System.getProperty("user.home"), ".kinobox", "mylist.json"));
    }

    public boolean contains(String id) {
        return titles.stream().anyMatch(t -> t.id().equals(id));
    }

    /**
     * Додати або прибрати. @return {@code true}, якщо тайтл тепер у списку
     */
    public boolean toggle(Title title) {
        boolean removed = titles.removeIf(t -> t.id().equals(title.id()));
        if (!removed) {
            titles.addFirst(title);
        }
        save();
        return !removed;
    }

    public List<Title> titles() {
        return List.copyOf(titles);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), titles);
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + file + ": " + e.getMessage());
        }
    }
}