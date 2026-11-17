package ua.kinobox.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Вміст вашої медіатеки Jellyfin, зіставлений з каталогом TMDB.
 * Jellyfin зберігає TMDB-номер кожного фільму (ProviderIds.Tmdb), тож зіставляємо точно за номером.
 */
public final class JellyfinLibrary {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** TMDB-номер — лише цифри. Інше (порожнє, зіпсоване) не зіставляємо: воно потрапило б у шлях запиту до TMDB. */
    private static final Pattern TMDB_NUMBER = Pattern.compile("\\d{1,10}");

    private final JellyfinClient client;
    /** Id користувача, від імені якого читаємо медіатеку. Запитуємо один раз. */
    private CompletableFuture<String> userId;

    public JellyfinLibrary(JellyfinClient client) {
        this.client = client;
    }

    public JellyfinClient client() {
        return client;
    }

    /**
     * Індекс медіатеки: наш id тайтлу ("movie:10378", "tv:1399") → id елемента в Jellyfin.
     * Елементи без TMDB-номера пропускаються — їх неможливо зіставити з каталогом.
     */
    public CompletableFuture<Map<String, String>> loadIndex() {
        return items(Map.of(
                "Recursive", "true",
                "IncludeItemTypes", "Movie,Series",
                "Fields", "ProviderIds"))
                .thenApply(json -> {
                    var index = new HashMap<String, String>();
                    for (JsonNode item : read(json).path("Items")) {
                        catalogId(item).ifPresent(id -> index.put(id, item.path("Id").asText()));
                    }
                    return Map.copyOf(index);
                });
    }

    /** Епізоди серіалу в медіатеці: "s1e2" → id елемента в Jellyfin. */
    public CompletableFuture<Map<String, String>> episodes(String seriesItemId) {
        return items(Map.of(
                "parentId", seriesItemId,
                "Recursive", "true",
                "IncludeItemTypes", "Episode"))
                .thenApply(json -> {
                    var index = new HashMap<String, String>();
                    for (JsonNode item : read(json).path("Items")) {
                        int season = item.path("ParentIndexNumber").asInt(-1);
                        int episode = item.path("IndexNumber").asInt(-1);
                        if (season >= 0 && episode >= 0) { // файли без номерів зіставити неможливо
                            index.put(new EpisodeRef(season, episode).code(), item.path("Id").asText());
                        }
                    }
                    return Map.copyOf(index);
                });
    }

    /** Нещодавно додані фільми й серіали: id тайтлів ("movie:603", "tv:1399"), новіші першими. */
    public CompletableFuture<List<String>> recentlyAdded(int limit) {
        return items(Map.of(
                "Recursive", "true",
                "IncludeItemTypes", "Movie,Series",
                "Fields", "ProviderIds",
                "SortBy", "DateCreated",
                "SortOrder", "Descending",
                "Limit", String.valueOf(limit)))
                .thenApply(json -> {
                    var ids = new ArrayList<String>();
                    for (JsonNode item : read(json).path("Items")) {
                        catalogId(item).ifPresent(ids::add);
                    }
                    return List.copyOf(ids);
                });
    }

    /**
     * Від чийого імені дивимося медіатеку: адміністратор, а якщо його немає — перший користувач.
     * Невдалий запит не запам'ятовуємо: наступного разу спробуємо знову.
     */
    synchronized CompletableFuture<String> userId() {
        if (userId == null || userId.isCompletedExceptionally()) {
            userId = client.get("Users", Map.of()).thenApply(json -> {
                String first = null;
                for (JsonNode user : read(json)) {
                    if (user.path("Policy").path("IsAdministrator").asBoolean()) {
                        return user.path("Id").asText();
                    }
                    if (first == null) {
                        first = user.path("Id").asText();
                    }
                }
                if (first == null) {
                    throw new IllegalStateException("На сервері Jellyfin немає користувачів");
                }
                return first;
            });
        }
        return userId;
    }

    /** GET /Items від імені користувача (без userId сервер повертає порожній список). */
    private CompletableFuture<String> items(Map<String, String> params) {
        return userId().thenCompose(user -> {
            var withUser = new HashMap<>(params);
            withUser.put("userId", user);
            return client.get("Items", withUser);
        });
    }

    /** Наш id тайтлу за TMDB-номером елемента: "movie:603" або "tv:1399". */
    private static Optional<String> catalogId(JsonNode item) {
        String tmdb = item.path("ProviderIds").path("Tmdb").asText("");
        if (!TMDB_NUMBER.matcher(tmdb).matches()) {
            return Optional.empty();
        }
        String prefix = "Series".equals(item.path("Type").asText()) ? "tv:" : "movie:";
        return Optional.of(prefix + tmdb);
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
