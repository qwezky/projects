package dev.example.dshclient.ui;

import java.awt.Color;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.UIManager;

/** 管理主题模式、过渡帧和变更通知，不直接接触用户配置文件。 */
public final class ThemeManager {
    public interface Persistence {
        Consumer<String> persist();

        String load();
    }

    private static final ThemeManager INSTANCE = new ThemeManager();

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ThemeTransition transition = new ThemeTransition();
    private volatile String mode = "system";
    private volatile boolean darkEffective = detectSystemDark();
    private volatile UiTheme displayTheme = darkEffective ? UiTheme.dark() : UiTheme.light();
    private volatile Persistence persistence;

    private ThemeManager() {
    }

    public static ThemeManager get() {
        return INSTANCE;
    }

    /** 过渡期间返回当前帧，而不是直接跳到目标主题。 */
    public UiTheme theme() {
        return displayTheme;
    }

    public String mode() {
        return mode;
    }

    public boolean isDarkEffective() {
        return darkEffective;
    }

    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public synchronized void setMode(String requestedMode) {
        String nextMode = normalize(requestedMode);
        boolean nextDark = "dark".equals(nextMode)
                || ("system".equals(nextMode) && detectSystemDark());
        boolean changed = !nextMode.equals(mode) || nextDark != darkEffective;
        boolean colorsChanged = nextDark != darkEffective;

        mode = nextMode;
        darkEffective = nextDark;

        Persistence currentPersistence = persistence;
        try {
            if (currentPersistence != null) {
                currentPersistence.persist().accept(nextMode);
            }
        } finally {
            if (changed) {
                if (colorsChanged) {
                    playTransition(nextDark);
                } else {
                    fireListeners();
                }
            }
        }
    }

    public synchronized void setPersistence(Persistence persistence) {
        this.persistence = Objects.requireNonNull(persistence, "persistence");
        String savedMode = persistence.load();
        if (savedMode != null) {
            try {
                setMode(savedMode);
            } catch (IllegalArgumentException ignored) {
                // 配置可能来自旧版本，保留当前有效模式而不是阻止启动。
            }
        }
    }

    public synchronized void refreshSystemTheme() {
        if (!"system".equals(mode)) {
            return;
        }
        boolean nextDark = detectSystemDark();
        if (nextDark != darkEffective) {
            darkEffective = nextDark;
            playTransition(nextDark);
        }
    }

    private void playTransition(boolean targetDark) {
        UiTheme target = targetDark ? UiTheme.dark() : UiTheme.light();
        transition.play(displayTheme, target, frame -> {
            displayTheme = frame;
            fireListeners();
        });
    }

    private void fireListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    private static String normalize(String value) {
        String normalized = Objects.requireNonNull(value, "mode")
                .trim().toLowerCase(Locale.ROOT);
        if (!"system".equals(normalized) && !"light".equals(normalized)
                && !"dark".equals(normalized)) {
            throw new IllegalArgumentException("主题模式仅支持 system、light、dark");
        }
        return normalized;
    }

    private static boolean detectSystemDark() {
        Color background = UIManager.getColor("control");
        if (background == null) {
            background = UIManager.getColor("Panel.background");
        }
        if (background == null) {
            return false;
        }
        double luminance = (0.2126 * background.getRed()
                + 0.7152 * background.getGreen()
                + 0.0722 * background.getBlue()) / 255.0;
        return luminance < 0.45;
    }
}
