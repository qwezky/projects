package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** 仿 Edge 的下载弹层；列表、图标、进度条和文字按钮均不使用 LAF 默认配色。 */
public final class DownloadPanel extends PopupSurface {
    private static final long serialVersionUID = 1L;

    private final JLabel titleLabel = new JLabel("下载");
    private final JPanel listPanel = new JPanel();
    private final FooterButton clearButton = new FooterButton("清除已完成");
    private final FooterButton openFolderButton = new FooterButton("打开下载文件夹");

    public DownloadPanel(ThemeManager tm) {
        super(tm);
        content().setLayout(new BorderLayout());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(4, 6, 8, 6));
        titleLabel.setOpaque(false);
        titleLabel.setFont(UiFonts.ui(Font.BOLD, 15f));
        titleLabel.setForeground(tm.theme().textPrimary);
        header.add(titleLabel, BorderLayout.CENTER);
        content().add(header, BorderLayout.NORTH);

        listPanel.setOpaque(false);
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        content().add(listPanel, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.add(new Divider(), BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        actions.add(clearButton);
        actions.add(openFolderButton);
        footer.add(actions, BorderLayout.CENTER);
        content().add(footer, BorderLayout.SOUTH);
        setRows(Collections.emptyList());
    }

    /** 整体替换视图，不另外维护下载数据模型。 */
    public void setRows(List<DownloadRow> rows) {
        Objects.requireNonNull(rows, "rows");
        listPanel.removeAll();
        if (rows.isEmpty()) {
            listPanel.add(new EmptyView());
        } else {
            for (DownloadRow row : rows) {
                listPanel.add(new RowView(Objects.requireNonNull(row, "row")));
            }
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    public void setFooterActions(Runnable onClearFinished, Runnable onOpenFolder) {
        clearButton.setAction(onClearFinished);
        openFolderButton.setAction(onOpenFolder);
    }

    @Override
    protected void onThemeChanged(UiTheme theme) {
        titleLabel.setForeground(theme.textPrimary);
        titleLabel.repaint();
        listPanel.repaint();
        clearButton.repaint();
        openFolderButton.repaint();
    }

    private final class RowView extends JComponent {
        private static final long serialVersionUID = 1L;
        private static final int ICON_SIZE = 24;

        private final transient DownloadRow row;
        private Hover hover = Hover.NONE;

        private RowView(DownloadRow row) {
            this.row = row;
            setOpaque(false);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    updateHover(iconAt(e.getX(), e.getY()));
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    updateHover(Hover.NONE);
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!SwingUtilities.isLeftMouseButton(e)) {
                        return;
                    }
                    Hover target = iconAt(e.getX(), e.getY());
                    if (target == Hover.FOLDER && row.onFolder != null) {
                        row.onFolder.run();
                    } else if (target == Hover.OPEN && row.onOpen != null) {
                        row.onOpen.run();
                    } else if (target == Hover.DELETE && row.onDelete != null) {
                        row.onDelete.run();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int folderX = folderBounds().x;
                int textWidth = Math.max(0, folderX - 28);

                Font nameFont = UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE);
                g.setFont(nameFont);
                g.setColor(theme.textPrimary);
                FontMetrics nameMetrics = g.getFontMetrics();
                g.drawString(ellipsize(row.name, nameMetrics, textWidth), 12, 19);

                Font statusFont = UiFonts.ui(Font.PLAIN, UiFonts.NOTE_SIZE);
                g.setFont(statusFont);
                g.setColor(theme.textSecondary);
                FontMetrics statusMetrics = g.getFontMetrics();
                String directory = row.path.getParent() == null
                        ? row.path.getAbsolutePath() : row.path.getParent();
                String status = row.status + " · " + directory;
                g.drawString(ellipsize(status, statusMetrics, textWidth), 12, 38);

                if (!row.done) {
                    paintProgress(g, theme);
                }
                paintIconButton(g, theme, Hover.FOLDER);
                paintIconButton(g, theme, Hover.OPEN);
                paintIconButton(g, theme, Hover.DELETE);

                g.setColor(theme.separator);
                g.drawLine(12, getHeight() - 1, getWidth() - 12, getHeight() - 1);
            } finally {
                g.dispose();
            }
        }

        private void paintProgress(Graphics2D g, UiTheme theme) {
            int width = getWidth() - 24;
            int y = getHeight() - 10;
            g.setColor(theme.separator);
            g.fill(new RoundRectangle2D.Float(12, y, width, 3, 3, 3));
            float progressWidth = width * row.percent / 100f;
            if (progressWidth > 0f) {
                g.setPaint(new GradientPaint(12, y, theme.accent,
                        12 + width, y, theme.info));
                g.fill(new RoundRectangle2D.Float(12, y, progressWidth, 3, 3, 3));
            }
        }

        private Rectangle deleteBounds() {
            return new Rectangle(getWidth() - 92, 7, ICON_SIZE, ICON_SIZE);
        }

        private void paintIconButton(Graphics2D g, UiTheme theme, Hover target) {
            Rectangle bounds = target == Hover.FOLDER ? folderBounds()
                    : target == Hover.OPEN ? openBounds() : deleteBounds();
            if (hover == target) {
                Color hoverColor = theme.textPrimary;
                g.setColor(new Color(hoverColor.getRed(), hoverColor.getGreen(),
                        hoverColor.getBlue(), 15));
                g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
            }
            Runnable action = target == Hover.FOLDER ? row.onFolder
                    : target == Hover.OPEN ? row.onOpen : row.onDelete;
            g.setColor(action == null ? theme.textDisabled
                    : hover == target ? theme.textPrimary : theme.textSecondary);
            g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            if (target == Hover.FOLDER) {
                drawFolder(g, bounds.x, bounds.y);
            } else if (target == Hover.OPEN) {
                drawOpen(g, bounds.x, bounds.y);
            } else {
                drawTrash(g, bounds.x, bounds.y);
            }
        }

        private void updateHover(Hover next) {
            if (hover == next) {
                return;
            }
            Hover previous = hover;
            hover = next;
            repaintIcon(previous);
            repaintIcon(next);
            setCursor(next == Hover.NONE ? Cursor.getDefaultCursor()
                    : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private void repaintIcon(Hover target) {
            if (target == Hover.FOLDER) {
                repaint(folderBounds());
            } else if (target == Hover.OPEN) {
                repaint(openBounds());
            } else if (target == Hover.DELETE) {
                repaint(deleteBounds());
            }
        }

        private Hover iconAt(int x, int y) {
            if (folderBounds().contains(x, y)) {
                return Hover.FOLDER;
            }
            if (openBounds().contains(x, y)) {
                return Hover.OPEN;
            }
            if (deleteBounds().contains(x, y)) {
                return Hover.DELETE;
            }
            return Hover.NONE;
        }

        private Rectangle folderBounds() {
            return new Rectangle(getWidth() - 64, 7, ICON_SIZE, ICON_SIZE);
        }

        private Rectangle openBounds() {
            return new Rectangle(getWidth() - 36, 7, ICON_SIZE, ICON_SIZE);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(380, row.done ? 52 : 62);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    private enum Hover { NONE, FOLDER, OPEN, DELETE }

    private static void drawFolder(Graphics2D g, int x, int y) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x + 4, y + 6);
        path.lineTo(x + 9, y + 6);
        path.lineTo(x + 11, y + 8);
        path.lineTo(x + 20, y + 8);
        path.lineTo(x + 20, y + 19);
        path.lineTo(x + 4, y + 19);
        path.closePath();
        g.draw(path);
    }

    private static void drawTrash(Graphics2D g, int x, int y) {
        Path2D.Float body = new Path2D.Float();
        body.moveTo(x + 7, y + 8);
        body.lineTo(x + 17, y + 8);
        body.lineTo(x + 16, y + 20);
        body.lineTo(x + 8, y + 20);
        body.closePath();
        g.draw(body);
        g.drawLine(x + 5, y + 8, x + 19, y + 8);
        g.drawLine(x + 10, y + 5, x + 14, y + 5);
        g.drawLine(x + 10, y + 12, x + 10, y + 17);
        g.drawLine(x + 14, y + 12, x + 14, y + 17);
    }

    private static void drawOpen(Graphics2D g, int x, int y) {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(x + 11, y + 5);
        path.lineTo(x + 5, y + 5);
        path.lineTo(x + 5, y + 19);
        path.lineTo(x + 18, y + 19);
        path.lineTo(x + 18, y + 13);
        path.moveTo(x + 13, y + 11);
        path.lineTo(x + 20, y + 4);
        path.moveTo(x + 15, y + 4);
        path.lineTo(x + 20, y + 4);
        path.lineTo(x + 20, y + 9);
        g.draw(path);
    }

    private final class EmptyView extends JComponent {
        private static final long serialVersionUID = 1L;

        private EmptyView() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(theme.textSecondary);
                Font mainFont = UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE);
                g.setFont(mainFont);
                FontMetrics mainMetrics = g.getFontMetrics();
                String empty = "还没有下载";
                g.drawString(empty, (getWidth() - mainMetrics.stringWidth(empty)) / 2, 38);

                Font hintFont = UiFonts.ui(Font.PLAIN, 11f);
                g.setFont(hintFont);
                FontMetrics hintMetrics = g.getFontMetrics();
                String path = new File(System.getProperty("user.home"), "Downloads").getAbsolutePath();
                String hint = ellipsize(path, hintMetrics, getWidth() - 32);
                g.drawString(hint, (getWidth() - hintMetrics.stringWidth(hint)) / 2, 62);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(380, 88);
        }
    }

    private final class FooterButton extends JComponent {
        private static final long serialVersionUID = 1L;

        private final String text;
        private transient Runnable action;
        private boolean hovered;

        private FooterButton(String text) {
            this.text = text;
            setOpaque(false);
            setFont(UiFonts.ui(Font.PLAIN, UiFonts.NOTE_SIZE));
            setEnabled(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    if (isEnabled()) {
                        hovered = true;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (isEnabled() && action != null && SwingUtilities.isLeftMouseButton(e)) {
                        action.run();
                    }
                }
            });
        }

        private void setAction(Runnable action) {
            this.action = action;
            setEnabled(action != null);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (hovered && isEnabled()) {
                    Color hoverColor = theme.textPrimary;
                    g.setColor(new Color(hoverColor.getRed(), hoverColor.getGreen(),
                            hoverColor.getBlue(), 15));
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                }
                g.setFont(getFont());
                g.setColor(isEnabled() ? theme.textPrimary : theme.textDisabled);
                FontMetrics metrics = g.getFontMetrics();
                int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, baseline);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(text) + 16, 30);
        }
    }

    private final class Divider extends JComponent {
        private static final long serialVersionUID = 1L;

        private Divider() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(tm.theme().separator);
            graphics.drawLine(0, 0, getWidth(), 0);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }
    }
}
