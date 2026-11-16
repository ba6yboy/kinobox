package ua.kinobox.data;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Джерело каталогу. Зараз є {@link MockCatalogService};
 * на кроці 3 з'явиться реалізація для TMDB, на кроці 5 — для Jellyfin.
 * Методи асинхронні, щоб UI-потік ніколи не чекав на мережу.
 */
public interface CatalogService {

    CompletableFuture<Title> featured();

    CompletableFuture<List<CatalogRow>> homeRows();

    /**
     * Ряди для розділу «Фільми» або «Серіали».
     */
    CompletableFuture<List<CatalogRow>> rows(Title.Kind kind);

    /**
     * Пошук фільмів і серіалів за назвою.
     */
    CompletableFuture<List<Title>> search(String query);

    /**
     * Сезони серіалу (без «Спецвипусків»). Для фільму — порожній список.
     */
    CompletableFuture<List<Season>> seasons(Title series);

    /**
     * Епізоди одного сезону.
     */
    CompletableFuture<List<Episode>> episodes(Title series, int season);
}
