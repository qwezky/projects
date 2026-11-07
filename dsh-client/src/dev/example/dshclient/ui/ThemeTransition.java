package dev.example.dshclient.ui;

import java.awt.Frame;
import java.awt.Window;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** 220ms 线性主题过渡；再次播放会接住当前帧并打断旧动画。 */
public final class ThemeTransition {
    private static final int DURATION_MS = 220;
    private static final int FRAME_MS = 16;

    private Timer timer;
    private long startedAt;
    private UiTheme from;
    private UiTheme to;
    private Consumer<UiTheme> onFrame;

    public void play(UiTheme from, UiTheme to, Consumer<UiTheme> onFrame) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(onFrame, "onFrame");
        if (SwingUtilities.isEventDispatchThread()) {
            start(from, to, onFrame);
        } else {
            SwingUtilities.invokeLater(() -> start(from, to, onFrame));
        }
    }

    public boolean isRunning() {
        return timer != null && timer.isRunning();
    }

    public void stop() {
        if (timer != null) {
            timer.stop();
        }
    }

    private void start(UiTheme from, UiTheme to, Consumer<UiTheme> onFrame) {
        stop();
        this.from = from;
        this.to = to;
        this.onFrame = onFrame;
        if (from == to || !animationAllowed()) {
            onFrame.accept(to);
            return;
        }
        startedAt = System.nanoTime();
        timer = new Timer(FRAME_MS, event -> tick());
        onFrame.accept(from);
        timer.start();
    }

    private void tick() {
        if (!animationAllowed()) {
            finish();
            return;
        }
        float progress = Math.min(1f,
                (System.nanoTime() - startedAt) / (DURATION_MS * 1_000_000f));
        if (progress >= 1f) {
            finish();
        } else {
            onFrame.accept(UiTheme.interpolate(from, to, progress));
        }
    }

    private void finish() {
        stop();
        Consumer<UiTheme> callback = onFrame;
        UiTheme target = to;
        if (callback != null && target != null) {
            callback.accept(target);
        }
    }

    private static boolean animationAllowed() {
        boolean anyShowing = false;
        for (Window window : Window.getWindows()) {
            if (!window.isShowing()) {
                continue;
            }
            anyShowing = true;
            boolean iconified = window instanceof Frame
                    && (((Frame) window).getExtendedState() & Frame.ICONIFIED) != 0;
            if (window.isActive() && !iconified) {
                return true;
            }
        }
        // 无窗口时也允许完成回调，便于独立测试和提前接线。
        return !anyShowing;
    }
}
