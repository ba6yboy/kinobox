package ua.kinobox.ui;

import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ua.kinobox.data.ProgressStore;
import ua.kinobox.data.Title;
import ua.kinobox.input.Command;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.TrackDescription;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Відеоплеєр на vlcj (libVLC) з двома режимами, як у Netflix:
 * повний (відео на весь екран) і компактний (відео зменшене, праворуч — серії).
 *
 * <pre>
 * Повний режим:
 *   ✕ / Enter          пауза / продовжити
 *   ← / →              −10 / +10 секунд
 *   L1,L2 / R1,R2      −30 / +30 секунд
 *   □ / F / F11        компактний режим
 *   ○ / Esc            закрити плеєр
 * Компактний режим:
 *   стрілки, ✕         навігація по панелі та вибір
 *   □ або ○            назад до повного відео
 * </pre>
 */
public final class PlayerScreen implements Screen {

    private static final long SHORT_SEEK_MS = 10_000;
    private static final long LONG_SEEK_MS = 30_000;
    /**
     * Як часто зберігати позицію під час перегляду.
     */
    private static final long AUTOSAVE_INTERVAL_MS = 10_000;
    /**
     * Яку частку ширини екрана займає відео в компактному режимі.
     */
    private static final double COMPACT_VIDEO_SHARE = 0.62;
    /**
     * Опції libVLC. --quiet вимикає технічні повідомлення VLC у консолі.
     */
    private static final String[] VLC_OPTIONS = {"--quiet"};

    private final StackPane root = new StackPane();
    private final StackPane videoBox = new StackPane();
    private final ImageView video = new ImageView();
    private final VBox overlay = new VBox(14);
    private final VBox side = new VBox(16);
    private final HBox tracksMenu = new HBox(48);
    private final Label stateIcon = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label time = new Label("0:00 / 0:00");
    private final PauseTransition hideTimer = new PauseTransition(Duration.seconds(3));
    /**
     * Частка ширини під відео: 1.0 — повний режим, COMPACT_VIDEO_SHARE — компактний.
     */
    private final DoubleProperty videoShare = new SimpleDoubleProperty(1.0);

    private final Title title;
    private final String source;
    private final ProgressStore progressStore;
    private final Consumer<String> notify;

    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer player;
    private boolean compact;
    private Timeline modeAnimation;
    private Node firstSideAction;
    private boolean tracksOpen;
    private long lastSavedMs;
    /**
     * Куди перемотати, коли відео почне грати; −1 — нікуди. volatile: читається з потоку VLC.
     */
    private volatile long resumeAtMs = -1;

    public PlayerScreen(Title title, String source, ProgressStore progressStore, Consumer<String> notify) {
        this.title = title;
        this.source = source;
        this.progressStore = progressStore;
        this.notify = notify;

        root.getStyleClass().add("player");
        // У повному режимі фокус тримає сам екран: команди обробляє handle()
        root.setFocusTraversable(true);

        // Відео заповнює свій контейнер, а ширина контейнера залежить від videoShare
        video.setPreserveRatio(true);
        video.fitWidthProperty().bind(videoBox.widthProperty());
        video.fitHeightProperty().bind(videoBox.heightProperty());
        videoBox.getChildren().addAll(video, buildOverlay());
        // Меню доріжок лежить поверх відео, праворуч; на старті сховане
        tracksMenu.getStyleClass().add("player-tracks");
        tracksMenu.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        tracksMenu.setVisible(false);
        StackPane.setAlignment(tracksMenu, Pos.CENTER_RIGHT);
        StackPane.setMargin(tracksMenu, new Insets(0, 60, 0, 0));
        videoBox.getChildren().add(tracksMenu);
        videoBox.setMinSize(0, 0);
        videoBox.prefWidthProperty().bind(root.widthProperty().multiply(videoShare));

        buildSidePanel();

        var body = new HBox(videoBox, side);
        body.setMinSize(0, 0);
        HBox.setHgrow(side, Priority.ALWAYS); // панель забирає решту ширини

        root.getChildren().add(body);
        hideTimer.setOnFinished(e -> fadeOverlay(false));
    }

    /**
     * Зрозуміла назва доріжки: VLC інколи віддає порожній опис.
     */
    private static String trackName(TrackDescription track) {
        if (track.id() < 0) {
            return "Вимкнено";
        }
        String text = track.description();
        return text == null || text.isBlank() ? "Доріжка " + track.id() : text;
    }

