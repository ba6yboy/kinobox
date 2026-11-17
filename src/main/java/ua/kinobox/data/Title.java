package ua.kinobox.data;

import java.util.List;

/**
 * Фільм або серіал у каталозі.
 *
 * @param posterUrl   URL постера; {@code null}, поки немає реального джерела (тоді малюється заглушка)
 * @param backdropUrl URL широкого фонового зображення для hero-банера
 * @param accentHue   відтінок (0–360) для кольорової заглушки постера
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
    public String kindLabel() {
        return kind == Kind.MOVIE ? "Фільм" : "Серіал";
    }

    /** Копія з іншою назвою — наприклад, «Серіал · С1:Е2» для плеєра. */
    public Title withName(String newName) {
        return new Title(id, newName, kind, year, genres, rating, overview, posterUrl, backdropUrl, accentHue,
                originalName);
    }

    public enum Kind {MOVIE, SERIES}
}
