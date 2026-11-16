package ua.kinobox.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Каталог з TMDB: ряди головного екрана, описи й зображення українською.
 */
public final class TmdbCatalogService implements CatalogService {

    private static final String IMAGE_BASE = "https://image.tmdb.org/t/p/";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_GENRES = 3;
    private static final List<RowSpec> HOME_ROWS = List.of(
            new RowSpec("У тренді цього тижня", "trending/all/week", Map.of(), null),
            new RowSpec("Популярні фільми", "movie/popular", Map.of(), Title.Kind.MOVIE),
            new RowSpec("Популярні серіали", "tv/popular", Map.of(), Title.Kind.SERIES),
            new RowSpec("Українське кіно", "discover/movie",
                    Map.of("with_origin_country", "UA", "sort_by", "popularity.desc"), Title.Kind.MOVIE),
            new RowSpec("Найвищий рейтинг", "movie/top_rated", Map.of(), Title.Kind.MOVIE),
            new RowSpec("Серіали з найвищим рейтингом", "tv/top_rated", Map.of(), Title.Kind.SERIES)
    );
    private static final List<RowSpec> MOVIE_ROWS = List.of(
            new RowSpec("Популярні фільми", "movie/popular", Map.of(), Title.Kind.MOVIE),
            new RowSpec("Зараз у кінотеатрах", "movie/now_playing", Map.of(), Title.Kind.MOVIE),
            new RowSpec("Найвищий рейтинг", "movie/top_rated", Map.of(), Title.Kind.MOVIE),
            genreRow("Бойовики", Title.Kind.MOVIE, 28),
            genreRow("Комедії", Title.Kind.MOVIE, 35),
            genreRow("Фантастика", Title.Kind.MOVIE, 878),
            genreRow("Жахи", Title.Kind.MOVIE, 27),
            genreRow("Анімація", Title.Kind.MOVIE, 16),
            genreRow("Документальні", Title.Kind.MOVIE, 99));
    private static final List<RowSpec> SERIES_ROWS = List.of(
            new RowSpec("Популярні серіали", "tv/popular", Map.of(), Title.Kind.SERIES),
            new RowSpec("Зараз в ефірі", "tv/on_the_air", Map.of(), Title.Kind.SERIES),
            new RowSpec("Найвищий рейтинг", "tv/top_rated", Map.of(), Title.Kind.SERIES),
            genreRow("Драми", Title.Kind.SERIES, 18),
            genreRow("Комедії", Title.Kind.SERIES, 35),
            genreRow("Кримінал", Title.Kind.SERIES, 80),
            genreRow("Фантастика і фентезі", Title.Kind.SERIES, 10765),
            genreRow("Анімація", Title.Kind.SERIES, 16),
            genreRow("Документальні", Title.Kind.SERIES, 99));
    private final TmdbClient client;
    /**
     * Довідник жанрів «число → назва». Завантажується один раз на старті.
     */
    private final CompletableFuture<Map<Integer, String>> genres;
    /**
     * Ряди розділів «Фільми» й «Серіали»: завантажуємо один раз на розділ.
     */
    private final Map<Title.Kind, CompletableFuture<List<CatalogRow>>> sectionRows = new EnumMap<>(Title.Kind.class);
    /**
     * Ряди головного екрана: завантажуємо один раз і далі віддаємо готові.
     */
    private CompletableFuture<List<CatalogRow>> homeRows;
    public TmdbCatalogService(TmdbClient client) {
        this.client = client;
        // Два запити йдуть одночасно; thenCombine чекає обидва й об'єднує результати
        this.genres = client.get("genre/movie/list", Map.of())
                .thenCombine(client.get("genre/tv/list", Map.of()), (movieJson, tvJson) -> {
                    var map = new HashMap<Integer, String>();
                    readGenres(movieJson, map);
                    readGenres(tvJson, map);
                    return Map.copyOf(map);
                });
    }

    /**
     * Ряд «усе з жанру N, від найпопулярнішого». Номери жанрів — з довідника TMDB.
     */
    private static RowSpec genreRow(String title, Title.Kind kind, int genreId) {
        String path = kind == Title.Kind.MOVIE ? "discover/movie" : "discover/tv";
        return new RowSpec(title, path,
                Map.of("with_genres", String.valueOf(genreId), "sort_by", "popularity.desc"), kind);
    }

    /**
     * "tv:1399" → "1399": у Title ми зберігали id разом із типом.
     */
    private static String tmdbId(Title t) {
        return t.id().substring(t.id().indexOf(':') + 1);
    }

    private static List<Title> parseList(String json, Title.Kind fixedKind, Map<Integer, String> genres) {
        var result = new ArrayList<Title>();
        for (JsonNode item : read(json).path("results")) {
            Title.Kind kind = fixedKind != null ? fixedKind : kindOf(item.path("media_type").asText());
            if (kind != null) { // у trending трапляються й актори (media_type = person) — їх пропускаємо
                result.add(toTitle(item, kind, genres));
            }
        }
        return List.copyOf(result);
    }