    // ---------- Бокова панель (компактний режим) ----------

    static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = total % 3600 / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    private Node buildOverlay() {
        var name = new Label(title.name());
        name.getStyleClass().add("player-title");

        stateIcon.getStyleClass().add("player-state");
        progress.getStyleClass().add("player-progress");
        progress.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(progress, Priority.ALWAYS);
        time.getStyleClass().add("player-time");

        var bar = new HBox(18, stateIcon, progress, time);
        bar.setAlignment(Pos.CENTER_LEFT);

        var help = new Label("✕ Пауза    ← → 10 с    L2 R2 30 с    □ Серії    Options Доріжки    ○ Вийти");
        help.getStyleClass().add("player-help");

        overlay.getChildren().addAll(name, bar, help);
        overlay.getStyleClass().add("player-overlay");
        overlay.setMaxHeight(VBox.USE_PREF_SIZE);
        overlay.setMouseTransparent(true);
        StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
        return overlay;
    }

    private void buildSidePanel() {
        var name = new Label(title.name());
        name.getStyleClass().add("player-side-title");
        name.setWrapText(true);

        var resume = sideButton("▶  Продовжити перегляд", () -> setCompact(false));
        var restart = sideButton("↺  Почати спочатку", this::restart);
        side.getChildren().addAll(name, resume, restart);

        if (title.kind() == Title.Kind.SERIES) {
            var caption = new Label("Наступні серії");
            caption.getStyleClass().add("player-side-caption");
            side.getChildren().add(caption);
            for (int i = 1; i <= 6; i++) {
                int episode = i; // у лямбду можна передати лише незмінну змінну
                side.getChildren().add(sideButton("Серія " + episode, () -> playEpisode(episode)));
            }
        }

        firstSideAction = resume;
        side.getStyleClass().add("player-side");
        // На старті панель схована і не займає місця в розкладці
        side.setOpacity(0);
        side.setVisible(false);
        side.setManaged(false);
    }

