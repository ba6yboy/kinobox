package ua.kinobox.ui;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
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
import ua.kinobox.data.Episode;
import ua.kinobox.data.MyList;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;
import ua.kinobox.data.WatchOptions;

import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Сторінка фільму або серіалу: опис, кнопки дій, для серіалів — сезони й епізоди. */
public final class DetailsScreen implements Screen {

    private final StackPane root = new StackPane();
    private final Button play;

    /**
     * @param progress      позиція перегляду (null — ще не дивилися); оновлюється сама
     * @param available     чи є відео в джерелі; може змінитися, коли джерело довантажиться
     * @param onPlayEpisode вибір конкретної серії на сторінці серіалу
     */
    public DetailsScreen(Title t, MyList myList, Consumer<String> notify, Consumer<Title> onPlay,
                         ObservableValue<ProgressStore.Progress> progress, CatalogService catalog,
                         ObservableValue<Boolean> available, BiConsumer<Title, Episode> onPlayEpisode) {
        root.getStyleClass().add("details");

        var fadeLeft = new Region();
        fadeLeft.getStyleClass().add("billboard-fade-left");
        var fadeBottom = new Region();
        fadeBottom.getStyleClass().add("details-fade-bottom");

        // Де подивитися онлайн — вантажиться у фоні; null, поки не прийшло або даних немає
        var where = new SimpleObjectProperty<WatchOptions>();
        catalog.watchOptions(t).thenAccept(options ->
                options.ifPresent(o -> Platform.runLater(() -> where.set(o))));

        play = playButton(t, onPlay, progress, available, where);
        var actions = new HBox(16, new VBox(8, play, watchedBar(progress)), listButton(t, myList, notify));
        actions.setAlignment(Pos.TOP_LEFT); // кнопки вирівняні по верху: смужка під «Дивитися» їх не зсуває

        var content = new VBox(18, header(t), actions, whereLabel(available, where));
        content.getStyleClass().add("details-content");
        content.setAlignment(Pos.BOTTOM_LEFT);
        if (t.kind() == Title.Kind.SERIES) {
            content.getChildren().add(new EpisodesSection(t, catalog, onPlayEpisode).node());
        }

        root.getChildren().addAll(Artwork.backdrop(t), fadeLeft, fadeBottom, content);
    }

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return play;
    }

    /** Назва, рядок «★ рейтинг · рік · тип · жанри» і опис. */
    private static Node header(Title t) {
        var name = new Label(t.name());
        name.getStyleClass().add("billboard-title");
        name.setWrapText(true);

        var rating = new Label(String.format(Locale.ROOT, "★ %.1f", t.rating()));
        rating.getStyleClass().add("rating");
        var meta = new HBox(18, rating,
                new Label(t.year() > 0 ? String.valueOf(t.year()) : ""),
                new Label(t.kindLabel()),
                new Label(String.join(" · ", t.genres())));
        meta.getStyleClass().add("billboard-meta");
        meta.setAlignment(Pos.CENTER_LEFT);

        var overview = new Label(t.overview());
        overview.getStyleClass().add("billboard-overview");
        overview.setWrapText(true);
        overview.setMaxWidth(720);

        return new VBox(18, name, meta, overview);
    }

    /** «Дивитися» / «Продовжити з 12:34» / «Де подивитися» / «Немає у медіатеці». */
    private static Button playButton(Title t, Consumer<Title> onPlay, ObservableValue<ProgressStore.Progress> progress,
                                     ObservableValue<Boolean> available, ObjectProperty<WatchOptions> where) {
        var button = FocusZoom.install(new Button(null, Icons.of(Icons.PLAY)), 1.06);
        button.getStyleClass().setAll("primary-button");
        button.setOnAction(e -> onPlay.accept(t));

        // Текст оновлюється сам, коли плеєр зберігає позицію
        ObservableValue<String> watchText = progress
                .map(p -> "Продовжити з " + PlayerScreen.format(p.positionMs()))
                .orElse("Дивитися");
        // Текст залежить від трьох значень: чи є відео, чи почали дивитися, чи є де подивитися онлайн
        button.textProperty().bind(Bindings.createStringBinding(() -> {
            if (Boolean.TRUE.equals(available.getValue())) {
                return watchText.getValue();
            }
            return where.get() != null ? "Де подивитися" : "Немає у медіатеці";
        }, available, watchText, where));
        // Приглушуємо, лише коли дивитися ніде: ні в джерелі відео, ні онлайн
        button.opacityProperty().bind(Bindings.createDoubleBinding(
                () -> Boolean.TRUE.equals(available.getValue()) || where.get() != null ? 1.0 : 0.55,
                available, where));
        return button;
    }

    /** Смужка переглянутого під кнопкою «Дивитися»; схована, поки не почали дивитися. */
    private static Node watchedBar(ObservableValue<ProgressStore.Progress> progress) {
        var bar = new ProgressBar();
        bar.getStyleClass().add("details-progress");
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.progressProperty().bind(progress.map(ProgressStore.Progress::share).orElse(0.0));
        bar.visibleProperty().bind(progress.map(p -> p.lengthMs() > 0).orElse(false));
        bar.managedProperty().bind(bar.visibleProperty());
        return bar;
    }

    /** «Мій список» / «У моєму списку» — перемикач. */
    private static Button listButton(Title t, MyList myList, Consumer<String> notify) {
        var button = FocusZoom.install(new Button(), 1.06);
        button.getStyleClass().setAll("secondary-button");
        Runnable refresh = () -> {
            boolean in = myList.contains(t.id());
            button.setText(in ? "У моєму списку" : "Мій список");
            button.setGraphic(Icons.of(in ? Icons.CHECK : Icons.ADD));
        };
        refresh.run();
        button.setOnAction(e -> {
            boolean added = myList.toggle(t);
            refresh.run();
            notify.accept(added ? "Додано до «Мого списку»" : "Прибрано з «Мого списку»");
        });
        return button;
    }

    /** «Де подивитися: MEGOGO — за підпискою…» — лише коли відео немає в джерелі, але є онлайн. */
    private static Node whereLabel(ObservableValue<Boolean> available, ObjectProperty<WatchOptions> where) {
        var label = new Label();
        label.getStyleClass().add("details-where");
        label.setWrapText(true);
        label.setMaxWidth(900);
        // Атрибуція JustWatch — обов'язкова умова TMDB для цих даних
        label.textProperty().bind(where.map(o -> "Де подивитися: " + o.summary() + "     (дані: JustWatch)")
                .orElse(""));
        label.visibleProperty().bind(Bindings.createBooleanBinding(
                () -> !Boolean.TRUE.equals(available.getValue()) && where.get() != null, available, where));
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }
}
