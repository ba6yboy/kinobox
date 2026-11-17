package ua.kinobox.data;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * Низькорівневий доступ до TMDB API v3: GET-запит і JSON-текст у відповідь.
 * Розбір JSON і перетворення у {@link Title} — у {@link TmdbCatalogService}.
 */
public final class TmdbClient {

    private static final URI BASE = URI.create("https://api.themoviedb.org/3/");
    private static final String LANGUAGE = "uk-UA";
    /** Скільки символів тіла помилки показувати в лозі: повна сторінка помилки лише засмічує консоль. */
    private static final int ERROR_BODY_LIMIT = 300;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String token;

    public TmdbClient(String token) {
        this.token = token;
    }

    /**
     * Токен з ~/.kinobox/tmdb.properties (рядок token=...).
     * Порожньо, якщо файлу чи токена немає: тоді застосунок працює на демо-даних.
     */
    public static Optional<TmdbClient> fromUserHome() {
        return UserFiles.readProperties(UserFiles.resolve("tmdb.properties"))
                .map(props -> props.getProperty("token", "").strip())
                .filter(token -> !token.isEmpty())
                .map(TmdbClient::new);
    }

    /**
     * Асинхронний GET /3/{path}. Мова відповіді — українська.
     * Приклад: {@code get("trending/all/week", Map.of())} або {@code get("search/multi", Map.of("query", "Дюна"))}.
     */
    public CompletableFuture<String> get(String path, Map<String, String> params) {
        var query = new StringJoiner("&");
        query.add("language=" + LANGUAGE);
        params.forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));

        var request = HttpRequest.newBuilder(BASE.resolve(path + "?" + query))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("TMDB: HTTP " + response.statusCode()
                                + " для " + path + ": " + abbreviate(response.body()));
                    }
                    return response.body();
                });
    }

    private static String abbreviate(String text) {
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "…";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
