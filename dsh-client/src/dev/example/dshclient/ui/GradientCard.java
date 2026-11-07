package dev.example.dshclient.ui;

import java.awt.BorderLayout;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/** 参数分组卡片：12px 圆角与内边距，标题和内容都可由接线层直接填充。 */
public final class GradientCard extends GradientPanel {
    private static final long serialVersionUID = 1L;

    private final transient ThemeManager tm;
    private final JPanel content = new JPanel(new BorderLayout());

    public GradientCard(ThemeManager tm) {
        this(null, tm);
    }

    public GradientCard(String title, ThemeManager tm) {
        super(theme(tm).cardStart, theme(tm).cardEnd, 0f, 12,
                theme(tm).border, true);
        this.tm = tm;
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.setOpaque(false);
        if (title != null) {
            add(new SectionHeader(title, tm), BorderLayout.NORTH);
        }
        add(content, BorderLayout.CENTER);
        tm.addListener(this::applyTheme);
    }

    public JPanel content() {
        return content;
    }

    private void applyTheme() {
        UiTheme theme = tm.theme();
        setGradient(theme.cardStart, theme.cardEnd);
        setBorderColor(theme.border);
    }

    private static UiTheme theme(ThemeManager tm) {
        return Objects.requireNonNull(tm, "tm").theme();
    }
}
