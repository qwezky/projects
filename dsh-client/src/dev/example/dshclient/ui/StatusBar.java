package dev.example.dshclient.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** 顶部 48px 状态条，含横向淡渐变、下沿分隔线和几何徽记。 */
public final class StatusBar extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int HEIGHT = 36;

    private final transient ThemeManager tm;
    private final JLabel titleLabel;
    private final JPanel actionArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));

    public StatusBar(String title, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.titleLabel = new JLabel(Objects.requireNonNull(title, "title"));
        setOpaque(false);
        setLayout(new BorderLayout(12, 0));
        setBorder(BorderFactory.createEmptyBorder(0, 12, 1, 12));

        JPanel left = new JPanel(new BorderLayout(10, 0));
        left.setOpaque(false);
        left.add(new Badge(), BorderLayout.WEST);
        titleLabel.setFont(UiFonts.ui(Font.BOLD, 13f));
        left.add(titleLabel, BorderLayout.CENTER);
        add(left, BorderLayout.CENTER);

        actionArea.setOpaque(false);
        add(actionArea, BorderLayout.EAST);
        applyTheme();
        tm.addListener(this::applyTheme);
    }

    public void setTitle(String title) {
        titleLabel.setText(Objects.requireNonNull(title, "title"));
        revalidate();
        repaint();
    }

    /** 右侧预留主题切换和操作按钮位置。 */
    public JPanel actionArea() {
        return actionArea;
    }

    private void applyTheme() {
        titleLabel.setForeground(tm.theme().textPrimary);
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        size.height = HEIGHT;
        return size;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setPaint(new GradientPaint(0f, 0f, theme.statusBarStart,
                    getWidth(), 0f, theme.statusBarEnd));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(theme.separator);
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
        } finally {
            g.dispose();
        }
    }

    private final class Badge extends JComponent {
        private static final long serialVersionUID = 1L;

        private Badge() {
            setOpaque(false);
            setPreferredSize(new Dimension(22, 22));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Path2D.Float outer = new Path2D.Float();
                outer.moveTo(11, 1);
                outer.lineTo(21, 7);
                outer.lineTo(17, 20);
                outer.lineTo(5, 20);
                outer.lineTo(1, 7);
                outer.closePath();
                g.setPaint(new GradientPaint(0f, 0f, theme.accent,
                        22f, 22f, theme.info));
                g.fill(outer);

                Path2D.Float inner = new Path2D.Float();
                inner.moveTo(11, 6);
                inner.lineTo(16, 9);
                inner.lineTo(13, 16);
                inner.lineTo(9, 16);
                inner.lineTo(6, 9);
                inner.closePath();
                g.setColor(new Color(255, 255, 255, 190));
                g.fill(inner);
            } finally {
                g.dispose();
            }
        }
    }
}
