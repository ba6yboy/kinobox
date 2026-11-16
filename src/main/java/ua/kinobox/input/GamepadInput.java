package ua.kinobox.input;

import com.studiohartman.jamepad.ControllerManager;
import com.studiohartman.jamepad.ControllerState;
import javafx.animation.AnimationTimer;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DualSense (і будь-який інший геймпад, відомий SDL) через Jamepad.
 *
 * <p>Опитування відбувається в {@link AnimationTimer}, тобто у FX-потоці з частотою кадрів,
 * тому жодної синхронізації не потрібно. Підтримується гаряче підключення.
 *
 * <p>Розкладка (позиції за SDL, підписи DualSense):
 * <pre>
 *   D-pad / лівий стік  → UP/DOWN/LEFT/RIGHT (з автоповтором)
 *   ✕ (a)               → ACCEPT
 *   ○ (b)               → BACK
 *   □ (x)               → FULLSCREEN
 *   Options (start)     → MENU
 *   L1 / R1             → PAGE_LEFT / PAGE_RIGHT
 * </pre>
 */
public final class GamepadInput {

    private static final Logger LOG = Logger.getLogger(GamepadInput.class.getName());

    private static final float STICK_DEADZONE = 0.55f;
    /**
     * Тригери L2/R2 аналогові (0..1): вважаємо натиснутим понад половину ходу.
     */
    private static final float TRIGGER_THRESHOLD = 0.5f;
    private static final long REPEAT_DELAY_NS = 380_000_000L;
    private static final long REPEAT_INTERVAL_NS = 95_000_000L;

    private final InputRouter router;
    private final Consumer<String> onConnectionChanged;
    private final Map<Command, Long> nextRepeatAt = new EnumMap<>(Command.class);

    private ControllerManager manager;
    private AnimationTimer timer;
    private boolean connected;

    /**
     * @param onConnectionChanged отримує назву контролера при підключенні або {@code null} при відключенні
     */
    public GamepadInput(InputRouter router, Consumer<String> onConnectionChanged) {
        this.router = router;
        this.onConnectionChanged = onConnectionChanged;
    }

    /**
     * @return {@code false}, якщо SDL не вдалося ініціалізувати (додаток працюватиме з клавіатурою)
     */
    public boolean start() {
        try {
            manager = new ControllerManager();
            manager.initSDLGamepad();
        } catch (Throwable t) {
            LOG.log(Level.WARNING, "Геймпад недоступний, лишається тільки клавіатура", t);
            manager = null;
            return false;
        }
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                poll(now);
            }
        };
        timer.start();
        return true;
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
        }
        if (manager != null) {
            manager.quitSDLGamepad();
        }
    }

    /**
     * Коротка вібрація — тактильний відгук на дію. Тихо ігнорується, якщо не підтримується.
     */
    public void pulse(float strength, int millis) {
        if (manager != null && connected) {
            try {
                manager.doVibration(0, strength, strength, millis);
            } catch (RuntimeException ignored) {
                // контролер міг відключитися між кадрами
            }
        }
    }

    private void poll(long now) {
        ControllerState s = manager.getState(0);

        if (s.isConnected != connected) {
            connected = s.isConnected;
            nextRepeatAt.clear();
            onConnectionChanged.accept(connected ? s.controllerType : null);
        }
        if (!connected) {
            return;
        }

        // Одноразові кнопки
        if (s.aJustPressed) router.dispatch(Command.ACCEPT);
        if (s.bJustPressed) router.dispatch(Command.BACK);
        if (s.xJustPressed) router.dispatch(Command.FULLSCREEN);
        if (s.startJustPressed) router.dispatch(Command.MENU);
        if (s.yJustPressed) router.dispatch(Command.SECONDARY); // △ у SDL має назву Y

        // Кнопки з автоповтором. Вісь Y у SDL: додатна — вниз.
        held(now, Command.UP, s.dpadUp || s.leftStickY < -STICK_DEADZONE);
        held(now, Command.DOWN, s.dpadDown || s.leftStickY > STICK_DEADZONE);
        held(now, Command.LEFT, s.dpadLeft || s.leftStickX < -STICK_DEADZONE);
        held(now, Command.RIGHT, s.dpadRight || s.leftStickX > STICK_DEADZONE);
        held(now, Command.PAGE_LEFT, s.lb || s.leftTrigger > TRIGGER_THRESHOLD);
        held(now, Command.PAGE_RIGHT, s.rb || s.rightTrigger > TRIGGER_THRESHOLD);
    }

    private void held(long now, Command cmd, boolean pressed) {
        Long next = nextRepeatAt.get(cmd);
        if (!pressed) {
            nextRepeatAt.remove(cmd);
        } else if (next == null) {
            router.dispatch(cmd);
            nextRepeatAt.put(cmd, now + REPEAT_DELAY_NS);
        } else if (now >= next) {
            router.dispatch(cmd);
            nextRepeatAt.put(cmd, now + REPEAT_INTERVAL_NS);
        }
    }
}
