package ua.kinobox.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Internet Archive: пошук фільмів у колекції суспільного надбання і прямі посилання на відеофайли.
 * API відкрите, ключ не потрібен.
 */
public final class ArchiveClient {

    private static final URI BASE = URI.create("https://archive.org/");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Колекція повнометражних фільмів з відкритим статусом. Шукаємо лише тут. */
    private static final String COLLECTION = "feature_films";
    private static final List<String> VIDEO_EXTENSIONS = List.of(".mp4", ".m4v", ".mkv", ".ogv", ".avi", ".mpeg", ".mpg");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Ідентифікатор фільму на archive.org за назвою й роком (±1); порожньо, якщо не знайдено. */
    public CompletableFuture<Optional<String>> findFeatureFilm(String title, int year) {
        // «Night of the Living Dead» → night AND of AND the AND living AND dead: усі слова мають бути в назві
        String words = Arrays.stream(title.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(word -> !word.isBlank())
                .collect(Collectors.joining(" AND "));
        if (words.isEmpty()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String query = "collection:(" + COLLECTION + ") AND mediatype:(movies) AND title:(" + words + ")"
                + (year > 0 ? " AND year:[" + (year - 1) + " TO " + (year + 1) + "]" : "");
        String path = "advancedsearch.php?q=" + encode(query)
                + "&fl%5B%5D=identifier&rows=1&sort%5B%5D=downloads+desc&output=json";
        return get(path).thenApply(json -> {
            JsonNode docs = read(json).path("response").path("docs");
            return docs.isEmpty()
                    ? Optional.<String>empty()
                    : Optional.of(docs.get(0).path("identifier").asText());
        });
    }

    /**
     * Пряме посилання на відеофайл елемента. Найкраще — похідний H.264 від самого Archive:
     * помірний розмір, тож добре йде навіть при повільному інтернеті. Інакше — найбільший MP4, потім інші формати.
     */
    public CompletableFuture<Optional<String>> bestVideoUrl(String identifier) {
        return get("metadata/" + pathSegment(identifier)).thenApply(json -> {
            String best = null;
            long bestScore = -1;
            for (JsonNode file : read(json).path("files")) {
                String name = file.path("name").asText("");
                String lower = name.toLowerCase(Locale.ROOT);
                if (VIDEO_EXTENSIONS.stream().noneMatch(lower::endsWith)) {
                    continue;
                }
                long size = file.path("size").asLong(0); // у метаданих розмір — рядок; asLong його розбере
                boolean derivedH264 = "h.264".equalsIgnoreCase(file.path("format").asText());
                boolean mp4 = lower.endsWith(".mp4");
                // Пріоритет: H.264 від Archive → будь-який MP4 → решта; у межах групи — більший файл
                long score = (derivedH264 ? 2_000_000_000_000L : mp4 ? 1_000_000_000_000L : 0) + size;
                if (score > bestScore) {
                    best = name;
                    bestScore = score;
                }
            }
            return Optional.ofNullable(best)
                    .map(name -> BASE.resolve("download/" + pathSegment(identifier) + "/" + pathSegment(name)).toString());
        });
    }

    private CompletableFuture<String> get(String path) {
        var request = HttpRequest.newBuilder(BASE.resolve(path))
                .header("User-Agent", "KinoBox/0.1 (personal media app)")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Internet Archive: HTTP " + response.statusCode() + " для " + path);
            }
            return response.body();
        });
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Кодування для частини шляху: пробіл — %20, а не «+», як у параметрах запиту. */
    private static String pathSegment(String value) {
        return encode(value).replace("+", "%20");
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}