package dev.example.dshclient.ui;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/** 无系统菜单外观的非聚焦弹层；背景、描边和文字都由当前主题驱动。 */
public class PopupSurface extends JWindow {
    private static final long serialVersionUID = 1L;
    private static final int MAX_WIDTH = 420;
    private static final int ARC = 12;

    protected final transient ThemeManager tm;

    private final SurfacePanel surface = new SurfacePanel();
    private final JPanel content = new JPanel(new BorderLayout());
    private final transient AWTEventListener outsideClickListener = this::handleOutsideClick;
    private final transient KeyEventDispatcher escapeDispatcher = this::handleEscape;
    private final transient WindowAdapter ownerFocusListener = new WindowAdapter() {
        @Override
        public void windowLostFocus(WindowEvent e) {
            hidePopup();
        }
    };

    private transient Window anchorWindow;
    private boolean globalListenersInstalled;

    @SuppressWarnings("this-escape")
    protected PopupSurface(ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        setFocusableWindowState(false);
        setFocusable(false);
        setAlwaysOnTop(true);

        surface.setOpaque(false);
        surface.setLayout(new BorderLayout());
        surface.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        content.setOpaque(false);
        surface.add(content, BorderLayout.CENTER);
        setContentPane(surface);
        updateSurfaceColors();
        tm.addListener(this::applyTheme);
    }

    /** 在 anchor 的屏幕坐标系中偏移 (x, y) 显示，不夺取主窗口焦点。 */
    public final void showAt(Component anchor, int x, int y) {
        Objects.requireNonNull(anchor, "anchor");
        applyTheme();
        pack();
        Dimension size = getSize();
        if (size.width > MAX_WIDTH) {
            size.width = MAX_WIDTH;
            setSize(size);
        }

        Point location = anchor.getLocationOnScreen();
        location.translate(x, y);
        clampToScreen(anchor, location);
        setLocation(location);
        attachAnchorWindow(SwingUtilities.getWindowAncestor(anchor));
        installGlobalListeners();
        setVisible(true);
        surface.repaint();
    }

    public final void hidePopup() {
        if (!isVisible()) {
            return;
        }
        setVisible(false);
        uninstallGlobalListeners();
        detachAnchorWindow();
    }

    protected final JPanel content() {
        return content;
    }

    /** 子类在此同步自己的文字和图标颜色，禁止回退到 LAF 默认色。 */
    protected void onThemeChanged(UiTheme theme) {
    }

    protected static String ellipsize(String text, FontMetrics metrics, int availableWidth) {
        if (availableWidth <= 0) {
            return "";
        }
        if (metrics.stringWidth(text) <= availableWidth) {
            return text;
        }
        String ellipsis = "…";
        int width = metrics.stringWidth(ellipsis);
        if (width > availableWidth) {
            return "";
        }
        int end = text.length();
        while (end > 0 && width + metrics.stringWidth(text.substring(0, end)) > availableWidth) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    private void applyTheme() {
        updateSurfaceColors();
        onThemeChanged(tm.theme());
        surface.repaint();
        content.repaint();
    }

    private void updateSurfaceColors() {
        UiTheme theme = tm.theme();
        Color windowBg = theme.windowBg;
        setBackground(windowBg);
        getRootPane().setBackground(windowBg);
        surface.setBackground(theme.panelBg);
        content.setBackground(theme.panelBg);
    }

    private void clampToScreen(Component anchor, Point location) {
        GraphicsConfiguration configuration = anchor.getGraphicsConfiguration();
        if (configuration == null) {
            configuration = getGraphicsConfiguration();
        }
        if (configuration == null) {
            return;
        }
        java.awt.Rectangle bounds = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int left = bounds.x + insets.left;
        int top = bounds.y + insets.top;
        int right = bounds.x + bounds.width - insets.right - getWidth();
        int bottom = bounds.y + bounds.height - insets.bottom - getHeight();
        location.x = Math.max(left, Math.min(location.x, Math.max(left, right)));
        location.y = Math.max(top, Math.min(location.y, Math.max(top, bottom)));
    }

    private void handleOutsideClick(AWTEvent event) {
        if (!isVisible() || !(event instanceof MouseEvent)
                || event.getID() != MouseEvent.MOUSE_PRESSED) {
            return;
        }
        Object source = event.getSource();
        if (!(source instanceof Component)) {
            hidePopup();
            return;
        }
        Window eventWindow = SwingUtilities.getWindowAncestor((Component) source);
        if (eventWindow != this) {
            hidePopup();
        }
    }

    private boolean handleEscape(KeyEvent event) {
        if (isVisible() && event.getID() == KeyEvent.KEY_PRESSED
                && event.getKeyCode() == KeyEvent.VK_ESCAPE) {
            hidePopup();
            return true;
        }
        return false;
    }

    private void installGlobalListeners() {
        if (globalListenersInstalled) {
            return;
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(outsideClickListener, AWTEvent.MOUSE_EVENT_MASK);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(escapeDispatcher);
        globalListenersInstalled = true;
    }

    private void uninstallGlobalListeners() {
        if (!globalListenersInstalled) {
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClickListener);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(escapeDispatcher);
        globalListenersInstalled = false;
    }

    private void attachAnchorWindow(Window window) {
        if (anchorWindow == window) {
            return;
        }
        detachAnchorWindow();
        anchorWindow = window;
        if (anchorWindow != null) {
            anchorWindow.addWindowFocusListener(ownerFocusListener);
        }
    }

    private void detachAnchorWindow() {
        if (anchorWindow != null) {
            anchorWindow.removeWindowFocusListener(ownerFocusListener);
            anchorWindow = null;
        }
    }

    @Override
    public void dispose() {
        uninstallGlobalListeners();
        detachAnchorWindow();
        super.dispose();
    }

    private final class SurfacePanel extends JPanel {
        private static final long serialVersionUID = 1L;

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(theme.panelBg);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
                g.setColor(theme.border);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f,
                        getWidth() - 1f, getHeight() - 1f, ARC, ARC));
            } finally {
                g.dispose();
            }
        }
    }
}
