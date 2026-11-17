package ua.kinobox.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
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
 * повний (відео на весь екран) і компактний (відео зменшене, праворуч — панель дій).
 *
 * <pre>
 * Повний режим:
 *   ✕ / Enter          пауза / продовжити
 *   ← / →              −10 / +10 секунд
 *   L1,L2 / R1,R2      −30 / +30 секунд
 *   □ / F / F11        компактний режим
 *   Options / M        аудіодоріжки й субтитри
 *   ○ / Esc            закрити плеєр
 * Компактний режим:
 *   стрілки, ✕         навігація по панелі та вибір
 *   □ або ○            назад до повного відео
 * </pre>
 */
public final class PlayerScreen implements Screen {

    private static final long SHORT_SEEK_MS = 10_000;
    private static final long LONG_SEEK_MS = 30_000;
    /** Як часто зберігати позицію під час перегляду. */
    private static final long AUTOSAVE_INTERVAL_MS = 10_000;
    /** Яку частку ширини екрана займає відео в компактному режимі. */
    private static final double COMPACT_VIDEO_SHARE = 0.62;
    /** Скільки секунд чекати перед автозапуском наступної серії. */
    private static final int NEXT_COUNTDOWN_SECONDS = 10;
    /** Опції libVLC. --quiet вимикає технічні повідомлення VLC у консолі. */
    private static final String[] VLC_OPTIONS = {"--quiet"};

    private final Title title;
    /** Адреса відео. Може містити API-ключ, тому її ніде не показуємо і не пишемо в лог. */
    private final String source;
    /** Під яким ключем зберігати позицію (id тайтлу або серії) — не адреса відео. */
    private final String progressKey;
    private final ProgressStore progressStore;
    private final Consumer<String> notify;
    /** Наступна серія або null (фільм, остання серія). */
    private final Next next;

    private final StackPane root = new StackPane();
    private final StackPane videoBox = new StackPane();
    private final ImageView video = new ImageView();
    private final VBox overlay = new VBox(14);
    private final VBox side = new VBox(16);
    private final HBox tracksMenu = new HBox(48);
    private final VBox nextPanel = new VBox(6);
    private final Label stateIcon = new Label();
    private final ProgressBar progress = new ProgressBar(0);
    private final Label time = new Label("0:00 / 0:00");
    private final Label nextCountdown = new Label();
    private final PauseTransition hideTimer = new PauseTransition(Duration.seconds(3));
    /** Частка ширини під відео: 1.0 — повний режим, COMPACT_VIDEO_SHARE — компактний. */
    private final DoubleProperty videoShare = new SimpleDoubleProperty(1.0);

    private MediaPlayerFactory factory;
    private EmbeddedMediaPlayer player;
    private boolean compact;
    private boolean tracksOpen;
    private boolean nextShown;
    private Timeline modeAnimation;
    private Timeline nextTimer;
    private int nextSecondsLeft;
    private Node firstSideAction;
    private long lastSavedMs;
    /** Куди перемотати, коли відео почне грати; −1 — нікуди. volatile: читається з потоку VLC. */
    private volatile long resumeAtMs = -1;

    public PlayerScreen(Title title, String source, String progressKey, ProgressStore progressStore,
                        Consumer<String> notify, Next next) {
        this.title = title;
        this.source = source;
        this.progressKey = progressKey;
        this.progressStore = progressStore;
        this.notify = notify;
        this.next = next;

        root.getStyleClass().add("player");
        // У повному режимі фокус тримає сам екран: команди обробляє handle()
        root.setFocusTraversable(true);

        // Відео заповнює свій контейнер, а ширина контейнера залежить від videoShare
        video.setPreserveRatio(true);
        video.fitWidthProperty().bind(videoBox.widthProperty());
        video.fitHeightProperty().bind(videoBox.heightProperty());
        videoBox.getChildren().addAll(video, buildOverlay(), buildTracksMenu());
        if (next != null) {
            videoBox.getChildren().add(buildNextPanel());
        }
        videoBox.setMinSize(0, 0);
        videoBox.prefWidthProperty().bind(root.widthProperty().multiply(videoShare));

        buildSidePanel();

        var body = new HBox(videoBox, side);
        body.setMinSize(0, 0);
        HBox.setHgrow(side, Priority.ALWAYS); // панель забирає решту ширини

        root.getChildren().add(body);
        hideTimer.setOnFinished(e -> fadeOverlay(false));
    }

