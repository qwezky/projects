package dev.example.dshclient.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** 终端只负责外框、标题条和 8px 内容留白，不介入内部终端渲染。 */
public final class TerminalFrame extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int ARC = 10;
    private static final int TITLE_HEIGHT = 34;

    private final transient ThemeManager tm;
    private final JLabel titleLabel;
    private final JPanel actionArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    private final JPanel terminalBody = new JPanel(new BorderLayout());

    public TerminalFrame(ThemeManager tm) {
        this("终端", tm);
    }

    public TerminalFrame(String title, ThemeManager tm) {
        this.tm = Objects.requireNonNull(tm, "tm");
        this.titleLabel = new JLabel(Objects.requireNonNull(title, "title"));
        setOpaque(false);
        setLayout(new BorderLayout());

        TitleBar titleBar = new TitleBar();
        titleBar.setLayout(new BorderLayout(8, 0));
        titleBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
        titleLabel.setFont(UiFonts.ui(Font.BOLD, UiFonts.LABEL_SIZE));
        titleBar.add(titleLabel, BorderLayout.WEST);
        actionArea.setOpaque(false);
        titleBar.add(actionArea, BorderLayout.EAST);
        add(titleBar, BorderLayout.NORTH);

        terminalBody.setOpaque(false);
        terminalBody.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(terminalBody, BorderLayout.CENTER);
        applyTheme();
        tm.addListener(this::applyTheme);
    }

    public TerminalFrame(JComponent terminal, ThemeManager tm) {
        this(tm);
        setTerminal(terminal);
    }

    public void setTitle(String title) {
        titleLabel.setText(Objects.requireNonNull(title, "title"));
        repaint();
    }

    public void setTerminal(JComponent terminal) {
        terminalBody.removeAll();
        terminalBody.add(Objects.requireNonNull(terminal, "terminal"), BorderLayout.CENTER);
        terminalBody.revalidate();
        terminalBody.repaint();
    }

    /** 右侧按钮位由接线层填充，可放置复制、缩放等操作。 */
    public JPanel actionArea() {
        return actionArea;
    }

    private void applyTheme() {
        titleLabel.setForeground(tm.theme().textPrimary);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        UiTheme theme = tm.theme();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(theme.terminalBg);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), ARC, ARC);
            g.setColor(theme.border);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f,
                    getWidth() - 1f, getHeight() - 1f, ARC, ARC));
        } finally {
            g.dispose();
        }
    }

    private final class TitleBar extends JPanel {
        private static final long serialVersionUID = 1L;

        private TitleBar() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.height = TITLE_HEIGHT;
            return size;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            UiTheme theme = tm.theme();
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0f, 0f, theme.statusBarStart,
                        getWidth(), 0f, theme.statusBarEnd));
                // 把下侧圆角延伸到裁剪区外，仅保留顶部两个圆角。
                g.fill(new RoundRectangle2D.Float(0f, 0f, getWidth(),
                        getHeight() + ARC, ARC, ARC));
                g.setColor(theme.separator);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            } finally {
                g.dispose();
            }
        }
    }
}
