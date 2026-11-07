package dev.example.dshclient.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.JPanel;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** 轻量页签条；内容用 CardLayout 切换，指示条以 160ms ease-out 滑动。 */
public final class TabStrip extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int HEADER_HEIGHT = 36;
    private static final int SLIDE_MS = 160;

    private final transient ThemeManager tm;
    private final CardLayout cardLayout = new CardLayout();
    private final JPanel content = new JPanel(cardLayout);
    private final ArrayList<String> titles = new ArrayList<>();
    private final ArrayList<JComponent> comps = new ArrayList<>();
    private final ArrayList<TabMenu> menus = new ArrayList<>();

    /** 页签右键菜单的统一入口：调用方自己决定用 JPopupMenu 还是自绘弹层。 */
    public interface TabMenu {
        void showAt(java.awt.Component anchor, int x, int y);
    }
    private final CopyOnWriteArrayList<Consumer<Integer>> selectionListeners =
            new CopyOnWriteArrayList<>();
    private final Header header = new Header();
    private final transient Timer slideTimer;

    private int selected = -1;
    private int hovered = -1;
    private long slideStartedAt;
    private float indicatorX = 12f;
    private float indicatorWidth;
    private float fromX;
    private float fromWidth;
    private float toX;
    private float toWidth;

    public TabStrip(ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        setOpaque(false);
        setLayout(new BorderLayout());
        content.setOpaque(false);
        add(header, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        slideTimer = new Timer(16, event -> advanceIndicator());
        tm.addListener(header::repaint);
    }

    public void addTab(String title, JComponent tabContent) {
        addTab(title, tabContent, null);
    }

    /** 带右键菜单的页签（菜单由调用方每次新建，避免复用同一个 JPopupMenu）。 */
    public void addTab(String title, JComponent tabContent, TabMenu menu) {
        titles.add(Objects.requireNonNull(title, "title"));
        comps.add(Objects.requireNonNull(tabContent, "content"));
        menus.add(menu);
        content.add(tabContent, String.valueOf(titles.size() - 1));
        if (selected < 0) {
            selected = 0;
            indicatorX = tabX(0) + 12f;
            indicatorWidth = indicatorWidth(0);
            cardLayout.show(content, "0");
        }
        header.revalidate();
        header.repaint();
        content.revalidate();
        content.repaint();
    }

    public void select(int index) {
        checkIndex(index);
        if (index == selected) {
            return;
        }
        slideTimer.stop();
        selected = index;
        cardLayout.show(content, String.valueOf(index));
        toX = tabX(index) + 12f;
        toWidth = indicatorWidth(index);
        if (canAnimate() && indicatorWidth > 0f) {
            fromX = indicatorX;
            fromWidth = indicatorWidth;
            slideStartedAt = System.nanoTime();
            slideTimer.start();
        } else {
            snapIndicator();
        }
        header.repaint();
        content.repaint();
        for (Consumer<Integer> listener : selectionListeners) {
            listener.accept(index);
        }
    }

    public int selected() {
        return selected;
    }

    public int tabCount() {
        return titles.size();
    }

    /** 按组件找页签下标（-1 = 不在）。关闭页签后其它下标会变，用这个比记数字稳。 */
    public int indexOf(JComponent component) {
        return comps.indexOf(component);
    }

    /** 关掉一个页签：重建内容卡片的 key，保证后续下标连续。 */
    public void removeTab(int index) {
        checkIndex(index);
        titles.remove(index);
        comps.remove(index);
        menus.remove(index);
        content.removeAll();
        for (int i = 0; i < comps.size(); i++) {
            content.add(comps.get(i), String.valueOf(i));
        }
        int next = Math.min(selected > index ? selected - 1 : selected,
                Math.max(0, comps.size() - 1));
        selected = -1;
        header.revalidate();
        header.repaint();
        content.revalidate();
        content.repaint();
        if (comps.isEmpty()) {
            indicatorWidth = 0f;
            repaint();
            return;
        }
        select(Math.max(0, next));
    }

    public void addSelectionListener(Consumer<Integer> listener) {
        selectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private void advanceIndicator() {
        if (!canAnimate()) {
            snapIndicator();
            return;
        }
        float progress = Math.min(1f,
                (System.nanoTime() - slideStartedAt) / (SLIDE_MS * 1_000_000f));
        float eased = 1f - (float) Math.pow(1f - progress, 3);
        indicatorX = fromX + (toX - fromX) * eased;
        indicatorWidth = fromWidth + (toWidth - fromWidth) * eased;
        if (progress >= 1f) {
            snapIndicator();
        } else {
            repaintIndicator();
        }
    }

    private void snapIndicator() {
        slideTimer.stop();
        if (selected >= 0) {
            indicatorX = tabX(selected) + 12f;
            indicatorWidth = indicatorWidth(selected);
        }
        repaintIndicator();
    }

    private void repaintIndicator() {
        header.repaint(0, HEADER_HEIGHT - 5, header.getWidth(), 5);
    }

    private boolean canAnimate() {
        Window window = SwingUtilities.getWindowAncestor(this);
        boolean iconified = window instanceof Frame
                && (((Frame) window).getExtendedState() & Frame.ICONIFIED) != 0;
        return isShowing() && window != null && window.isActive() && !iconified;
    }

    private void checkIndex(int index) {
        if (index < 0 || index >= titles.size()) {
            throw new IndexOutOfBoundsException("页签索引越界: " + index);
        }
    }

    private int tabX(int index) {
        int x = 0;
        for (int i = 0; i < index; i++) {
            x += tabWidth(i);
        }
        return x;
    }

    private int tabWidth(int index) {
        return header.getFontMetrics(header.getFont()).stringWidth(titles.get(index)) + 32;
    }

    private float indicatorWidth(int index) {
        return Math.max(8, tabWidth(index) - 24);
    }

    private int tabAt(int x) {
        int start = 0;
        for (int i = 0; i < titles.size(); i++) {
            start += tabWidth(i);
            if (x < start) {
                return i;
            }
        }
        return -1;
    }

    private final class Header extends JComponent {
        private static final long serialVersionUID = 1L;

        private Header() {
            setOpaque(false);
            setFont(UiFonts.ui(Font.PLAIN, UiFonts.LABEL_SIZE));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int index = tabAt(e.getX());
                    if (index >= 0) {
                        select(index);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowMenu(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowMenu(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    int index = tabAt(e.getX());
                    if (index != hovered) {
                        hovered = index;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = -1;
                    repaint();
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** 右键弹出该页签自己的菜单（Windows 上 popup 触发在 released，Mac 在 pressed）。 */
        private void maybeShowMenu(MouseEvent e) {
            if (!e.isPopupTrigger()) {
                return;
            }
            int index = tabAt(e.getX());
            if (index < 0 || index >= menus.size() || menus.get(index) == null) {
                return;
            }
            menus.get(index).showAt(e.getComponent(), e.getX(), e.getY());
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(theme.panelBg);
                g.fillRect(0, 0, getWidth(), getHeight());
                FontMetrics metrics = g.getFontMetrics(getFont());
                int x = 0;
                for (int i = 0; i < titles.size(); i++) {
                    int width = tabWidth(i);
                    if (i == hovered) {
                        Color hover = theme.textPrimary;
                        g.setColor(new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), 15));
                        g.fillRoundRect(x + 2, 3, width - 4, HEADER_HEIGHT - 7, 6, 6);
                    }
                    g.setColor(i == selected ? theme.textPrimary : theme.textSecondary);
                    g.setFont(getFont());
                    int textWidth = metrics.stringWidth(titles.get(i));
                    int baseline = (HEADER_HEIGHT - metrics.getHeight()) / 2 + metrics.getAscent();
                    g.drawString(titles.get(i), x + (width - textWidth) / 2, baseline);
                    x += width;
                }
                g.setColor(theme.separator);
                g.drawLine(0, HEADER_HEIGHT - 1, getWidth(), HEADER_HEIGHT - 1);
                if (selected >= 0 && indicatorWidth > 0f) {
                    g.setPaint(new GradientPaint(indicatorX, 0f, theme.accent,
                            indicatorX + indicatorWidth, 0f, theme.info));
                    g.fill(new RoundRectangle2D.Float(indicatorX, HEADER_HEIGHT - 4,
                            indicatorWidth, 3f, 3f, 3f));
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int width = 0;
            for (int i = 0; i < titles.size(); i++) {
                width += tabWidth(i);
            }
            return new Dimension(width, HEADER_HEIGHT);
        }
    }
}
