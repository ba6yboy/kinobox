package ua.kinobox.data;

import java.util.List;

/** Горизонтальний ряд на головному екрані: «Популярне», «Нове» тощо. */
public record CatalogRow(String title, List<Title> items) {
}
