package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Objects;
import javax.swing.JButton;

/** 主按钮使用垂直渐变，其他变体保持克制并共享焦点与禁用状态。 */
public final class ModernButton extends JButton {
    public enum Kind { PRIMARY, SECONDARY, GHOST }

    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 30;
    private static final int ARC = 8;

    private final Kind kind;
    private final transient ThemeManager tm;

    public ModernButton(String text, Kind kind, ThemeManager tm) {
        super(text);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.tm = Objects.requireNonNull(tm, "tm");
        setOpaque(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setRolloverEnabled(true);
        setFont(UiFonts.ui(Font.PLAIN, UiFonts.LABEL_SIZE));
        setMargin(new Insets(0, 14, 0, 14));
        tm.addListener(this::repaint);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            boolean pressed = isEnabled() && (getModel().isPressed() || getModel().isArmed());
            boolean hover = isEnabled() && !pressed && getModel().isRollover();
            int offsetY = pressed ? 1 : 0;

            if (kind == Kind.PRIMARY) {
                paintPrimary(g, theme, pressed, hover, offsetY);
            } else {
                paintSecondary(g, theme, pressed, hover, offsetY);
            }

            if (isFocusOwner() && isEnabled()) {
                g.setColor(theme.focusRing);
                g.setStroke(new BasicStroke(2f));
                g.drawRoundRect(1, 1 + offsetY, getWidth() - 3, getHeight() - 3,
                        ARC + 2, ARC + 2);
            }
            paintText(g, theme, offsetY);
        } finally {
            g.dispose();
        }
    }

    private void paintPrimary(Graphics2D g, UiTheme theme, boolean pressed,
            boolean hover, int offsetY) {
        if (hover) {
            g.setColor(withAlpha(theme.accent, 90));
            g.setStroke(new BasicStroke(2f));
            g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, ARC + 2, ARC + 2);
        }

        Color start = theme.accent;
        Color end = theme.accentHover;
        if (!isEnabled()) {
            start = desaturated(start);
            end = desaturated(end);
        } else if (pressed) {
            start = brightness(start, 0.94f);
            end = brightness(end, 0.94f);
        } else if (hover) {
            start = brightness(start, 1.06f);
            end = brightness(end, 1.06f);
        }
        int x = 2;
        int y = 2 + offsetY;
        int width = getWidth() - 4;
        int height = getHeight() - 4;
        g.setPaint(new GradientPaint(x, y, start, x, y + height, end));
        g.fillRoundRect(x, y, width, height, ARC, ARC);
        g.setColor(end);
        g.drawRoundRect(x, y, width - 1, height - 1, ARC, ARC);
    }

    private void paintSecondary(Graphics2D g, UiTheme theme, boolean pressed,
            boolean hover, int offsetY) {
        Color background = null;
        if (isEnabled()) {
            if (pressed) {
                background = brightness(theme.controlPressed, 0.94f);
            } else if (hover) {
                background = brightness(theme.controlHover, 1.06f);
            } else if (kind == Kind.SECONDARY) {
                background = theme.panelBg;
            }
        } else if (kind == Kind.SECONDARY) {
            background = desaturated(theme.inputBg);
        }
        int y = 1 + offsetY;
        if (background != null) {
            g.setColor(background);
            g.fillRoundRect(1, y, getWidth() - 2, getHeight() - 2, ARC, ARC);
        }
        if (kind == Kind.SECONDARY) {
            g.setColor(theme.border);
            g.drawRoundRect(1, y, getWidth() - 3, getHeight() - 3, ARC, ARC);
        }
    }

    private void paintText(Graphics2D g, UiTheme theme, int offsetY) {
        String text = getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        g.setColor(!isEnabled() ? theme.textDisabled
                : kind == Kind.PRIMARY ? theme.primaryText : theme.textPrimary);
        g.setFont(getFont());
        FontMetrics metrics = g.getFontMetrics();
        int x = (getWidth() - metrics.stringWidth(text)) / 2;
        int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent() + offsetY;
        g.drawString(text, Math.max(8, x), baseline);
    }

    private static Color brightness(Color color, float factor) {
        return new Color(Math.min(255, Math.round(color.getRed() * factor)),
                Math.min(255, Math.round(color.getGreen() * factor)),
                Math.min(255, Math.round(color.getBlue() * factor)));
    }

    private static Color desaturated(Color color) {
        int gray = Math.round(0.2126f * color.getRed() + 0.7152f * color.getGreen()
                + 0.0722f * color.getBlue());
        return new Color(gray, gray, gray);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        size.width = Math.max(size.width, 64);
        size.height = HEIGHT;
        return size;
    }
}