    /** «1:02:03» або «2:03». */
    static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = total % 3600 / 60;
        long s = total % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ---------- Screen ----------

    @Override
    public Parent view() {
        return root;
    }

    @Override
    public Node initialFocus() {
        return root;
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
        var saved = progressStore.position(progressKey);
        if (saved.isPresent()) {
            resumeAtMs = saved.getAsLong(); // перемотаємо в timeChanged, щойно файл стане seekable
            notify.accept("Продовжуємо з " + format(resumeAtMs) + "     {S} — почати спочатку");
        }

        // 3. І лише тепер запускаємо відтворення — рівно один раз
        player.media().play(source);
        showOverlay();
    }

    @Override
    public void onHidden() {
        if (nextTimer != null) {
            nextTimer.stop(); // плеєр закрили посеред відліку — наступна серія не повинна ввімкнутися сама
        }
        hideTimer.stop();
        if (modeAnimation != null) {
            modeAnimation.stop();
        }
        if (player != null) {
            // Перемотка на збережену позицію ще не відбулась — поточний час ≈ 0, ним не можна перезаписувати прогрес
            if (resumeAtMs < 0) {
                progressStore.save(progressKey, player.status().time(), player.status().length());
            }
            player.controls().stop();
            player.release();
            factory.release();
            player = null;
        }
    }

    @Override
    public boolean handle(Command command) {
        showOverlay();
        if (player == null) {
            return false; // VLC не запустився — дозволяємо лише вийти
        }
        if (nextShown) {
            switch (command) {
                case ACCEPT -> playNext();      // ✕ — дивитися зараз
                case BACK -> hideNextPanel();   // ○ — скасувати; наступне ○ закриє плеєр
                default -> { }                  // решту ігноруємо, поки йде відлік
            }
            return true;
        }
        if (tracksOpen) {
            if (command == Command.BACK || command == Command.MENU) {
                closeTracks();
                return true;
            }
            return false; // стрілки й ✕ — навігація по меню доріжок, її робить ScreenManager
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
            return false; // стрілки й ✕ віддаємо ScreenManager: він рухає фокус по кнопках панелі
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
            default -> { }    // UP, DOWN, SECONDARY — просто показати панель
        }
        return true;
    }

    // ---------- Побудова інтерфейсу ----------

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

        var help = Glyphs.hint("{X} Пауза     ← → 10 с     {L2} {R2} 30 с     {S} Серії     {OPT} Доріжки     {O} Вийти",
                "player-help");

