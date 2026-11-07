package dev.example.dshclient.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.AbstractButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicCheckBoxUI;

/** 为任意 JCheckBox 安装主题化自绘外观，避免 Metal 图标和底色。 */
public final class ToggleField extends JCheckBox {
    private static final long serialVersionUID = 1L;

    public ToggleField(ThemeManager tm) {
        style(this, tm);
    }

    public static void style(JCheckBox box, ThemeManager tm) {
        box.setUI(new ThemedCheckBoxUI(tm));
        box.setOpaque(false);
        box.setContentAreaFilled(false);
        box.setBorderPainted(false);
        box.setFocusPainted(false);
        box.setRolloverEnabled(true);
        box.setFocusable(true);
        box.setFont(UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE));
        tm.addListener(box::repaint);
    }

    private static final class ThemedCheckBoxUI extends BasicCheckBoxUI {
        private final ThemeManager tm;

        private ThemedCheckBoxUI(ThemeManager tm) {
            this.tm = tm;
        }

        @Override
        public void paint(Graphics graphics, JComponent component) {
            AbstractButton button = (AbstractButton) component;
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                int boxY = (component.getHeight() - 14) / 2;
                boolean enabled = button.isEnabled();
                boolean selected = button.isSelected();

                if (button.getModel().isRollover() && enabled) {
                    Color hover = theme.textPrimary;
                    g.setColor(new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), 15));
                    g.fillRoundRect(0, boxY - 2, 18, 18, 5, 5);
                }
                if (!enabled) {
                    g.setColor(theme.inputBg);
                } else if (selected) {
                    g.setColor(button.getModel().isPressed() ? theme.accentPressed : theme.accent);
                } else {
                    g.setColor(button.getModel().isPressed() ? theme.controlPressed : theme.inputBg);
                }
                g.fillRoundRect(2, boxY, 14, 14, 3, 3);
                g.setColor(!enabled ? theme.textDisabled : selected ? theme.accent : theme.border);
                g.drawRoundRect(2, boxY, 13, 13, 3, 3);

                if (selected) {
                    g.setColor(enabled ? new Color(0xFFFFFF) : theme.textDisabled);
                    g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    Path2D.Float check = new Path2D.Float();
                    check.moveTo(5, boxY + 7);
                    check.lineTo(8, boxY + 10);
                    check.lineTo(13, boxY + 4);
                    g.draw(check);
                }
                if (component.isFocusOwner() && enabled) {
                    g.setColor(theme.focusRing);
                    g.setStroke(new BasicStroke(1.5f));
                    g.drawRoundRect(0, boxY - 2, 17, 17, 5, 5);
                }

                String text = button.getText();
                if (text != null && !text.isEmpty()) {
                    g.setFont(component.getFont());
                    g.setColor(enabled ? theme.textPrimary : theme.textDisabled);
                    FontMetrics metrics = g.getFontMetrics();
                    int baseline = (component.getHeight() - metrics.getHeight()) / 2
                            + metrics.getAscent();
                    g.drawString(text, 24, baseline);
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public Dimension getPreferredSize(JComponent component) {
            AbstractButton button = (AbstractButton) component;
            FontMetrics metrics = component.getFontMetrics(component.getFont());
            String text = button.getText();
            int textWidth = text == null || text.isEmpty() ? 0 : metrics.stringWidth(text) + 4;
            return new Dimension(textWidth == 0 ? 20 : 24 + textWidth,
                    Math.max(30, metrics.getHeight() + 4));
        }
    }
}
