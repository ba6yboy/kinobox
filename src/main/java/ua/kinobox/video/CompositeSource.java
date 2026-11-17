package ua.kinobox.video;

import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.Title;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Кілька джерел як одне: опитуємо по черзі — спершу ваша медіатека, потім інші. */
public final class CompositeSource implements VideoSource {

    private final List<VideoSource> sources;

    public CompositeSource(List<VideoSource> sources) {
        this.sources = List.copyOf(sources);
    }

    @Override
    public String name() {
        return sources.stream().map(VideoSource::name).collect(Collectors.joining(" та "));
    }

    @Override
    public CompletableFuture<String> start() {
        // Кожне джерело стартує незалежно: недоступний Jellyfin не повинен вимикати Archive
        List<CompletableFuture<String>> starts = sources.stream()
                .map(source -> source.start().exceptionally(error -> {
                    System.err.println(source.name() + ": " + error.getMessage());
                    return "Не вдалося підключитися: " + source.name();
                }))
                .toList();
        return CompletableFuture.allOf(starts.toArray(CompletableFuture[]::new)).thenApply(done -> {
            String message = starts.stream().map(CompletableFuture::join).filter(Objects::nonNull)
                    .collect(Collectors.joining("   ·   "));
            return message.isEmpty() ? null : message;
        });
    }

    @Override
    public ObservableValue<Boolean> availability(Title title) {
        List<ObservableValue<Boolean>> parts = sources.stream().map(source -> source.availability(title)).toList();
        // «Доступно», якщо доступно хоча б в одному джерелі; перераховується, коли змінюється будь-яке
        return Bindings.createBooleanBinding(
                () -> parts.stream().anyMatch(part -> Boolean.TRUE.equals(part.getValue())),
                parts.toArray(Observable[]::new));
    }

    @Override
    public CompletableFuture<Optional<String>> movieUrl(Title movie) {
        return first(source -> source.movieUrl(movie));
    }

    @Override
    public CompletableFuture<Optional<String>> episodeUrl(Title series, EpisodeRef episode) {
        return first(source -> source.episodeUrl(series, episode));
    }

    @Override
    public CompletableFuture<Optional<EpisodeRef>> nextEpisode(Title series, EpisodeRef current) {
        // Наступну серію знає те джерело, в якому є серіал
        for (VideoSource source : sources) {
            if (Boolean.TRUE.equals(source.availability(series).getValue())) {
                return source.nextEpisode(series, current);
            }
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    @Override
    public CompletableFuture<List<String>> recentlyAdded(int limit) {
        List<CompletableFuture<List<String>>> parts = sources.stream()
                .map(source -> source.recentlyAdded(limit).exceptionally(error -> List.of()))
                .toList();
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(done -> parts.stream()
                .flatMap(part -> part.join().stream())
                .distinct()
                .limit(limit)
                .toList());
    }

    /** Перша непорожня відповідь: кожне наступне джерело питаємо лише тоді, коли попередні нічого не дали. */
    private CompletableFuture<Optional<String>> first(
            Function<VideoSource, CompletableFuture<Optional<String>>> ask) {
        CompletableFuture<Optional<String>> result = CompletableFuture.completedFuture(Optional.empty());
        for (VideoSource source : sources) {
            result = result.thenCompose(found -> found.isPresent()
                    ? CompletableFuture.completedFuture(found)
                    : ask.apply(source).exceptionally(error -> Optional.empty()));
        }
        return result;
    }
}