package ua.kinobox.data;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Конкретна серія: сезон і номер. Уміє будувати ключ прогресу і розбирати його назад. */
public record EpisodeRef(int season, int episode) implements Comparable<EpisodeRef> {

    public static final EpisodeRef FIRST = new EpisodeRef(1, 1);

    /** Порядок перегляду: спершу сезон, потім номер серії. */
    private static final Comparator<EpisodeRef> ORDER =
            Comparator.comparingInt(EpisodeRef::season).thenComparingInt(EpisodeRef::episode);
    /** Кінець ключа прогресу: ":s2e5". Не більше 4 цифр — щоб parseInt ніколи не переповнився. */
    private static final Pattern KEY = Pattern.compile(":s(\\d{1,4})e(\\d{1,4})$");

    /** "tv:1399:s2e5" → С2:Е5; для ключа фільму ("movie:603") — порожньо. */
    public static Optional<EpisodeRef> parse(String key) {
        Matcher m = KEY.matcher(key);
        return m.find()
                ? Optional.of(new EpisodeRef(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))))
                : Optional.empty();
    }

    /** "s2e5" → С2:Е5 (короткий код з індексу Jellyfin). */
    public static Optional<EpisodeRef> fromCode(String code) {
        return parse(":" + code);
    }

    /** Короткий код "s2e5" — так само позначені серії в індексі Jellyfin. */
    public String code() {
        return "s" + season + "e" + episode;
    }

    /** Ключ прогресу серії: "tv:1399:s2e5". */
    public String key(Title series) {
        return series.id() + ":" + code();
    }

    /** Для людей: "С2:Е5". */
    public String label() {
        return "С" + season + ":Е" + episode;
    }

    /** Наступна серія того самого сезону (без перевірки, чи вона існує). */
    public EpisodeRef next() {
        return new EpisodeRef(season, episode + 1);
    }

    @Override
    public int compareTo(EpisodeRef other) {
        return ORDER.compare(this, other);
    }
}
