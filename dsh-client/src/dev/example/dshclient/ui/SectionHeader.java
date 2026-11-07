package dev.example.dshclient.ui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import java.util.Objects;
import javax.swing.JComponent;

/** 小号分组标题配一条细分隔线，减少额外容器和卡片感。 */
public final class SectionHeader extends JComponent {
    private static final long serialVersionUID = 1L;
    private static final float TRACKING = 0.5f;
    private static final int HEIGHT = 28;

    private final transient ThemeManager tm;
    private final String title;

    public SectionHeader(String text, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.title = Objects.requireNonNull(text, "text").toUpperCase(Locale.ROOT);
        setOpaque(false);
        setFont(UiFonts.ui(Font.BOLD, UiFonts.SECTION_TITLE_SIZE));
        tm.addListener(this::repaint);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            int baseline = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();

            g.setColor(theme.textSecondary);
            float x = 0f;
            for (int i = 0; i < title.length(); i++) {
                String character = title.substring(i, i + 1);
                g.drawString(character, x, baseline);
                x += metrics.stringWidth(character) + TRACKING;
            }

            int lineStart = Math.round(x) + 12;
            if (lineStart < getWidth()) {
                g.setColor(theme.separator);
                g.drawLine(lineStart, getHeight() / 2, getWidth(), getHeight() / 2);
            }
        } finally {
            g.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int trackedWidth = metrics.stringWidth(title)
                + Math.max(0, title.length() - 1) * Math.round(TRACKING);
        return new Dimension(trackedWidth + 80, HEIGHT);
    }
}
