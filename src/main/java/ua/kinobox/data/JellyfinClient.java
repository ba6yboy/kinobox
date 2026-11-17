package ua.kinobox.data;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Доступ до вашого сервера Jellyfin: GET-запити до API і посилання на відео для плеєра.
 * Налаштування — у ~/.kinobox/jellyfin.properties (url=..., apiKey=...).
 */
public final class JellyfinClient {

    private static final String CLIENT = "KinoBox";
    private static final String VERSION = "0.1.0";
    /** Id елемента в Jellyfin — GUID (32 hex-символи, інколи з дефісами). Інше в URL не підставляємо. */
    private static final Pattern ITEM_ID = Pattern.compile("[0-9a-fA-F-]{1,64}");
    /** 172.16.0.0 – 172.31.255.255: приватна мережа. */
    private static final Pattern PRIVATE_172 = Pattern.compile("172\\.(1[6-9]|2\\d|3[01])\\..*");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build(); // переспрямування вимкнені: заголовок з ключем не піде на чужу адресу
    private final URI base;
    private final String apiKey;
    private final String authorization;

    /** @throws IllegalArgumentException якщо адреса не http(s) */
    public JellyfinClient(String url, String apiKey) {
        this.base = URI.create(url.endsWith("/") ? url : url + "/");
        String scheme = Objects.requireNonNullElse(base.getScheme(), "").toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || base.getHost() == null) {
            throw new IllegalArgumentException("адреса сервера має починатися з http:// або https://");
        }
        if (scheme.equals("http") && !isLocalNetwork(base.getHost())) {
            System.err.println("Jellyfin: з'єднання без шифрування (http) поза локальною мережею — "
                    + "API-ключ передається відкритим текстом. Використовуйте https://");
        }
        this.apiKey = apiKey;
        String device = Objects.requireNonNullElse(System.getenv("COMPUTERNAME"), "PC");
        // Схема MediaBrowser: ключ + хто підключається (видно в панелі Jellyfin → Пристрої)
        this.authorization = "MediaBrowser Token=\"" + apiKey + "\", Client=\"" + CLIENT
                + "\", Device=\"" + device + "\", DeviceId=\"kinobox-" + device + "\", Version=\"" + VERSION + "\"";
    }

    /** Порожньо, якщо файлу налаштувань немає або він неповний — тоді відео шукаємо лише в Internet Archive. */
    public static Optional<JellyfinClient> fromUserHome() {
        Path file = UserFiles.resolve("jellyfin.properties");
        return UserFiles.readProperties(file).flatMap(props -> {
            String url = props.getProperty("url", "").strip();
            String key = props.getProperty("apiKey", "").strip();
            if (url.isEmpty() || key.isEmpty()) {
                System.err.println(file + ": потрібні обидва рядки — url=... і apiKey=...");
                return Optional.empty();
            }
            try {
                return Optional.of(new JellyfinClient(url, key));
            } catch (IllegalArgumentException e) {
                System.err.println(file + ": неправильна адреса сервера — " + e.getMessage());
                return Optional.empty();
            }
        });
    }

    /** Асинхронний GET до API, наприклад {@code get("Items", Map.of("Recursive", "true"))}. */
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
     * Тому це посилання ніде не показуємо і не пишемо в лог.
     */
    public String streamUrl(String itemId) {
        if (!ITEM_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("Неочікуваний id елемента Jellyfin");
        }
        return base.resolve("Videos/" + itemId + "/stream?static=true&ApiKey=" + encode(apiKey)).toString();
    }

    /** Домашня чи локальна мережа: там http прийнятний. Перевіряємо лише запис адреси, без DNS-запитів. */
    private static boolean isLocalNetwork(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("[::1]") || !h.contains(".") || h.endsWith(".local")
                || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.")
                || PRIVATE_172.matcher(h).matches();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