    private Button sideButton(String text, Runnable action) {
        var button = new Button(text);
        button.getStyleClass().add("player-episode");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void playEpisode(int episode) {
        resumeAtMs = -1;
        // Поки всі серії — те саме відео. З TMDB/Jellyfin тут буде посилання на конкретну серію.
        var saved = progressStore.position(source);
        if (saved.isPresent()) {
            resumeAtMs = saved.getAsLong(); // перемотаємо в події playing
            notify.accept("Продовжуємо з " + format(resumeAtMs) + "    □ — почати спочатку");
        }
        player.media().play(source);
        notify.accept("Серія " + episode);
        setCompact(false);
    }

    // ---------- Меню аудіодоріжок і субтитрів ----------

    private void restart() {
        resumeAtMs = -1;
        progressStore.clear(source);
        player.media().play(source); // play замість setTime(0): спрацює навіть після кінця відео
        setCompact(false);
    }

    /**
     * Плавне перемикання між повним і компактним режимом.
     */
    private void setCompact(boolean value) {
        if (compact == value) {
            return;
        }
        compact = value;
        if (modeAnimation != null) {
            modeAnimation.stop(); // швидке повторне натискання □ не повинно ламати анімацію
        }
        if (value) {
            side.setManaged(true);
            side.setVisible(true);
        }
        modeAnimation = new Timeline(new KeyFrame(Duration.millis(250),
                new KeyValue(videoShare, value ? COMPACT_VIDEO_SHARE : 1.0, Interpolator.EASE_BOTH),
                new KeyValue(side.opacityProperty(), value ? 1.0 : 0.0, Interpolator.EASE_BOTH)));
        modeAnimation.setOnFinished(e -> {
            if (!compact) {
                side.setVisible(false);
                side.setManaged(false);
            }
        });
        modeAnimation.play();

        // У компактному режимі фокус переходить на кнопки панелі,
        // а сам екран перестає бути кандидатом для навігації стрілками
        root.setFocusTraversable(!value);
        if (value) {
            firstSideAction.requestFocus();
        } else {
            root.requestFocus();
        }
        showOverlay();
    }

    private void openTracks() {
        var audio = trackColumn("Аудіо",
                player.audio().trackDescriptions(),
                player.audio().track(),
                id -> player.audio().setTrack(id));
        var subtitles = trackColumn("Субтитри",
                player.subpictures().trackDescriptions(),
                player.subpictures().track(),
                id -> player.subpictures().setTrack(id));
        tracksMenu.getChildren().setAll(audio, subtitles);
        tracksMenu.setVisible(true);
        tracksOpen = true;

        root.setFocusTraversable(false);
        // Фокус — на поточну аудіодоріжку, а якщо доріжок немає — на першу кнопку меню
        tracksMenu.lookupAll(".player-track").stream()
                .filter(n -> n.getStyleClass().contains("active"))
                .findFirst()
                .or(() -> tracksMenu.lookupAll(".player-track").stream().findFirst())
                .ifPresent(Node::requestFocus);
        showOverlay();
    }

    private void closeTracks() {
        tracksMenu.setVisible(false);
        tracksMenu.getChildren().clear();
        tracksOpen = false;
        root.setFocusTraversable(true);
        root.requestFocus();
        showOverlay();
    }

    // ---------- Життєвий цикл ----------

    /**
     * Колонка меню: заголовок і кнопка на кожну доріжку.
     * {@code select} — що зробити з id вибраної доріжки (аудіо чи субтитри).
     */
    private VBox trackColumn(String caption, List<TrackDescription> tracks, int current, IntConsumer select) {
        var column = new VBox(10);
        var header = new Label(caption);
        header.getStyleClass().add("player-tracks-caption");
        column.getChildren().add(header);

        if (tracks.isEmpty()) {
            var none = new Label("Немає");
            none.getStyleClass().add("player-tracks-empty");
            column.getChildren().add(none);
        }
        for (var track : tracks) {
            boolean active = track.id() == current;
            var button = new Button((active ? "✓  " : "     ") + trackName(track));
            button.getStyleClass().add("player-track");
            if (active) {
                button.getStyleClass().add("active");
            }
            button.setMaxWidth(Double.MAX_VALUE);
            button.setOnAction(e -> {
                select.accept(track.id());
                closeTracks();
                notify.accept(caption + ": " + trackName(track));
            });
            column.getChildren().add(button);
        }
        return column;
    }

    @Override
    public void onShown() {
        try {
            factory = new MediaPlayerFactory(VLC_OPTIONS);
        } catch (RuntimeException | LinkageError e) {
            // Найчастіша причина — не встановлено VLC або встановлено 32-бітну версію
            notify.accept("Не знайдено VLC 3.x (64-bit). Встановіть його з videolan.org");
            return;
        }
        // 1. Спершу створюємо плеєр і підключаємо до нього ImageView та події
        player = factory.mediaPlayers().newEmbeddedMediaPlayer();
        player.videoSurface().set(new ImageViewVideoSurface(video));
        player.events().addMediaPlayerEventListener(new PlayerEvents());

        // 2. Потім вирішуємо, звідки почати
        var saved = progressStore.position(source);
        if (saved.isPresent()) {
            resumeAtMs = saved.getAsLong(); // перемотаємо в timeChanged, щойно файл стане seekable
            notify.accept("Продовжуємо з " + format(resumeAtMs) + "    □ — почати спочатку");
        }

        // 3. І лише тепер запускаємо відтворення — рівно один раз
        player.media().play(source);
        showOverlay();
    }

    @Override
    public void onHidden() {
        hideTimer.stop();
        if (modeAnimation != null) {
            modeAnimation.stop();
        }
        if (player != null) {
            progressStore.save(source, player.status().time(), player.status().length());
            player.controls().stop();
            player.release();
            factory.release();
            player = null;
        }
    }

    // ---------- Керування ----------

    @Override
    public boolean handle(Command command) {
        showOverlay();
        if (player == null) {
            return false; // VLC не запустився — дозволяємо лише вийти
        }
        if (tracksOpen) {
            if (command == Command.BACK || command == Command.MENU) {
                closeTracks();
                return true;
            }
            // Стрілки й ✕ — навігація по меню доріжок, її робить ScreenManager
            return false;
        }
        if (command == Command.MENU && !compact) {
            openTracks();
            return true;
        }
        if (command == Command.FULLSCREEN) {
            setCompact(!compact);
            return true;
        }
        if (compact) {
            if (command == Command.BACK) {
                setCompact(false); // ○ у компактному режимі — назад до відео, а не вихід
                return true;
            }
            // Стрілки й ✕ віддаємо ScreenManager: він сам рухає фокус по кнопках панелі
            return false;
        }
        switch (command) {
            case ACCEPT -> player.controls().setPause(player.status().isPlaying());
            case LEFT -> seek(-SHORT_SEEK_MS);
            case RIGHT -> seek(SHORT_SEEK_MS);
            case PAGE_LEFT -> seek(-LONG_SEEK_MS);
            case PAGE_RIGHT -> seek(LONG_SEEK_MS);
            case BACK -> {
                return false; // ScreenManager закриє екран і викличе onHidden()
            }
            default -> {
            } // UP, DOWN, MENU — просто показати панель
        }
        return true;
    }

    /**
     * Перемотка на {@code deltaMs} мілісекунд (від'ємне значення — назад).
     * Час на панелі оновлюємо одразу: на паузі VLC не надсилає подію timeChanged.
     */
    private void seek(long deltaMs) {
        long length = player.status().length();
        long target = Math.clamp(player.status().time() + deltaMs, 0, Math.max(0, length - 1000));
        player.controls().setTime(target);
        updateTime(target, length);
    }

    // ---------- Панель керування поверх відео ----------

    private void showOverlay() {
        fadeOverlay(true);
        hideTimer.playFromStart();
    }

    private void fadeOverlay(boolean visible) {
        // Не ховаємо панель на паузі та в компактному режимі
        if (!visible && (compact || tracksOpen || (player != null && !player.status().isPlaying()))) {
            return;
        }
        var fade = new FadeTransition(Duration.millis(visible ? 120 : 400), overlay);
        fade.setToValue(visible ? 1 : 0);
        fade.play();
    }

    private void updateState(boolean playing) {
        stateIcon.setGraphic(Icons.of(playing ? Icons.PAUSE : Icons.PLAY));
        if (!playing) {
            showOverlay();
        }
    }

    private void updateTime(long timeMs, long lengthMs) {
        progress.setProgress(lengthMs > 0 ? (double) timeMs / lengthMs : 0);
        time.setText(format(timeMs) + " / " + format(lengthMs));
    }

    /**
     * Зберігаємо не на кожен кадр, а раз на 10 секунд перегляду — так диск не смикається постійно.
     */
    private void autosave(long timeMs, long lengthMs) {
        if (Math.abs(timeMs - lastSavedMs) >= AUTOSAVE_INTERVAL_MS) {
            progressStore.save(source, timeMs, lengthMs);
            lastSavedMs = timeMs;
        }
    }

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return root;
    }

