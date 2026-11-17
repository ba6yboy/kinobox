package ua.kinobox.ui;

import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import ua.kinobox.data.CatalogService;
import ua.kinobox.data.MyList;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;

import java.util.Locale;
import java.util.function.Consumer;

import javafx.beans.binding.Bindings;

import ua.kinobox.data.Episode;

import java.util.function.BiConsumer;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import ua.kinobox.data.WatchOptions;

/**
 * Сторінка фільму або серіалу: опис, кнопки дій, для серіалів — епізоди.
 */
public final class DetailsScreen implements Screen {

    private final StackPane root = new StackPane();
    private final Button play;

    public DetailsScreen(Title t, MyList myList, Consumer<String> notify, Consumer<Title> onPlay,
                         ObservableValue<ProgressStore.Progress> progress, CatalogService catalog,
                         ObservableValue<Boolean> available, BiConsumer<Title, Episode> onPlayEpisode) {

        root.getStyleClass().add("details");

        var art = Artwork.backdrop(t);
        var fadeLeft = new Region();
        fadeLeft.getStyleClass().add("billboard-fade-left");
        var fadeBottom = new Region();
        fadeBottom.getStyleClass().add("details-fade-bottom");

        var name = new Label(t.name());
        name.getStyleClass().add("billboard-title");
        name.setWrapText(true);

        var rating = new Label(String.format(Locale.ROOT, "★ %.1f", t.rating()));
        rating.getStyleClass().add("rating");
        var meta = new HBox(18, rating, new Label(t.year() > 0 ? String.valueOf(t.year()) : ""), new Label(t.kindLabel()),
                new Label(String.join(" · ", t.genres())));
        meta.getStyleClass().add("billboard-meta");
        meta.setAlignment(Pos.CENTER_LEFT);

        var overview = new Label(t.overview());
        overview.getStyleClass().add("billboard-overview");
        overview.setWrapText(true);
        overview.setMaxWidth(720);

        // ---------- Кнопка «Дивитися» / «Продовжити» і смужка прогресу ----------

        play = FocusZoom.install(new Button(null, Icons.of(Icons.PLAY)), 1.06);
        play.getStyleClass().setAll("primary-button");
        play.setOnAction(e -> onPlay.accept(t));

        String startText = "Дивитися";
        // Де подивитися онлайн — вантажиться у фоні; null, поки не прийшло або даних немає
        var where = new SimpleObjectProperty<WatchOptions>();
        catalog.watchOptions(t).thenAccept(options ->
                options.ifPresent(o -> Platform.runLater(() -> where.set(o))));
        // Текст кнопки і смужка самі оновлюються, коли плеєр зберігає позицію
        ObservableValue<String> watchText = progress
                .map(p -> "Продовжити з " + PlayerScreen.format(p.positionMs()))
                .orElse(startText);
        // Текст залежить одразу від двох значень: чи є файл у медіатеці і чи почали дивитися
        play.textProperty().bind(Bindings.createStringBinding(() -> {
            if (Boolean.TRUE.equals(available.getValue())) {
                return watchText.getValue();
            }
            return where.get() != null ? "Де подивитися" : "Немає у медіатеці";
        }, available, watchText, where));
        // Приглушуємо, лише коли дивитися ніде: ні в медіатеці, ні онлайн
        play.opacityProperty().bind(Bindings.createDoubleBinding(
                () -> Boolean.TRUE.equals(available.getValue()) || where.get() != null ? 1.0 : 0.55,
                available, where));

        var whereLabel = new Label();
        whereLabel.getStyleClass().add("details-where");
        whereLabel.setWrapText(true);
        whereLabel.setMaxWidth(900);
        // Атрибуція JustWatch — обов'язкова умова TMDB для цих даних
        whereLabel.textProperty().bind(where.map(o -> "Де подивитися: " + o.summary() + "     (дані: JustWatch)")
                .orElse(""));
        whereLabel.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> !Boolean.TRUE.equals(available.getValue()) && where.get() != null, available, where));
        whereLabel.managedProperty().bind(whereLabel.visibleProperty());

        var watched = new ProgressBar();
        watched.getStyleClass().add("details-progress");
        watched.setMaxWidth(Double.MAX_VALUE);
        watched.progressProperty().bind(progress.map(ProgressStore.Progress::share).orElse(0.0));
        watched.visibleProperty().bind(progress.map(p -> p.lengthMs() > 0).orElse(false));
        watched.managedProperty().bind(watched.visibleProperty());

        // Кнопка і смужка під нею — одним блоком
        var playBox = new VBox(8, play, watched);

        // ---------- «Мій список» ----------

        var listButton = FocusZoom.install(new Button(), 1.06);
        listButton.getStyleClass().setAll("secondary-button");
        Runnable refreshList = () -> {
            boolean in = myList.contains(t.id());
            listButton.setText(in ? "У моєму списку" : "Мій список");
            listButton.setGraphic(Icons.of(in ? Icons.CHECK : Icons.ADD));
        };
        refreshList.run();
        listButton.setOnAction(e -> {
            boolean added = myList.toggle(t);
            refreshList.run();
            notify.accept(added ? "Додано до «Мого списку»" : "Прибрано з «Мого списку»");
        });

        // ---------- Розкладка ----------

        var actions = new HBox(16, playBox, listButton); // тут тепер playBox замість play
        actions.setAlignment(Pos.TOP_LEFT);              // кнопки вирівняні по верху, смужка не зсуває їх

        var content = new VBox(18, name, meta, overview, actions, whereLabel);
        content.getStyleClass().add("details-content");
        content.setAlignment(Pos.BOTTOM_LEFT);

        if (t.kind() == Title.Kind.SERIES) {
            content.getChildren().add(new EpisodesSection(t, catalog, onPlayEpisode).node());        }

        root.getChildren().addAll(art, fadeLeft, fadeBottom, content);
    }

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return play;
    }
}