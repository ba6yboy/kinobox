package ua.kinobox.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * Вміст вашої медіатеки Jellyfin, зіставлений з каталогом TMDB.
 * Jellyfin зберігає TMDB-номер кожного фільму (ProviderIds.Tmdb), тож зіставляємо точно за номером.
 */
public final class JellyfinLibrary {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JellyfinClient client;
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
        return userId()
                .thenCompose(user -> client.get("Items", Map.of(
                        "userId", user,
                        "Recursive", "true",
                        "IncludeItemTypes", "Movie,Series",
                        "Fields", "ProviderIds")))
                .thenApply(json -> {
                    var index = new HashMap<String, String>();
                    for (JsonNode item : read(json).path("Items")) {
                        String tmdb = item.path("ProviderIds").path("Tmdb").asText("");
                        if (tmdb.isBlank()) {
                            continue;
                        }
                        String prefix = "Series".equals(item.path("Type").asText()) ? "tv:" : "movie:";
                        index.put(prefix + tmdb, item.path("Id").asText());
                    }
                    return Map.copyOf(index);
                });
    }

    /** Епізоди серіалу в медіатеці: "s1e2" → id елемента в Jellyfin. */
    public CompletableFuture<Map<String, String>> episodes(String seriesItemId) {
        return userId()
                .thenCompose(user -> client.get("Items", Map.of(
                        "userId", user,
                        "parentId", seriesItemId,
                        "Recursive", "true",
                        "IncludeItemTypes", "Episode")))
                .thenApply(json -> {
                    var index = new HashMap<String, String>();
                    for (JsonNode item : read(json).path("Items")) {
                        int season = item.path("ParentIndexNumber").asInt(-1);
                        int episode = item.path("IndexNumber").asInt(-1);
                        if (season >= 0 && episode >= 0) { // файли без номерів зіставити неможливо
                            index.put("s" + season + "e" + episode, item.path("Id").asText());
                        }
                    }
                    return Map.copyOf(index);
                });
    }

    /** Нещодавно додані фільми й серіали: id тайтлів ("movie:603", "tv:1399"), новіші першими. */
    public CompletableFuture<List<String>> recentlyAdded(int limit) {
        return userId()
                .thenCompose(user -> client.get("Items", Map.of(
                        "userId", user,
                        "Recursive", "true",
                        "IncludeItemTypes", "Movie,Series",
                        "Fields", "ProviderIds",
                        "SortBy", "DateCreated",
                        "SortOrder", "Descending",
                        "Limit", String.valueOf(limit))))
                .thenApply(json -> {
                    var ids = new ArrayList<String>();
                    for (JsonNode item : read(json).path("Items")) {
                        String tmdb = item.path("ProviderIds").path("Tmdb").asText("");
                        if (!tmdb.isBlank()) {
                            String prefix = "Series".equals(item.path("Type").asText()) ? "tv:" : "movie:";
                            ids.add(prefix + tmdb);
                        }
                    }
                    return List.copyOf(ids);
                });
    }

    /**
     * Від чийого імені дивимося медіатеку: адміністратор, а якщо його немає — перший користувач.
     * Запитуємо один раз і запам'ятовуємо.
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

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}