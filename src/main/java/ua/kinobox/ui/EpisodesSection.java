package ua.kinobox.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import ua.kinobox.data.CatalogService;
import ua.kinobox.data.Episode;
import ua.kinobox.data.Season;
import ua.kinobox.data.Title;

import java.util.List;
import java.util.function.Consumer;

/**
 * Блок «Сезони та епізоди» на сторінці серіалу. Дані підвантажуються асинхронно.
 */
final class EpisodesSection {

    private static final double STILL_WIDTH = 256;
    private static final double STILL_HEIGHT = 144;

    private final Title series;
    private final CatalogService catalog;
    private final Consumer<Title> onPlay;

    private final VBox root = new VBox(6);
    private final HBox seasonTabs = new HBox(10);
    private final HBox cards = new HBox();
    private final Label status = new Label("Завантаження сезонів…");

    private Button activeTab;
    /**
     * Сезон, який користувач вибрав останнім. Захищає від запізнілих відповідей.
     */
    private int requestedSeason;

    EpisodesSection(Title series, CatalogService catalog, Consumer<Title> onPlay) {
        this.series = series;
        this.catalog = catalog;
        this.onPlay = onPlay;

        seasonTabs.getStyleClass().add("season-tabs");
        cards.getStyleClass().add("row-cards");

        var scroll = new ScrollPane(cards);
        scroll.getStyleClass().setAll("row-scroll", "episodes-scroll");
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setFocusTraversable(false);

        // Напис статусу займає місце лише тоді, коли в ньому є текст
        status.getStyleClass().add("episode-meta");
        status.managedProperty().bind(status.textProperty().isNotEmpty());
        status.visibleProperty().bind(status.managedProperty());

        // Вкладки сезонів — у власному прокрутному рядку, як і епізоди:
        // скільки б сезонів не було, сторінка не розширюється за межі екрана
        var tabsScroll = new ScrollPane(seasonTabs);
        tabsScroll.getStyleClass().setAll("row-scroll");
        tabsScroll.setFitToHeight(true);
        tabsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tabsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        tabsScroll.setFocusTraversable(false);

        root.getChildren().addAll(tabsScroll, status, scroll);
        catalog.seasons(series).whenComplete((seasons, error) ->
                Platform.runLater(() -> showSeasons(seasons, error)));
    }

    Node node() {
        return root;
    }

    private void showSeasons(List<Season> seasons, Throwable error) {
        if (error != null) {
            System.err.println("Сезони «" + series.name() + "»: " + error.getMessage());
            status.setText("Не вдалося завантажити сезони");
            return;
        }
        if (seasons.isEmpty()) {
            status.setText("Епізодів поки немає");
            return;
        }
        for (Season season : seasons) {
            var tab = new Button(season.name());
            tab.getStyleClass().setAll("season-tab");
            tab.setMinWidth(Region.USE_PREF_SIZE); // не стискати до «...»: краще прокрутити, ніж обрізати текст
            tab.setOnAction(e -> selectSeason(tab, season));
            seasonTabs.getChildren().add(tab);
        }
        selectSeason((Button) seasonTabs.getChildren().getFirst(), seasons.getFirst());
    }

    private void selectSeason(Button tab, Season season) {
        if (activeTab != null) {
            activeTab.getStyleClass().remove("active");
        }
        activeTab = tab;
        tab.getStyleClass().add("active");

        requestedSeason = season.number();
        cards.getChildren().clear();
        status.setText("Завантаження епізодів…");

        catalog.episodes(series, season.number()).whenComplete((episodes, error) -> Platform.runLater(() -> {
            // Поки відповідь ішла, користувач міг вибрати інший сезон — тоді ця відповідь застаріла
            if (season.number() != requestedSeason) {
                return;
            }
            if (error != null) {
                System.err.println("Епізоди «" + series.name() + "», сезон " + season.number() + ": "
                        + error.getMessage());
                status.setText("Не вдалося завантажити епізоди");
                return;
            }
            status.setText("");
            for (Episode episode : episodes) {
                cards.getChildren().add(card(episode));
            }
        }));
    }

    private Button card(Episode episode) {
        var still = Artwork.still(episode.stillUrl(), series.accentHue(), STILL_WIDTH, STILL_HEIGHT);

        var name = new Label(episode.number() + ". " + episode.name());
        name.getStyleClass().add("episode-title");
        name.setMaxWidth(STILL_WIDTH);

        var meta = new Label(episode.runtimeMin() > 0 ? episode.runtimeMin() + " хв" : "");
        meta.getStyleClass().add("episode-meta");

        var button = FocusZoom.install(new Button(null, new VBox(8, still, name, meta)), 1.06);
        button.getStyleClass().setAll("episode-card");
        button.setOnAction(e -> onPlay.accept(series)); // поки всі епізоди відкривають одне відео
        return button;
    }
}