        overlay.getChildren().addAll(name, bar, help);
        overlay.getStyleClass().add("player-overlay");
        overlay.setMaxHeight(VBox.USE_PREF_SIZE);
        overlay.setMouseTransparent(true);
        StackPane.setAlignment(overlay, Pos.BOTTOM_CENTER);
        return overlay;
    }

    /** Меню доріжок лежить поверх відео, праворуч; на старті сховане. */
    private Node buildTracksMenu() {
        tracksMenu.getStyleClass().add("player-tracks");
        tracksMenu.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        tracksMenu.setVisible(false);
        StackPane.setAlignment(tracksMenu, Pos.CENTER_RIGHT);
        StackPane.setMargin(tracksMenu, new Insets(0, 60, 0, 0));
        return tracksMenu;
    }

    /** Картка «Наступна серія» з відліком — над панеллю керування, праворуч. */
    private Node buildNextPanel() {
        var caption = new Label("Наступна серія");
        caption.getStyleClass().add("player-next-caption");
        var name = new Label(next.label());
        name.getStyleClass().add("player-next-title");
        nextCountdown.getStyleClass().add("player-next-count");
        var help = Glyphs.hint("{X} Дивитися зараз     {O} Скасувати", "player-help");

        nextPanel.getChildren().addAll(caption, name, nextCountdown, help);
        nextPanel.getStyleClass().add("player-next");
        nextPanel.setMaxSize(VBox.USE_PREF_SIZE, VBox.USE_PREF_SIZE);
        nextPanel.setVisible(false);
        StackPane.setAlignment(nextPanel, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(nextPanel, new Insets(0, 60, 170, 0));
        return nextPanel;
    }

    /** Бокова панель компактного режиму. На старті схована і не займає місця в розкладці. */
    private void buildSidePanel() {
        var name = new Label(title.name());
        name.getStyleClass().add("player-side-title");
        name.setWrapText(true);

        var resume = sideButton("▶  Продовжити перегляд", () -> setCompact(false));
        var restart = sideButton("↺  Почати спочатку", this::restart);
        side.getChildren().addAll(name, resume, restart);

        firstSideAction = resume;
        side.getStyleClass().add("player-side");
        side.setOpacity(0);
        side.setVisible(false);
        side.setManaged(false);
    }

    private static Button sideButton(String text, Runnable action) {
        var button = new Button(text);
        button.getStyleClass().add("player-episode");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> action.run());
        return button;
    }

    // ---------- Компактний режим ----------

    /** Плавне перемикання між повним і компактним режимом. */
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

    private void restart() {
        resumeAtMs = -1;
        progressStore.clear(progressKey);
        player.media().play(source); // play замість setTime(0): спрацює навіть після кінця відео
        setCompact(false);
    }

    // ---------- Меню аудіодоріжок і субтитрів ----------

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
        var buttons = tracksMenu.lookupAll(".player-track");
        buttons.stream()
                .filter(n -> n.getStyleClass().contains("active"))
                .findFirst()
                .or(() -> buttons.stream().findFirst())
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

    /** Зрозуміла назва доріжки: VLC інколи віддає порожній опис. */
    private static String trackName(TrackDescription track) {
        if (track.id() < 0) {
            return "Вимкнено";
        }
        String text = track.description();
        return text == null || text.isBlank() ? "Доріжка " + track.id() : text;
    }

    // ---------- Наступна серія ----------

    /** Серія закінчилась: показуємо картку і запускаємо відлік. */
    private void showNextPanel() {
        nextShown = true;
        nextPanel.setVisible(true);
        nextSecondsLeft = NEXT_COUNTDOWN_SECONDS;
        nextCountdown.setText("через " + nextSecondsLeft + " с");
        // Кадр щосекунди, рівно NEXT_COUNTDOWN_SECONDS разів
        nextTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            nextSecondsLeft--;
            if (nextSecondsLeft <= 0) {
                playNext();
            } else {
                nextCountdown.setText("через " + nextSecondsLeft + " с");
            }
        }));
        nextTimer.setCycleCount(NEXT_COUNTDOWN_SECONDS);
        nextTimer.play();
    }

    private void hideNextPanel() {
        nextShown = false;
        nextPanel.setVisible(false);
        if (nextTimer != null) {
            nextTimer.stop();
        }
    }

    private void playNext() {
        hideNextPanel();
        next.play().run(); // застосунок закриє цей плеєр і відкриє наступну серію
    }

    // ---------- Перемотка і панель керування ----------

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

    private void showOverlay() {
        fadeOverlay(true);
        hideTimer.playFromStart();
    }

    private void fadeOverlay(boolean visible) {
        // Не ховаємо панель на паузі, у компактному режимі і поки відкрите меню доріжок
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

    /** Зберігаємо не на кожен кадр, а раз на 10 секунд перегляду — так диск не смикається постійно. */
    private void autosave(long timeMs, long lengthMs) {
        if (Math.abs(timeMs - lastSavedMs) >= AUTOSAVE_INTERVAL_MS) {
            progressStore.save(progressKey, timeMs, lengthMs);
            lastSavedMs = timeMs;
        }
    }

    /** Що вмикати після цієї серії: підпис для картки і дія. */
    public record Next(String label, Runnable play) {
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
                progressStore.clear(progressKey);
                if (next != null) {
                    showNextPanel();
                } else {
                    notify.accept("Перегляд завершено");
                }
            });
        }

        @Override
        public void error(MediaPlayer mp) {
            // Лише назва: адреса відео може містити API-ключ Jellyfin
            Platform.runLater(() -> notify.accept("Не вдалося відтворити «" + title.name() + "»"));
        }
    }
}
