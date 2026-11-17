package ua.kinobox.video;

import javafx.beans.value.ObservableValue;
import ua.kinobox.data.EpisodeRef;
import ua.kinobox.data.Title;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Звідки береться відео для плеєра. Каталог (назви, описи) — окремо, у CatalogService.
 * Нове джерело (інший сервер, легальний сервіс) — новий клас, що реалізує цей інтерфейс.
 */
public interface VideoSource {

    /** Для повідомлень «…немає у {name}», наприклад «вашій медіатеці Jellyfin». */
    String name();

    /** Підготовка на старті (наприклад, завантажити індекс). Результат — повідомлення для користувача або null. */
    default CompletableFuture<String> start() {
        return CompletableFuture.completedFuture(null);
    }

    /** Чи можна дивитися тайтл. Може змінитися, коли джерело довантажиться. */
    ObservableValue<Boolean> availability(Title title);

    /**
     * Посилання на фільм для VLC; порожньо — фільму в цьому джерелі немає.
     * Посилання може містити секрет (API-ключ), тому його не показують користувачу і не пишуть у лог.
     */
    CompletableFuture<Optional<String>> movieUrl(Title movie);

    /** Посилання на серію для VLC; порожньо — серії в цьому джерелі немає. */
    CompletableFuture<Optional<String>> episodeUrl(Title series, EpisodeRef episode);

    /** Нещодавно додане: id тайтлів ("movie:603", "tv:1399"), новіші першими. Порожньо, якщо джерело не знає. */
    default CompletableFuture<List<String>> recentlyAdded(int limit) {
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * Наступна доступна серія після вказаної, з переходом на новий сезон.
     * Порожньо — це остання доступна серія.
     */
    default CompletableFuture<Optional<EpisodeRef>> nextEpisode(Title series, EpisodeRef current) {
        return CompletableFuture.completedFuture(Optional.of(current.next()));
    }
}