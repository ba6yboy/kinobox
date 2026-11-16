package ua.kinobox.data;

/**
 * Епізод серіалу.
 *
 * @param runtimeMin тривалість у хвилинах; 0 — невідомо
 * @param stillUrl   кадр із серії; null — немає
 */
public record Episode(int season, int number, String name, String overview, int runtimeMin, String stillUrl) {
}