package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** 点击后使用自绘 ContextMenu 选值，完全不显示 JComboBox 的 LAF 外观。 */
public final class ChoiceField extends JComponent {
    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 30;
    private static final int ARC = 6;

    private final transient ThemeManager tm;
    private final String[] values;
    private final ContextMenu popup;
    private final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private int selectedIndex;
    private boolean hovered;

    public ChoiceField(String[] values, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.values = Objects.requireNonNull(values, "values").clone();
        this.selectedIndex = values.length == 0 ? -1 : 0;
        this.popup = new ContextMenu(tm);
        for (int i = 0; i < this.values.length; i++) {
            int index = i;
            popup.addItem(this.values[i], () -> setSelectedIndex(index));
        }
        setOpaque(false);
        setFocusable(true);
        setFont(UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installInputHandlers();
        tm.addListener(this::repaint);
    }

    public String value() {
        return selectedIndex < 0 ? "" : values[selectedIndex];
    }

    public int selectedIndex() {
        return selectedIndex;
    }

    public void setValue(String value) {
        Objects.requireNonNull(value, "value");
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                setSelectedIndex(i);
                return;
            }
        }
        throw new IllegalArgumentException("未知选项: " + value);
    }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= values.length) {
            throw new IndexOutOfBoundsException("选项索引越界: " + index);
        }
        if (selectedIndex == index) {
            return;
        }
        selectedIndex = index;
        repaint();
        for (Consumer<String> listener : listeners) {
            listener.accept(value());
        }
    }

    public void addSelectionListener(Consumer<String> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            popup.hidePopup();
        }
        repaint();
    }

    private void openPopup() {
        if (isEnabled() && values.length > 0 && !popup.isVisible()) {
            popup.showAt(this, 0, getHeight() + 4);
        }
    }

    private void installInputHandlers() {
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
                    openPopup();
                }
            }
        });
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                repaint();
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!isEnabled() || values.length == 0) {
                    return;
                }
                if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                    openPopup();
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_UP) {
                    setSelectedIndex(Math.max(0, selectedIndex - 1));
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_DOWN) {
                    setSelectedIndex(Math.min(values.length - 1, selectedIndex + 1));
                    e.consume();
                }
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
            g.setColor(theme.inputBg);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            if (hovered && isEnabled()) {
                Color hover = theme.textPrimary;
                g.setColor(new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), 15));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            }
            if (isFocusOwner() && isEnabled()) {
                g.setPaint(new GradientPaint(0f, 0f, theme.accent,
                        getWidth(), 0f, theme.info));
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Float(1f, 1f,
                        getWidth() - 3f, getHeight() - 3f, ARC, ARC));
            } else {
                g.setColor(theme.border);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f,
                        getWidth() - 1f, getHeight() - 1f, ARC, ARC));
            }

            g.setFont(getFont());
            g.setColor(!isEnabled() ? theme.textDisabled
                    : selectedIndex < 0 ? theme.textSecondary : theme.textPrimary);
            FontMetrics metrics = g.getFontMetrics();
            String text = selectedIndex < 0 ? "请选择" : value();
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g.drawString(PopupSurface.ellipsize(text, metrics, getWidth() - 44), 12, baseline);

            g.setColor(isEnabled() ? theme.textSecondary : theme.textDisabled);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Float arrow = new Path2D.Float();
            int x = getWidth() - 18;
            int y = getHeight() / 2 - 2;
            arrow.moveTo(x - 3, y);
            arrow.lineTo(x + 1, y + 4);
            arrow.lineTo(x + 5, y);
            g.draw(arrow);
        } finally {
            g.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int width = 80;
        for (String value : values) {
            width = Math.max(width, metrics.stringWidth(value));
        }
        return new Dimension(Math.min(280, Math.max(160, width + 48)), HEIGHT);
    }
}