    /**
     * Події libVLC приходять з її власного потоку. Змінювати JavaFX-елементи
     * можна лише з FX-потоку, тому все загортаємо в {@link Platform#runLater}.
     */
    private final class PlayerEvents extends MediaPlayerEventAdapter {
        @Override
        public void timeChanged(MediaPlayer mp, long newTime) {
            long target = resumeAtMs;
            if (target > 0) {
                // Чекаємо, доки VLC дозволить перемотку; до того setTime() тихо ігнорується
                if (mp.status().isSeekable()) {
                    resumeAtMs = -1;
                    // Методи плеєра не можна викликати прямо з потоку подій VLC — submit виконає це окремо
                    mp.submit(() -> mp.controls().setTime(target));
                }
                // Поки перемотка не відбулася — не оновлюємо панель і не зберігаємо «0:01» поверх справжньої позиції
                return;
            }
            long length = mp.status().length();
            Platform.runLater(() -> {
                updateTime(newTime, length);
                autosave(newTime, length);
            });
        }

        @Override
        public void playing(MediaPlayer mp) {
            Platform.runLater(() -> updateState(true));
        }

        @Override
        public void paused(MediaPlayer mp) {
            Platform.runLater(() -> updateState(false));
        }

        @Override
        public void finished(MediaPlayer mp) {
            Platform.runLater(() -> {
                updateState(false);
                progressStore.clear(source);
                notify.accept("Перегляд завершено");
            });
        }

        @Override
        public void error(MediaPlayer mp) {
            Platform.runLater(() -> notify.accept("Не вдалося відтворити: " + source));
        }
    }
}