    private static Title toTitle(JsonNode item, Title.Kind kind, Map<Integer, String> genres) {
        boolean movie = kind == Title.Kind.MOVIE;
        int tmdbId = item.path("id").asInt();

        String date = text(item.path(movie ? "release_date" : "first_air_date"));
        int year = date != null && date.length() >= 4 ? Integer.parseInt(date.substring(0, 4)) : 0;

        var genreNames = new ArrayList<String>();
        for (JsonNode id : item.path("genre_ids")) {
            String name = genres.get(id.asInt());
            if (name != null && genreNames.size() < MAX_GENRES) {
                genreNames.add(capitalize(name));
            }
        }

        String overview = text(item.path("overview"));
        if (overview == null || overview.isBlank()) {
            overview = "Опису українською поки немає.";
        }

        return new Title(
                (movie ? "movie:" : "tv:") + tmdbId,           // id унікальний серед фільмів і серіалів
                text(item.path(movie ? "title" : "name")),
                kind,
                year,
                List.copyOf(genreNames),
                Math.round(item.path("vote_average").asDouble() * 10) / 10.0,
                overview,
                image(item.path("poster_path"), "w342"),
                image(item.path("backdrop_path"), "w1280"),
                Math.floorMod(tmdbId * 47, 360));              // колір заглушки, поки вантажиться картинка
    }

    private static void readGenres(String json, Map<Integer, String> into) {
        for (JsonNode genre : read(json).path("genres")) {
            into.put(genre.path("id").asInt(), genre.path("name").asText());
        }
    }

    private static Title.Kind kindOf(String mediaType) {
        return switch (mediaType) {
            case "movie" -> Title.Kind.MOVIE;
            case "tv" -> Title.Kind.SERIES;
            default -> null;
        };
    }

    /**
     * Повна адреса зображення. Розмір: w342, w780, original тощо.
     */
    private static String image(JsonNode path, String size) {
        String p = text(path);
        return p == null ? null : IMAGE_BASE + size + p;
    }

    /**
     * Текст вузла або null. Увага: для JSON-значення null метод asText() повертає рядок "null".
     */
    private static String text(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized CompletableFuture<List<CatalogRow>> homeRows() {
        if (homeRows == null || homeRows.isCompletedExceptionally()) {
            homeRows = loadRows(HOME_ROWS);
        }
        return homeRows;
    }

    // ---------- Розбір JSON ----------

    @Override
    public synchronized CompletableFuture<List<CatalogRow>> rows(Title.Kind kind) {
        var cached = sectionRows.get(kind);
        if (cached == null || cached.isCompletedExceptionally()) {
            cached = loadRows(kind == Title.Kind.MOVIE ? MOVIE_ROWS : SERIES_ROWS);
            sectionRows.put(kind, cached);
        }
        return cached;
    }

    /**
     * Усі ряди паралельно; порожні й невдалі ряди пропускаються.
     */
    private CompletableFuture<List<CatalogRow>> loadRows(List<RowSpec> specs) {
        List<CompletableFuture<CatalogRow>> requests = specs.stream()
                .map(this::loadRow)
                .toList();
        return CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
                .thenApply(done -> {
                    var rows = requests.stream()
                            .map(CompletableFuture::join)        // тут вже все завершено — join не чекає
                            .filter(row -> !row.items().isEmpty())
                            .toList();
                    if (rows.isEmpty()) {
                        throw new IllegalStateException("TMDB не повернув жодного ряду (подробиці вище в лозі)");
                    }
                    return rows;
                });
    }

    @Override
    public CompletableFuture<Title> featured() {
        return homeRows().thenApply(rows -> rows.getFirst().items().getFirst());
    }

    @Override
    public CompletableFuture<List<Title>> search(String query) {
        // search/multi шукає одночасно фільми, серіали й людей; людей parseList пропустить
        return client.get("search/multi", Map.of("query", query, "include_adult", "false"))
                .thenCombine(genres, (json, genreNames) -> parseList(json, null, genreNames));
    }

    @Override
    public CompletableFuture<List<Season>> seasons(Title series) {
        if (series.kind() != Title.Kind.SERIES) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client.get("tv/" + tmdbId(series), Map.of()).thenApply(json -> {
            var result = new ArrayList<Season>();
            for (JsonNode s : read(json).path("seasons")) {
                int number = s.path("season_number").asInt();
                int count = s.path("episode_count").asInt();
                // Сезон 0 — «Спецвипуски»; сезони без епізодів ще не вийшли
                if (number > 0 && count > 0) {
                    String name = text(s.path("name"));
                    result.add(new Season(number, name != null && !name.isBlank() ? name : "Сезон " + number, count));
                }
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<List<Episode>> episodes(Title series, int season) {
        return client.get("tv/" + tmdbId(series) + "/season/" + season, Map.of()).thenApply(json -> {
            var result = new ArrayList<Episode>();
            for (JsonNode e : read(json).path("episodes")) {
                int number = e.path("episode_number").asInt();
                String name = text(e.path("name"));
                result.add(new Episode(
                        season,
                        number,
                        name != null && !name.isBlank() ? name : "Епізод " + number,
                        Objects.requireNonNullElse(text(e.path("overview")), ""),
                        e.path("runtime").asInt(0),
                        image(e.path("still_path"), "w300")));
            }
            return List.copyOf(result);
        });
    }

    /**
     * Один ряд. Помилка одного ряду не ламає весь екран — ряд просто буде порожнім і не покажеться.
     */
    private CompletableFuture<CatalogRow> loadRow(RowSpec spec) {
        return client.get(spec.path(), spec.params())
                .thenCombine(genres, (json, genreNames) ->
                        new CatalogRow(spec.title(), parseList(json, spec.kind(), genreNames)))
                .exceptionally(error -> {
                    System.err.println("TMDB, ряд «" + spec.title() + "»: " + error.getMessage());
                    return new CatalogRow(spec.title(), List.of());
                });
    }

    /**
     * Опис одного ряду: заголовок, адреса запиту, параметри
     * і тип вмісту (null — тип вказаний у кожному елементі, як у trending).
     */
    private record RowSpec(String title, String path, Map<String, String> params, Title.Kind kind) {
    }
}