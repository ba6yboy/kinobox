package ua.kinobox.video;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import ua.kinobox.data.ArchiveClient;
import ua.kinobox.data.CatalogService;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.Title;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Фільми з Internet Archive (суспільне надбання): шукаємо за оригінальною назвою і роком. */
public final class ArchiveSource implements VideoSource {

    private final ArchiveClient client = new ArchiveClient();
    /** id тайтлу → ідентифікатор на archive.org (порожньо — шукали, не знайшли). Шукаємо раз на тайтл. */
    private final Map<String, CompletableFuture<Optional<String>>> matches = new ConcurrentHashMap<>();
    /** Властивості «чи доступно» для кнопок. Лише FX-потік. */
    private final Map<String, SimpleBooleanProperty> available = new HashMap<>();

    @Override
    public String name() {
        return "Internet Archive";
    }

    @Override
    public ObservableValue<Boolean> availability(Title title) {
        if (title.kind() != Title.Kind.MOVIE) {
            return new SimpleBooleanProperty(false); // серіалів у колекції немає
        }
        return available.computeIfAbsent(title.id(), id -> {
            var property = new SimpleBooleanProperty(false);
            // Пошук іде у фоні; щойно знайдемо — кнопка «Дивитися» оживе сама
            match(title).thenAccept(found -> {
                if (found.isPresent()) {
                    Platform.runLater(() -> property.set(true));
                }
            });
            return property;
        });
    }

    @Override
    public CompletableFuture<Optional<String>> movieUrl(Title movie) {
        if (movie.kind() != Title.Kind.MOVIE) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return match(movie).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture(Optional.<String>empty())
                : client.bestVideoUrl(found.get()));
    }

    @Override
    public CompletableFuture<Optional<String>> episodeUrl(Title series, EpisodeRef episode) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    /**
     * Ряд «Класика»: найпопулярніші фільми колекції, зіставлені з каталогом (назва + рік ±1).
     * Знайдені пари одразу кладемо в кеш — на сторінці фільму пошук в Archive вже не потрібен.
     */
    public CompletableFuture<List<Title>> classics(CatalogService catalog, int limit) {
        return client.popularFeatureFilms(limit * 2) // з запасом: частину не вдасться зіставити
                .thenCompose(films -> {
                    List<CompletableFuture<Optional<Title>>> lookups = films.stream()
                            .filter(film -> film.year() > 0)  // без року легко помилитися з фільмом
                            .map(film -> findInCatalog(catalog, film))
                            .toList();
                    return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new))
                            .thenApply(done -> lookups.stream()
                                    .map(CompletableFuture::join)
                                    .flatMap(Optional::stream)
                                    .distinct()
                                    .limit(limit)
                                    .toList());
                });
    }

    private CompletableFuture<Optional<Title>> findInCatalog(CatalogService catalog, ArchiveClient.Film film) {
        // «Nosferatu (1922)» → «Nosferatu»: дужки в назвах Archive лише заважають пошуку
        String name = film.title().replaceAll("\\(.*?\\)", "").strip();
        return catalog.search(name)
                .thenApply(results -> results.stream()
                        .filter(t -> t.kind() == Title.Kind.MOVIE && Math.abs(t.year() - film.year()) <= 1)
                        .findFirst())
                .thenApply(found -> {
                    found.ifPresent(title -> matches.put(title.id(),
                            CompletableFuture.completedFuture(Optional.of(film.identifier()))));
                    return found;
                })
                .exceptionally(error -> Optional.empty());
    }

    /** Пошук один раз на тайтл; помилку мережі вважаємо «не знайдено», щоб не ламати сторінку. */
    private CompletableFuture<Optional<String>> match(Title title) {
        return matches.computeIfAbsent(title.id(), id -> {
            CompletableFuture<Optional<String>> result = CompletableFuture.completedFuture(Optional.empty());
            for (String name : searchNames(title)) {
                // Пробуємо варіанти назви по черзі, доки щось не знайдеться
                result = result.thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(found)
                        : client.findFeatureFilm(name, title.year()));
            }
            return result.exceptionally(error -> {
                System.err.println("Internet Archive, «" + title.name() + "»: " + error.getMessage());
                return Optional.empty();
            });
        });
    }

    /**
     * Варіанти назви для пошуку: оригінальна повністю і її коротка частина до «:», «,» чи « - »
     * («Nosferatu, eine Symphonie des Grauens» → «Nosferatu»). Якщо оригінальної немає — звичайна назва.
     */
    private static Set<String> searchNames(Title title) {
        String original = title.originalName() != null && !title.originalName().isBlank()
                ? title.originalName() : title.name();
        var names = new LinkedHashSet<String>();
        names.add(original);
        names.add(original.split(":|,| - ")[0].strip());
        return names;
    }
}