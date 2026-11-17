package ua.kinobox.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Де легально подивитися тайтл у країні (дані JustWatch через TMDB).
 *
 * @param link сторінка з посиланнями на всі сервіси (відкривається в браузері)
 */
public record WatchOptions(String link, List<String> subscription, List<String> free,
                           List<String> rent, List<String> buy) {

    public boolean isEmpty() {
        return subscription.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty();
    }

    /** «MEGOGO, Netflix — за підпискою   ·   Apple TV — оренда» */
    public String summary() {
        var parts = new ArrayList<String>();
        add(parts, subscription, "за підпискою");
        add(parts, free, "безкоштовно");
        add(parts, rent, "оренда");
        add(parts, buy, "купівля");
        return String.join("   ·   ", parts);
    }

    private static void add(List<String> parts, List<String> names, String kind) {
        if (!names.isEmpty()) {
            parts.add(String.join(", ", names) + " — " + kind);
        }
    }
}