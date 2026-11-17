package ua.kinobox.data;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Джерело каталогу: назви, описи, зображення, сезони.
 * Реалізації — {@link TmdbCatalogService} і {@link MockCatalogService} (демо-дані без мережі).
 * Методи асинхронні, щоб UI-потік ніколи не чекав на мережу.
 */
public interface CatalogService {

    /** Тайтл для банера головного екрана. */
    CompletableFuture<Title> featured();

    /** Ряди головного екрана. */
    CompletableFuture<List<CatalogRow>> homeRows();

    /** Ряди для розділу «Фільми» або «Серіали». */
    CompletableFuture<List<CatalogRow>> rows(Title.Kind kind);

    /** Пошук фільмів і серіалів за назвою. */
    CompletableFuture<List<Title>> search(String query);

    /** Сезони серіалу (без «Спецвипусків»). Для фільму — порожній список. */
    CompletableFuture<List<Season>> seasons(Title series);

    /** Епізоди одного сезону. */
    CompletableFuture<List<Episode>> episodes(Title series, int season);

    /** Один тайтл за id ("movie:603", "tv:1399"). Порожньо, якщо не знайдено. Не завершується помилкою. */
    CompletableFuture<Optional<Title>> title(String id);

    /** Кілька тайтлів у тому ж порядку; ненайдені пропускаються. */
    default CompletableFuture<List<Title>> titles(List<String> ids) {
        List<CompletableFuture<Optional<Title>>> requests = ids.stream().map(this::title).toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(done -> requests.stream()
                        .map(CompletableFuture::join)
                        .flatMap(Optional::stream)      // порожні Optional просто зникають зі списку
                        .toList());
    }

    /** Де легально подивитися тайтл онлайн. Порожньо, якщо даних немає. Не завершується помилкою. */
    default CompletableFuture<Optional<WatchOptions>> watchOptions(Title title) {
        return CompletableFuture.completedFuture(Optional.empty());
    }
}
