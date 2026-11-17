package ua.kinobox.data;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

/**
 * Вигадані дані для розробки UI без мережі.
 */
public final class MockCatalogService implements CatalogService {

    private static final String[] ADJ = {
            "Тиха", "Остання", "Північна", "Загублена", "Крижана", "Золота", "Нічна", "Дика",
            "Темна", "Далека", "Безмежна", "Сталева", "Небесна", "Таємна", "Вічна", "Червона"
    };
    private static final String[] NOUN = {
            "гавань", "межа", "зоря", "станція", "ріка", "долина", "орбіта", "фортеця",
            "хвиля", "пустеля", "легенда", "траса", "мапа", "буря", "тиша", "експедиція"
    };
    private static final String[] GENRES = {
            "Драма", "Трилер", "Фантастика", "Комедія", "Пригоди", "Детектив", "Документальний", "Анімація"
    };
    private static final String[] ROWS = {
            "Популярне зараз", "Нові серіали", "Продовжити перегляд", "Найвищий рейтинг",
            "Фантастика і пригоди", "Документальне кіно", "Бо ви дивилися «Тиха гавань»"
    };

    private final List<CatalogRow> rows;

    public MockCatalogService() {
        var rnd = new Random(42);
        var result = new ArrayList<CatalogRow>();
        int id = 0;
        for (String rowTitle : ROWS) {
            var items = new ArrayList<Title>();
            int count = 12 + rnd.nextInt(10);
            for (int i = 0; i < count; i++) {
                items.add(randomTitle(rnd, "t" + id++));
            }
            result.add(new CatalogRow(rowTitle, List.copyOf(items)));
        }
        this.rows = List.copyOf(result);
    }

    private static Title randomTitle(Random rnd, String id) {
        String name = ADJ[rnd.nextInt(ADJ.length)] + " " + NOUN[rnd.nextInt(NOUN.length)];
        var kind = rnd.nextBoolean() ? Title.Kind.MOVIE : Title.Kind.SERIES;
        var genres = List.of(GENRES[rnd.nextInt(GENRES.length)], GENRES[rnd.nextInt(GENRES.length)])
                .stream().distinct().toList();
        double rating = Math.round((5.5 + rnd.nextDouble() * 4.0) * 10) / 10.0;
        String overview = "Коли " + NOUN[rnd.nextInt(NOUN.length)] + " приховує більше, ніж здається, "
                + "героям доведеться вирішити, кому довіряти. Історія про вибір, ціну правди "
                + "і людей, які не здаються навіть тоді, коли все проти них.";
        return new Title(id, name, kind, 1995 + rnd.nextInt(31), genres, rating, overview,
                null, null, rnd.nextDouble() * 360, name);
    }

    @Override
    public CompletableFuture<Title> featured() {
        return CompletableFuture.completedFuture(rows.getFirst().items().getFirst());
    }

    @Override
    public CompletableFuture<List<CatalogRow>> homeRows() {
        return CompletableFuture.completedFuture(rows);
    }

    @Override
    public CompletableFuture<List<CatalogRow>> rows(Title.Kind kind) {
        // Ті самі демо-ряди, але лише з фільмами або лише із серіалами
        return CompletableFuture.completedFuture(rows.stream()
                .map(row -> new CatalogRow(row.title(),
                        row.items().stream().filter(t -> t.kind() == kind).toList()))
                .filter(row -> !row.items().isEmpty())
                .toList());
    }

    @Override
    public CompletableFuture<List<Title>> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(rows.stream()
                .flatMap(row -> row.items().stream())
                .filter(t -> t.name().toLowerCase(Locale.ROOT).contains(q))
                .distinct()
                .limit(40)
                .toList());
    }

    @Override
    public CompletableFuture<List<Season>> seasons(Title series) {
        if (series.kind() != Title.Kind.SERIES) {
            return CompletableFuture.completedFuture(List.of());
        }
        int count = 1 + Math.floorMod(series.id().hashCode(), 4); // 1–4 сезони, стабільно для кожного тайтлу
        return CompletableFuture.completedFuture(IntStream.rangeClosed(1, count)
                .mapToObj(n -> new Season(n, "Сезон " + n, 8))
                .toList());
    }

    @Override
    public CompletableFuture<List<Episode>> episodes(Title series, int season) {
        return CompletableFuture.completedFuture(IntStream.rangeClosed(1, 8)
                .mapToObj(n -> new Episode(season, n, "Епізод " + n, "", 38 + (n * 7) % 20, null))
                .toList());
    }

    @Override
    public CompletableFuture<Optional<Title>> title(String id) {
        return CompletableFuture.completedFuture(rows.stream()
                .flatMap(row -> row.items().stream())
                .filter(t -> t.id().equals(id))
                .findFirst());
    }
}
