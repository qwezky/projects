package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** 设置窗口根面板；原生文本框和下拉在进入行布局前会转换为主题控件。 */
public final class SettingsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int LABEL_WIDTH = 132;
    private static final int LABEL_GAP = 10;

    private final transient ThemeManager tm;
    private final GroupsContainer groups = new GroupsContainer();
    private final JScrollPane scrollPane;
    private final JLabel titleLabel = new JLabel("设置");
    private int groupCount;
    private transient Runnable closeAction;

    public SettingsPanel(ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        setOpaque(true);
        setLayout(new BorderLayout());

        TitleBar titleBar = new TitleBar();
        titleBar.setLayout(new BorderLayout(8, 0));
        titleBar.setBorder(BorderFactory.createEmptyBorder(0, 14, 0, 8));
        titleLabel.setOpaque(false);
        titleLabel.setFont(UiFonts.ui(Font.BOLD, 15f));
        titleBar.add(titleLabel, BorderLayout.CENTER);
        titleBar.add(new CloseButton(), BorderLayout.EAST);
        add(titleBar, BorderLayout.NORTH);

        groups.setOpaque(false);
        groups.setLayout(new BoxLayout(groups, BoxLayout.Y_AXIS));
        groups.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        scrollPane = new JScrollPane(groups);
        scrollPane.setOpaque(false);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getViewport().setScrollMode(javax.swing.JViewport.BLIT_SCROLL_MODE);
        JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
        verticalBar.setUI(new ThinScrollBarUI(tm));
        verticalBar.setOpaque(false);
        verticalBar.setPreferredSize(new Dimension(8, 64));
        verticalBar.setUnitIncrement(16);
        verticalBar.setBlockIncrement(64);
        add(scrollPane, BorderLayout.CENTER);

        applyTheme();
        tm.addListener(this::applyTheme);
    }

    public Group group(String title) {
        GradientCard card = new GradientCard(Objects.requireNonNull(title, "title"), tm);
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        card.setAlignmentX(LEFT_ALIGNMENT);
        GroupBuilder builder = new GroupBuilder(card);
        if (groupCount++ > 0) {
            groups.add(Box.createVerticalStrut(12));
        }
        groups.add(card);
        builder.syncCardSize();
        groups.revalidate();
        groups.repaint();
        return builder;
    }

    /** 未设置动作时，关闭按钮默认 dispose 所在窗口。 */
    public void setOnClose(Runnable closeAction) {
        this.closeAction = closeAction;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(tm.theme().windowBg);
        graphics.fillRect(0, 0, getWidth(), getHeight());
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(640, 520);
    }

    private void applyTheme() {
        UiTheme theme = tm.theme();
        setBackground(theme.windowBg);
        titleLabel.setForeground(theme.textPrimary);
        groups.setBackground(theme.windowBg);
        scrollPane.setBackground(theme.windowBg);
        scrollPane.getViewport().setBackground(theme.windowBg);
        scrollPane.getVerticalScrollBar().repaint();
        titleLabel.repaint();
        groups.repaint();
        repaint();
    }

    private JComponent prepareField(JComponent field) {
        if (field instanceof JTextField) {
            return new ModernField((JTextField) field, tm);
        }
        if (field instanceof JComboBox<?>) {
            return choiceForCombo((JComboBox<?>) field);
        }
        return field;
    }

    private ChoiceField choiceForCombo(JComboBox<?> combo) {
        String[] values = new String[combo.getItemCount()];
        for (int i = 0; i < values.length; i++) {
            values[i] = String.valueOf(combo.getItemAt(i));
        }
        ChoiceField choice = new ChoiceField(values, tm);
        choice.setEnabled(combo.isEnabled());
        if (combo.getSelectedItem() != null) {
            choice.setValue(String.valueOf(combo.getSelectedItem()));
        }
        choice.addSelectionListener(value -> {
            for (int i = 0; i < values.length; i++) {
                if (values[i].equals(value)) {
                    if (combo.getSelectedIndex() != i) {
                        combo.setSelectedIndex(i);
                    }
                    break;
                }
            }
        });
        combo.addActionListener(event -> {
            if (combo.getSelectedItem() != null) {
                try {
                    choice.setValue(String.valueOf(combo.getSelectedItem()));
                } catch (IllegalArgumentException ignored) {
                    // 动态模型由接线层重建对应 ChoiceField。
                }
            }
        });
        combo.addPropertyChangeListener("enabled",
                event -> choice.setEnabled(combo.isEnabled()));
        return choice;
    }

    private void closeWindow() {
        if (closeAction != null) {
            closeAction.run();
            return;
        }
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.dispose();
        }
    }

    public interface Group {
        Group row(String label, JComponent field);

        Group toggle(String label, String note, JCheckBox box);

        Group hint(String text);
    }

    private final class GroupBuilder implements Group {
        private final GradientCard card;
        private final JPanel rows = new JPanel();

        private GroupBuilder(GradientCard card) {
            this.card = card;
            rows.setOpaque(false);
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            card.content().add(rows, BorderLayout.CENTER);
        }

        @Override
        public Group row(String label, JComponent field) {
            Objects.requireNonNull(field, "field");
            JPanel row = formRow(label, prepareField(field), 34, 30);
            addRow(row, 34, 8);
            return this;
        }

        @Override
        public Group toggle(String label, String note, JCheckBox box) {
            Objects.requireNonNull(box, "box");
            ToggleField.style(box, tm);
            JPanel row = new JPanel(new BorderLayout(LABEL_GAP, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
            row.add(new WrappingLabel(label, LABEL_WIDTH, 40, 12f), BorderLayout.WEST);
            row.add(new WrappingLabel(note == null ? "" : note, 0, 40, 11f), BorderLayout.CENTER);
            row.add(box, BorderLayout.EAST);
            row.setPreferredSize(new Dimension(360, 48));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
            addRow(row, 48, 8);
            return this;
        }

        @Override
        public Group hint(String text) {
            WrappingLabel hint = new WrappingLabel(Objects.requireNonNull(text, "text"),
                    0, 32, 11f);
            hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
            addRow(hint, 32, 6);
            return this;
        }

        private JPanel formRow(String label, JComponent field, int height, int contentHeight) {
            JPanel row = new JPanel(new BorderLayout(LABEL_GAP, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
            row.add(new WrappingLabel(label, LABEL_WIDTH, contentHeight, 12f), BorderLayout.WEST);
            row.add(field, BorderLayout.CENTER);
            row.setPreferredSize(new Dimension(360, height));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            return row;
        }

        private void addRow(JComponent row, int height, int spacing) {
            if (rows.getComponentCount() > 0) {
                rows.add(Box.createVerticalStrut(spacing));
            }
            row.setAlignmentX(LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            rows.add(row);
            syncCardSize();
        }

        private void syncCardSize() {
            Dimension preferred = card.getPreferredSize();
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, preferred.height));
            rows.revalidate();
            card.revalidate();
            groups.revalidate();
        }
    }

    private final class WrappingLabel extends JComponent {
        private static final long serialVersionUID = 1L;

        private final String text;
        private final int fixedWidth;
        private final int height;

        private WrappingLabel(String text, int fixedWidth, int height, float fontSize) {
            this.text = Objects.requireNonNull(text, "text");
            this.fixedWidth = fixedWidth;
            this.height = height;
            setOpaque(false);
            setFont(UiFonts.ui(Font.PLAIN, fontSize));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                g.setColor(tm.theme().textSecondary);
                FontMetrics metrics = g.getFontMetrics();
                String[] lines = wrappedLines(metrics, getWidth());
                int lineHeight = metrics.getHeight() + 1;
                int baseline = (getHeight() - lines.length * lineHeight) / 2 + metrics.getAscent();
                for (String line : lines) {
                    g.drawString(line, 0, baseline);
                    baseline += lineHeight;
                }
            } finally {
                g.dispose();
            }
        }

        private String[] wrappedLines(FontMetrics metrics, int width) {
            if (text.isEmpty() || width <= 0 || metrics.stringWidth(text) <= width) {
                return new String[]{text};
            }
            int firstEnd = 0;
            while (firstEnd < text.length()
                    && metrics.stringWidth(text.substring(0, firstEnd + 1)) <= width) {
                firstEnd++;
            }
            if (firstEnd == 0) {
                firstEnd = 1;
            }
            String first = text.substring(0, firstEnd);
            String second = PopupSurface.ellipsize(text.substring(firstEnd), metrics, width);
            return new String[]{first, second};
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(fixedWidth > 0 ? fixedWidth : 120, height);
        }
    }

    private final class TitleBar extends JPanel {
        private static final long serialVersionUID = 1L;

        private TitleBar() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            graphics.setColor(theme.windowBg);
            graphics.fillRect(0, 0, getWidth(), getHeight());
            graphics.setColor(theme.separator);
            graphics.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, 40);
        }
    }

    private final class CloseButton extends JComponent {
        private static final long serialVersionUID = 1L;
        private boolean hovered;

        private CloseButton() {
            setOpaque(false);
            setFocusable(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        closeWindow();
                    }
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                        closeWindow();
                        e.consume();
                    }
                }
            });
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override
                public void focusGained(java.awt.event.FocusEvent e) {
                    repaint();
                }

                @Override
                public void focusLost(java.awt.event.FocusEvent e) {
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered) {
                    Color hover = theme.textPrimary;
                    g.setColor(new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), 15));
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 6, 6);
                }
                if (isFocusOwner()) {
                    g.setColor(theme.focusRing);
                    g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 6, 6);
                }
                g.setColor(hovered ? theme.textPrimary : theme.textSecondary);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D.Float cross = new Path2D.Float();
                cross.moveTo(9, 9);
                cross.lineTo(19, 19);
                cross.moveTo(19, 9);
                cross.lineTo(9, 19);
                g.draw(cross);
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(28, 28);
        }
    }

    private static final class GroupsContainer extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(64, visibleRect.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class ThinScrollBarUI extends BasicScrollBarUI {
        private final ThemeManager tm;

        private ThinScrollBarUI(ThemeManager tm) {
            this.tm = tm;
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
            graphics.setColor(tm.theme().scrollTrack);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.width <= 2 || bounds.height <= 2) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(isDragging ? tm.theme().scrollThumbHover : tm.theme().scrollThumb);
                g.fill(new RoundRectangle2D.Float(bounds.x + 1, bounds.y + 1,
                        bounds.width - 2, bounds.height - 2, 8, 8));
            } finally {
                g.dispose();
            }
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setFocusable(false);
            button.setOpaque(false);
            return button;
        }

        @Override
        public Dimension getPreferredSize(JComponent component) {
            return scrollbar.getOrientation() == JScrollBar.VERTICAL
                    ? new Dimension(8, 48) : new Dimension(48, 8);
        }
    }
}
