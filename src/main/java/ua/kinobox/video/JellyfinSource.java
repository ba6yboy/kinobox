package ua.kinobox.video;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.JellyfinLibrary;
import ua.kinobox.data.Title;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Відео з вашого сервера Jellyfin. Фільми й серіали зіставляються з каталогом за TMDB-номером. */
public final class JellyfinSource implements VideoSource {

    private final JellyfinLibrary library;
    /** Що є в медіатеці: id тайтлу → id елемента в Jellyfin. Змінюється лише у FX-потоці. */
    private final ObservableMap<String, String> items = FXCollections.observableHashMap();
    /** Епізоди серіалів: id тайтлу → ("s1e2" → id елемента). Запитуємо раз на серіал. */
    private final Map<String, CompletableFuture<Map<String, String>>> episodes = new ConcurrentHashMap<>();

    public JellyfinSource(JellyfinLibrary library) {
        this.library = library;
    }

    @Override
    public String name() {
        return "вашій медіатеці Jellyfin";
    }

    @Override
    public CompletableFuture<String> start() {
        // Другий аргумент — «де виконати продовження»: Platform::runLater означає «у FX-потоці».
        // Саме там живуть кнопки, що слухають items.
        return library.loadIndex().thenApplyAsync(index -> {
            items.putAll(index);
            return "Медіатека Jellyfin: " + index.size() + " тайтлів";
        }, Platform::runLater);
    }

    @Override
    public ObservableValue<Boolean> availability(Title title) {
        return Bindings.valueAt(items, title.id()).map(itemId -> true).orElse(false);
    }

    @Override
    public CompletableFuture<Optional<String>> movieUrl(Title movie) {
        // thenApply, а не map одразу: якщо streamUrl відкине id, отримаємо невдалий future, а не виняток тут
        return CompletableFuture.completedFuture(Optional.ofNullable(items.get(movie.id())))
                .thenApply(itemId -> itemId.map(this::stream));
    }

    @Override
    public CompletableFuture<Optional<String>> episodeUrl(Title series, EpisodeRef episode) {
        return episodesOf(series).thenApply(index -> Optional.ofNullable(index.get(episode.code())).map(this::stream));
    }

    @Override
    public CompletableFuture<Optional<EpisodeRef>> nextEpisode(Title series, EpisodeRef current) {
        return episodesOf(series).thenApply(index -> index.keySet().stream()
                .map(EpisodeRef::fromCode)
                .flatMap(Optional::stream)
                .filter(episode -> episode.compareTo(current) > 0) // лише ті, що йдуть після поточної
                .min(Comparator.naturalOrder()));                   // найближча з них
    }

    @Override
    public CompletableFuture<List<String>> recentlyAdded(int limit) {
        return library.recentlyAdded(limit);
    }

    /** Епізоди серіалу; невдалі запити не кешуємо, щоб наступного разу спробувати знову. */
    private CompletableFuture<Map<String, String>> episodesOf(Title series) {
        String seriesItemId = items.get(series.id());
        if (seriesItemId == null) {
            return CompletableFuture.completedFuture(Map.of()); // серіалу немає в медіатеці
        }
        return episodes.compute(series.id(), (id, cached) ->
                cached == null || cached.isCompletedExceptionally() ? library.episodes(seriesItemId) : cached);
    }

    private String stream(String itemId) {
        return library.client().streamUrl(itemId);
    }
}