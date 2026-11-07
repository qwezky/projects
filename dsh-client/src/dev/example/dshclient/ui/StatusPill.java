package dev.example.dshclient.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** 状态文字与呼吸圆点并存；计时器只重绘圆点区域。 */
public final class StatusPill extends JComponent {
    public enum State { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 24;
    private static final int DOT_SIZE = 6;
    private static final int PERIOD_MS = 1600;
    private static final Rectangle DOT_AREA = new Rectangle(4, 2, 24, 20);

    private final transient ThemeManager tm;
    private final transient Timer pulseTimer;
    private transient Window window;
    private State state;
    private String text;
    private long pulseStartedAt;

    public StatusPill(ThemeManager tm) {
        this(State.DISCONNECTED, "已断开", tm);
    }

    public StatusPill(State state, String text, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.state = Objects.requireNonNull(state, "state");
        this.text = Objects.requireNonNull(text, "text");
        setOpaque(false);
        setFont(UiFonts.ui(Font.PLAIN, UiFonts.NOTE_SIZE));
        pulseTimer = new Timer(33, event -> {
            if (!canAnimate()) {
                updateAnimation();
                return;
            }
            repaint(DOT_AREA);
        });
        installLifecycleListeners();
        tm.addListener(this::repaint);
    }

    public void setState(State state, String text) {
        this.state = Objects.requireNonNull(state, "state");
        this.text = Objects.requireNonNull(text, "text");
        revalidate();
        repaint();
        updateAnimation();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(theme.panelBg);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setColor(theme.separator);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);

            Color stateColor = stateColor(theme);
            float haloAlpha = haloAlpha();
            g.setColor(withAlpha(stateColor, Math.round(haloAlpha * 255)));
            g.fillOval(8, (getHeight() - 14) / 2, 14, 14);
            g.setColor(stateColor);
            g.fillOval(12, (getHeight() - DOT_SIZE) / 2, DOT_SIZE, DOT_SIZE);

            g.setColor(theme.textPrimary);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(text, 28, baseline);
        } finally {
            g.dispose();
        }
    }

    private float haloAlpha() {
        if (!pulseTimer.isRunning()) {
            return 0.15f;
        }
        long elapsedMs = (System.nanoTime() - pulseStartedAt) / 1_000_000L;
        double phase = 2.0 * Math.PI * (elapsedMs % PERIOD_MS) / PERIOD_MS - Math.PI / 2.0;
        return (float) (0.30 + 0.15 * Math.sin(phase));
    }

    private Color stateColor(UiTheme theme) {
        switch (state) {
            case CONNECTING:
                return theme.warning;
            case CONNECTED:
                return theme.success;
            case ERROR:
                return theme.danger;
            case DISCONNECTED:
            default:
                return theme.textSecondary;
        }
    }

    private void installLifecycleListeners() {
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                updateAnimation();
            }

            @Override
            public void componentHidden(ComponentEvent e) {
                pulseTimer.stop();
            }
        });
        addHierarchyListener(event -> {
            long flags = HierarchyEvent.PARENT_CHANGED | HierarchyEvent.SHOWING_CHANGED
                    | HierarchyEvent.DISPLAYABILITY_CHANGED;
            if ((event.getChangeFlags() & flags) != 0) {
                syncWindow();
            }
        });
    }

    private void syncWindow() {
        Window next = SwingUtilities.getWindowAncestor(this);
        if (next == window) {
            updateAnimation();
            return;
        }
        if (window != null) {
            window.removeWindowFocusListener(windowEvents);
            window.removeWindowStateListener(windowEvents);
        }
        window = next;
        if (window != null) {
            window.addWindowFocusListener(windowEvents);
            window.addWindowStateListener(windowEvents);
        }
        updateAnimation();
    }

    private final transient WindowAdapter windowEvents = new WindowAdapter() {
        @Override
        public void windowGainedFocus(WindowEvent e) {
            updateAnimation();
        }

        @Override
        public void windowLostFocus(WindowEvent e) {
            pulseTimer.stop();
        }

        @Override
        public void windowStateChanged(WindowEvent e) {
            updateAnimation();
        }
    };

    private boolean canAnimate() {
        boolean iconified = window instanceof Frame
                && (((Frame) window).getExtendedState() & Frame.ICONIFIED) != 0;
        return isShowing() && window != null && window.isActive() && !iconified;
    }

    private void updateAnimation() {
        if (canAnimate()) {
            if (!pulseTimer.isRunning()) {
                pulseStartedAt = System.nanoTime();
                pulseTimer.start();
            }
        } else {
            pulseTimer.stop();
        }
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        return new Dimension(Math.max(68, metrics.stringWidth(text) + 40), HEIGHT);
    }
}
