package dev.example.dshclient.term;

import java.awt.Color;

import dev.example.dshclient.ui.UiTheme;

/**
 * 终端配色：16 个 ANSI 基本色 + 216 色立方 + 24 级灰阶，外加光标/选区/滚动条。
 *
 * <p>暗亮两套各来一份。16 色不是随便挑的 —— 它们是终端里最常出现的颜色（ls、git、grep、编译错误都靠它），
 * 所以按"深色底上够亮、浅色底上够深"分别调过，而不是把同一组色值反相。
 *
 * <p>{@link TerminalScreen} 里存的是**颜色编号**（0–255）或真彩色（带 0x0100_0000 标记），
 * 由这里的 {@link #fg(int)} / {@link #bg(int)} 在绘制时解析 —— 切主题时整块终端跟着换色。
 */
public final class TerminalPalette {

    final Color background;
    final Color foreground;
    final Color cursor;
    final Color selection;
    final Color scrollThumb;
    /** 0–15：ANSI 基本色。 */
    private final Color[] ansi;

    private TerminalPalette(Color background, Color foreground, Color cursor, Color selection,
                            Color scrollThumb, Color[] ansi) {
        this.background = background;
        this.foreground = foreground;
        this.cursor = cursor;
        this.selection = selection;
        this.scrollThumb = scrollThumb;
        this.ansi = ansi;
    }

    /**
     * 从界面主题（WB 专家产出的 ui.UiTheme）派生终端配色：
     * 底色/光标/选区/滚动条跟界面同一套，ANSI 16 色用主题里校准过的一组。
     */
    public static TerminalPalette of(UiTheme t) {
        Color[] a = new Color[16];
        for (int i = 0; i < 16; i++) {
            a[i] = t.ansi(i);
        }
        return new TerminalPalette(t.terminalBg, t.textPrimary, t.terminalCursor,
                t.terminalSelection, t.scrollThumb, a);
    }

    /** 暗色：接近 VS Code 默认深色，但底色偏中性（不带蓝）。 */
    public static TerminalPalette dark() {
        Color[] a = {
                new Color(0x2B2B2B), new Color(0xF07178), new Color(0x7FD962), new Color(0xE6C07B),
                new Color(0x62A8EA), new Color(0xC792EA), new Color(0x5FD3C4), new Color(0xC8C8C8),
                new Color(0x5A5A5A), new Color(0xFF8A93), new Color(0x9BE38B), new Color(0xF2D89B),
                new Color(0x82BEF5), new Color(0xD9B0F5), new Color(0x8AE6DA), new Color(0xF2F2F2),
        };
        return new TerminalPalette(new Color(0x1B1D22), new Color(0xD6D9E0), new Color(0x7AA2F7),
                new Color(0x2E4A7A), new Color(0x3A3F49), a);
    }

    /** 亮色：纸白底，颜色压暗一档，保证在 #FBFCFD 上看得清。 */
    public static TerminalPalette light() {
        Color[] a = {
                new Color(0x3B3F46), new Color(0xC02A2A), new Color(0x2E7D32), new Color(0x9A6700),
                new Color(0x1F5FA8), new Color(0x8B3AA8), new Color(0x0F7A72), new Color(0x8A8F98),
                new Color(0x6B7078), new Color(0xD93025), new Color(0x1E7B24), new Color(0x7A5200),
                new Color(0x1558A0), new Color(0x7A2E96), new Color(0x0B6B64), new Color(0x24272C),
        };
        return new TerminalPalette(new Color(0xFBFCFD), new Color(0x24272C), new Color(0x1F5FA8),
                new Color(0xC7DCF7), new Color(0xC9CED6), a);
    }

    /** 解析终端里存的前景编码。 */
    public Color fg(int enc) {
        if (enc == TerminalScreen.DEFAULT) {
            return foreground;
        }
        return resolve(enc);
    }

    /** 解析背景编码；默认背景返回主题底色。 */
    public Color bg(int enc) {
        if (enc == TerminalScreen.DEFAULT) {
            return background;
        }
        return resolve(enc);
    }

    private Color resolve(int enc) {
        if (enc < 0) {
            return foreground;
        }
        if (enc < 0x0100_0000) {
            return palette256(enc);
        }
        return new Color(enc & 0xFFFFFF);
    }

    /** 0–15 用调过的基本色；16–231 是 6×6×6 立方；232–255 是灰阶。 */
    Color palette256(int idx) {
        if (idx < 16) {
            return ansi[idx];
        }
        if (idx < 232) {
            int i = idx - 16;
            int r = i / 36;
            int g = (i % 36) / 6;
            int b = i % 6;
            return new Color(step(r), step(g), step(b));
        }
        int v = 8 + (idx - 232) * 10;
        return new Color(v, v, v);
    }

    private static int step(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    Color ansi(int i) {
        return ansi[Math.floorMod(i, 16)];
    }

    /** 供界面做"终端标题条"用：比底色亮/暗一档的过渡色。 */
    Color chrome() {
        return blend(background, foreground, 0.06);
    }

    Color border() {
        return blend(background, foreground, 0.16);
    }

    public static Color blend(Color a, Color b, double t) {
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(clamp(r), clamp(g), clamp(bl));
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
