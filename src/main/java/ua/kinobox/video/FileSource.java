package ua.kinobox.video;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ObservableValue;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.Title;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Один файл чи посилання для всіх тайтлів: тестове відео або аргумент --video під час розробки. */
public final class FileSource implements VideoSource {

    private final String video;

    public FileSource(String video) {
        this.video = video;
    }

    @Override
    public String name() {
        return "тестовому відео";
    }

    @Override
    public ObservableValue<Boolean> availability(Title title) {
        return new SimpleBooleanProperty(true); // доступне все
    }

    @Override
    public CompletableFuture<Optional<String>> movieUrl(Title movie) {
        return CompletableFuture.completedFuture(Optional.of(video));
    }

    @Override
    public CompletableFuture<Optional<String>> episodeUrl(Title series, EpisodeRef episode) {
        return movieUrl(series);
    }
}