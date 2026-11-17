package ua.kinobox.data;

import java.util.List;
import java.util.Objects;

/**
 * Фільм або серіал у каталозі.
 *
 * @param id           унікальний id: "movie:603", "tv:1399" (для демо-даних — "t0", "t1"…)
 * @param year         рік виходу; 0 — невідомо
 * @param posterUrl    URL постера; {@code null} — немає (тоді малюється заглушка)
 * @param backdropUrl  URL широкого фонового зображення для банера і карток
 * @param accentHue    відтінок (0–360) для кольорової заглушки
 * @param originalName назва мовою оригіналу (для пошуку в Internet Archive)
 */
public record Title(
        String id,
        String name,
        Kind kind,
        int year,
        List<String> genres,
        double rating,
        String overview,
        String posterUrl,
        String backdropUrl,
        double accentHue,
        String originalName
) {

    /** Тайтли читаються й з JSON-файлів користувача, тож відсутні поля замінюємо безпечними значеннями. */
    public Title {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        name = Objects.requireNonNullElse(name, "");
        genres = genres == null ? List.of() : genres.stream().filter(Objects::nonNull).toList();
        overview = Objects.requireNonNullElse(overview, "");
    }

    public String kindLabel() {
        return kind == Kind.MOVIE ? "Фільм" : "Серіал";
    }

    /** Копія з іншою назвою — наприклад, «Серіал · С1:Е2» для плеєра. */
    public Title withName(String newName) {
        return new Title(id, newName, kind, year, genres, rating, overview, posterUrl, backdropUrl, accentHue,
                originalName);
    }

    public enum Kind { MOVIE, SERIES }
}
