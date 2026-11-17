package ua.kinobox.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** «Мій список»: збережені тайтли, нові першими. Файл — ~/.kinobox/mylist.json. Лише FX-потік. */
public final class MyList {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final List<Title> titles = new ArrayList<>();

    public MyList(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try {
                JSON.readValue(file.toFile(), new TypeReference<List<Title>>() {
                }).stream().filter(Objects::nonNull).forEach(titles::add);
            } catch (IOException e) {
                System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            }
        }
    }

    public static MyList inUserHome() {
        return new MyList(UserFiles.resolve("mylist.json"));
    }

    public boolean contains(String id) {
        return titles.stream().anyMatch(t -> t.id().equals(id));
    }

    public List<Title> titles() {
        return List.copyOf(titles);
    }

    /**
     * Додати або прибрати.
     *
     * @return {@code true}, якщо тайтл тепер у списку
     */
    public boolean toggle(Title title) {
        boolean removed = titles.removeIf(t -> t.id().equals(title.id()));
        if (!removed) {
            titles.addFirst(title);
        }
        save();
        return !removed;
    }

    private void save() {
        try {
            UserFiles.writeAtomically(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(titles));
        } catch (IOException e) {
            System.err.println("Не вдалося зберегти " + file + ": " + e.getMessage());
        }
    }
}
