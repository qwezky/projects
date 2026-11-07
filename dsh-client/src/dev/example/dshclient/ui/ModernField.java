package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JTextField;

/** 聚焦时使用 accent→info 渐变描边，文本框本身保持透明。 */
public final class ModernField extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 30;
    private static final int ARC = 6;

    private final transient ThemeManager tm;
    private final JTextField field;

    public ModernField(ThemeManager tm) {
        this("", tm);
    }

    public ModernField(String text, ThemeManager tm) {
        this(new JTextField(text), tm);
    }

    /** 可包装调用方已有的文本框，保留其模型和事件监听。 */
    public ModernField(JTextField field, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.field = Objects.requireNonNull(field, "field");
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));

        field.setOpaque(false);
        field.setBorder(BorderFactory.createEmptyBorder());
        field.setFont(UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                applyTheme();
            }

            @Override
            public void focusLost(FocusEvent e) {
                applyTheme();
            }
        });
        add(field, BorderLayout.CENTER);
        applyTheme();
        tm.addListener(this::applyTheme);
    }

    public JTextField field() {
        return field;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        boolean focused = field.isFocusOwner() && field.isEnabled();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(focused ? theme.inputFocusBg : theme.inputBg);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            if (focused) {
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
        } finally {
            g.dispose();
        }
    }

    private void applyTheme() {
        UiTheme theme = tm.theme();
        field.setBackground(field.isFocusOwner() ? theme.inputFocusBg : theme.inputBg);
        field.setForeground(theme.textPrimary);
        field.setDisabledTextColor(theme.textDisabled);
        field.setCaretColor(theme.textPrimary);
        field.setSelectionColor(theme.selectionBg);
        field.setSelectedTextColor(theme.selectionFg);
        field.repaint();
        repaint();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (field != null) {
            field.setEnabled(enabled);
            repaint();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        size.width = Math.max(size.width, 160);
        size.height = HEIGHT;
        return size;
    }
}
