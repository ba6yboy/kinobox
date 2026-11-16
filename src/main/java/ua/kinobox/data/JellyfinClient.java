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
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * Доступ до вашого сервера Jellyfin: GET-запити до API і посилання на відео для плеєра.
 * Налаштування — у ~/.kinobox/jellyfin.properties (url=..., apiKey=...).
 */
public final class JellyfinClient {

    private static final String CLIENT = "KinoBox";
    private static final String VERSION = "0.1.0";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final URI base;
    private final String apiKey;
    private final String authorization;

    public JellyfinClient(String url, String apiKey) {
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        this.apiKey = apiKey;
        String device = Objects.requireNonNullElse(System.getenv("COMPUTERNAME"), "PC");
        // Схема MediaBrowser: ключ + хто підключається (видно в панелі Jellyfin → Пристрої)
        this.authorization = "MediaBrowser Token=\"" + apiKey + "\", Client=\"" + CLIENT
                + "\", Device=\"" + device + "\", DeviceId=\"kinobox-" + device + "\", Version=\"" + VERSION + "\"";
    }

    /** Порожньо, якщо файлу налаштувань немає — тоді застосунок працює з тестовим відео, як раніше. */
    public static Optional<JellyfinClient> fromUserHome() {
        Path file = Path.of(System.getProperty("user.home"), ".kinobox", "jellyfin.properties");
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
        String url = props.getProperty("url", "").strip();
        String key = props.getProperty("apiKey", "").strip();
        if (url.isEmpty() || key.isEmpty()) {
            System.err.println(file + ": потрібні обидва рядки — url=... і apiKey=...");
            return Optional.empty();
        }
        return Optional.of(new JellyfinClient(url, key));
    }

    /** Асинхронний GET до API, наприклад get("Items", Map.of("Recursive", "true")). */
    public CompletableFuture<String> get(String path, Map<String, String> params) {
        var query = new StringJoiner("&");
        params.forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));
        String target = query.length() == 0 ? path : path + "?" + query;

        var request = HttpRequest.newBuilder(base.resolve(target))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new IllegalStateException("Jellyfin: HTTP " + response.statusCode() + " для " + path);
                    }
                    return response.body();
                });
    }

    /**
     * Посилання на оригінальний файл для VLC: static=true — без перекодування на сервері.
     * Ключ тут у самому URL, бо VLC не вміє додавати власні заголовки до запиту.
     */
    public String streamUrl(String itemId) {
        return base.resolve("Videos/" + itemId + "/stream?static=true&ApiKey=" + encode(apiKey)).toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}