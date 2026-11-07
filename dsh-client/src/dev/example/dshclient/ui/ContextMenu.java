package dev.example.dshclient.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Objects;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** 完全自绘的右键菜单，菜单项索引不包含分隔线。 */
public final class ContextMenu extends PopupSurface {
    private static final long serialVersionUID = 1L;
    private static final int ROW_HEIGHT = 30;

    private final ArrayList<MenuRow> items = new ArrayList<>();
    private MenuRow hoveredRow;

    public ContextMenu(ThemeManager tm) {
        super(tm);
        content().setLayout(new BoxLayout(content(), BoxLayout.Y_AXIS));
    }

    public void addItem(String text, Runnable action) {
        MenuRow row = new MenuRow(Objects.requireNonNull(text, "text"),
                Objects.requireNonNull(action, "action"));
        items.add(row);
        content().add(row);
        content().revalidate();
        content().repaint();
    }

    public void addSeparator() {
        content().add(new Separator());
        content().revalidate();
        content().repaint();
    }

    public void setEnabled(int index, boolean enabled) {
        if (index < 0 || index >= items.size()) {
            throw new IndexOutOfBoundsException("菜单项索引越界: " + index);
        }
        items.get(index).setEnabled(enabled);
    }

    @Override
    protected void onThemeChanged(UiTheme theme) {
        for (MenuRow item : items) {
            item.repaint();
        }
    }

    private void setHoveredRow(MenuRow row) {
        if (hoveredRow == row) {
            return;
        }
        MenuRow previous = hoveredRow;
        hoveredRow = row;
        if (previous != null) {
            previous.setHovered(false);
        }
        if (row != null) {
            row.setHovered(true);
        }
    }

    private final class MenuRow extends JComponent {
        private static final long serialVersionUID = 1L;

        private final String text;
        private final transient Runnable action;
        private boolean hovered;

        private MenuRow(String text, Runnable action) {
            this.text = text;
            this.action = action;
            setOpaque(false);
            setFont(UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (isEnabled()) {
                        setHoveredRow(MenuRow.this);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hoveredRow == MenuRow.this) {
                        setHoveredRow(null);
                    }
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isEnabled() && SwingUtilities.isLeftMouseButton(e)) {
                        hidePopup();
                        action.run();
                    }
                }
            });
        }

        private void setHovered(boolean hovered) {
            if (this.hovered != hovered) {
                this.hovered = hovered;
                repaint();
            }
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            if (!enabled && hoveredRow == this) {
                setHoveredRow(null);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered && isEnabled()) {
                    Color hover = theme.textPrimary;
                    g.setColor(new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), 15));
                    g.fillRoundRect(1, 1, getWidth() - 2, getHeight() - 2, 6, 6);
                }
                g.setFont(getFont());
                g.setColor(isEnabled() ? theme.textPrimary : theme.textDisabled);
                FontMetrics metrics = g.getFontMetrics();
                String label = ellipsize(text, metrics, getWidth() - 24);
                int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(label, 12, baseline);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            int textWidth = getFontMetrics(getFont()).stringWidth(text);
            return new Dimension(Math.min(396, Math.max(160, textWidth + 24)), ROW_HEIGHT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, ROW_HEIGHT);
        }
    }

    private final class Separator extends JComponent {
        private static final long serialVersionUID = 1L;

        private Separator() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(tm.theme().separator);
            graphics.drawLine(12, getHeight() / 2, getWidth() - 12, getHeight() / 2);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(160, 9);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 9);
        }
    }
}
