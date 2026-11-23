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
    /**
     * 系统亮暗来源：由 ClientFrame 注入（Windows 上读注册表 AppsUseLightTheme）。
     * 之前这里靠 Swing 默认控件底色猜——Windows 上永远是浅色，等于"跟随系统"从来没生效过。
     */
    private volatile java.util.function.BooleanSupplier systemDarkSource = ThemeManager::lafDarkGuess;
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

    /**
     * 注入系统亮暗探测（返回 true = 系统当前是深色）。传 null 恢复默认猜测。
     * @param source 探测函数，每次调用都应反映**当前**系统状态
     */
    public void setSystemDarkSource(java.util.function.BooleanSupplier source) {
        this.systemDarkSource = source == null ? ThemeManager::lafDarkGuess : source;
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

    /**
     * 重新问一次系统（探测本身可能要起进程，尽量别在 EDT 上调）。
     */
    public synchronized void refreshSystemTheme() {
        applySystemDark(detectSystemDark());
    }

    /**
     * 用"已经探好的"系统亮暗更新当前主题；调用方可以把探测放到后台线程。
     * @param systemIsDark 系统当前是否深色
     */
    public synchronized void applySystemDark(boolean systemIsDark) {
        if (!"system".equals(mode) || systemIsDark == darkEffective) {
            return;
        }
        darkEffective = systemIsDark;
        playTransition(systemIsDark);
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

    private boolean detectSystemDark() {
        try {
            return systemDarkSource.getAsBoolean();
        } catch (RuntimeException failure) {
            return lafDarkGuess();
        }
    }

    /** 兜底：按 Swing 默认控件底色的亮度猜。Windows 的正常路径走注入的注册表探测。 */
    private static boolean lafDarkGuess() {
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
