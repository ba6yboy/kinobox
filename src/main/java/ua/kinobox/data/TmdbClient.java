package ua.kinobox.data;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * Низькорівневий доступ до TMDB API v3: робить GET-запит і повертає JSON-текст.
 * Розбором JSON і перетворенням у Title займеться інший клас (крок 3b).
 */
public final class TmdbClient {

    private static final URI BASE = URI.create("https://api.themoviedb.org/3/");
    private static final String LANGUAGE = "uk-UA";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String token;

    public TmdbClient(String token) {
        this.token = token;
    }

    /**
     * Читає токен з C:\Users\<ім'я>\.kinobox\tmdb.properties (рядок token=...).
     * Порожньо — якщо файлу чи токена немає: тоді застосунок працюватиме на демо-даних.
     */
    public static Optional<TmdbClient> fromUserHome() {
        Path file = Path.of(System.getProperty("user.home"), ".kinobox", "tmdb.properties");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        var props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            System.err.println("Не вдалося прочитати " + file + ": " + e.getMessage());
            return Optional.empty();
        }
        String token = props.getProperty("token", "").strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(new TmdbClient(token));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Асинхронний GET /3/{path}. Мова відповіді — українська.
     * Приклад: get("trending/all/week", Map.of()) або get("search/multi", Map.of("query", "Дюна")).
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
                                + " для " + path + ": " + response.body());
                    }
                    return response.body();
                });
    }
}