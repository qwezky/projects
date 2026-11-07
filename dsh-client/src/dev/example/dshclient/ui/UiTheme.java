package dev.example.dshclient.ui;

import java.awt.Color;

/** 不可变颜色令牌；过渡帧也是独立实例，避免组件读到不一致的主题。 */
public final class UiTheme {
    private static final int ANSI_COUNT = 16;

    public final Color windowBg;
    public final Color panelBg;
    public final Color inputBg;
    public final Color inputFocusBg;
    public final Color terminalBg;
    public final Color separator;
    public final Color border;
    public final Color textPrimary;
    public final Color textSecondary;
    public final Color textDisabled;
    public final Color accent;
    public final Color accentHover;
    public final Color accentPressed;
    public final Color primaryText;
    public final Color focusRing;
    public final Color success;
    public final Color warning;
    public final Color danger;
    public final Color info;
    public final Color selectionBg;
    public final Color selectionFg;
    public final Color controlHover;
    public final Color controlPressed;
    public final Color scrollTrack;
    public final Color scrollThumb;
    public final Color scrollThumbHover;
    public final Color terminalCursor;
    public final Color terminalSelection;
    public final Color statusBarStart;
    public final Color statusBarEnd;
    public final Color cardStart;
    public final Color cardEnd;

    private final Color[] ansiColors;

    private UiTheme(
            Color windowBg, Color panelBg, Color inputBg, Color terminalBg,
            Color separator, Color border, Color textPrimary, Color textSecondary,
            Color textDisabled, Color accent, Color accentHover, Color accentPressed,
            Color primaryText, Color focusRing, Color success, Color warning,
            Color danger, Color info, Color selectionBg, Color selectionFg,
            Color controlHover, Color controlPressed, Color scrollTrack,
            Color scrollThumb, Color scrollThumbHover, Color terminalCursor,
            Color terminalSelection, Color statusBarStart, Color statusBarEnd,
            Color cardStart, Color cardEnd, Color inputFocusBg, Color[] ansiColors) {
        this.windowBg = windowBg;
        this.panelBg = panelBg;
        this.inputBg = inputBg;
        this.terminalBg = terminalBg;
        this.separator = separator;
        this.border = border;
        this.textPrimary = textPrimary;
        this.textSecondary = textSecondary;
        this.textDisabled = textDisabled;
        this.accent = accent;
        this.accentHover = accentHover;
        this.accentPressed = accentPressed;
        this.primaryText = primaryText;
        this.focusRing = focusRing;
        this.success = success;
        this.warning = warning;
        this.danger = danger;
        this.info = info;
        this.selectionBg = selectionBg;
        this.selectionFg = selectionFg;
        this.controlHover = controlHover;
        this.controlPressed = controlPressed;
        this.scrollTrack = scrollTrack;
        this.scrollThumb = scrollThumb;
        this.scrollThumbHover = scrollThumbHover;
        this.terminalCursor = terminalCursor;
        this.terminalSelection = terminalSelection;
        this.statusBarStart = statusBarStart;
        this.statusBarEnd = statusBarEnd;
        this.cardStart = cardStart;
        this.cardEnd = cardEnd;
        this.inputFocusBg = inputFocusBg;
        this.ansiColors = ansiColors.clone();
    }

    public static UiTheme dark() {
        return DarkHolder.INSTANCE;
    }

    public static UiTheme light() {
        return LightHolder.INSTANCE;
    }

    /** 返回 ANSI 标准色 0–7 或 bright 色 8–15。 */
    public Color ansi(int i) {
        if (i < 0 || i >= ANSI_COUNT) {
            throw new IndexOutOfBoundsException("ANSI 色索引必须在 0 到 15 之间");
        }
        return ansiColors[i];
    }

    public Color[] ansiColors() {
        return ansiColors.clone();
    }

    /** 线性插值全部可见颜色，包括渐变端点和 ANSI 色板。 */
    public static UiTheme interpolate(UiTheme from, UiTheme to, float progress) {
        float t = Math.max(0f, Math.min(1f, progress));
        Color[] ansi = new Color[ANSI_COUNT];
        for (int i = 0; i < ANSI_COUNT; i++) {
            ansi[i] = mix(from.ansiColors[i], to.ansiColors[i], t);
        }
        return new UiTheme(
                mix(from.windowBg, to.windowBg, t), mix(from.panelBg, to.panelBg, t),
                mix(from.inputBg, to.inputBg, t), mix(from.terminalBg, to.terminalBg, t),
                mix(from.separator, to.separator, t), mix(from.border, to.border, t),
                mix(from.textPrimary, to.textPrimary, t), mix(from.textSecondary, to.textSecondary, t),
                mix(from.textDisabled, to.textDisabled, t), mix(from.accent, to.accent, t),
                mix(from.accentHover, to.accentHover, t), mix(from.accentPressed, to.accentPressed, t),
                mix(from.primaryText, to.primaryText, t), mix(from.focusRing, to.focusRing, t),
                mix(from.success, to.success, t), mix(from.warning, to.warning, t),
                mix(from.danger, to.danger, t), mix(from.info, to.info, t),
                mix(from.selectionBg, to.selectionBg, t), mix(from.selectionFg, to.selectionFg, t),
                mix(from.controlHover, to.controlHover, t), mix(from.controlPressed, to.controlPressed, t),
                mix(from.scrollTrack, to.scrollTrack, t), mix(from.scrollThumb, to.scrollThumb, t),
                mix(from.scrollThumbHover, to.scrollThumbHover, t),
                mix(from.terminalCursor, to.terminalCursor, t),
                mix(from.terminalSelection, to.terminalSelection, t),
                mix(from.statusBarStart, to.statusBarStart, t),
                mix(from.statusBarEnd, to.statusBarEnd, t),
                mix(from.cardStart, to.cardStart, t), mix(from.cardEnd, to.cardEnd, t),
                mix(from.inputFocusBg, to.inputFocusBg, t), ansi);
    }

    private static Color mix(Color from, Color to, float t) {
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t),
                Math.round(from.getAlpha() + (to.getAlpha() - from.getAlpha()) * t));
    }

    private static UiTheme createDark() {
        Color[] ansi = {
                new Color(0x0C0C0C), new Color(0xF14C4C), new Color(0x23D18B), new Color(0xE5C07B),
                new Color(0x5CA9F5), new Color(0xD670D6), new Color(0x29B8DB), new Color(0xD4D4D4),
                new Color(0x8A8A8A), new Color(0xFF6B6B), new Color(0x4EC9B0), new Color(0xF5D76E),
                new Color(0x7DB7FF), new Color(0xE698E6), new Color(0x6CD5ED), new Color(0xFFFFFF)
        };
        return new UiTheme(
                new Color(0x1E1E1E), new Color(0x252526), new Color(0x1B1B1B), new Color(0x141414),
                new Color(0x383838), new Color(0x707070), new Color(0xF2F2F2), new Color(0xBDBDBD),
                new Color(0x7C7C7C), new Color(0x2C68AA), new Color(0x2D70B8), new Color(0x285C96),
                new Color(0xFFFFFF), new Color(0x5CA9F5), new Color(0x4EC9B0), new Color(0xE5C07B),
                new Color(0xF26D6D), new Color(0x5CA9F5), new Color(0x264F78), new Color(0xFFFFFF),
                new Color(0x323233), new Color(0x3B3B3D), new Color(0x202020), new Color(0x5C5C5C),
                new Color(0x747474), new Color(0xF2F2F2), new Color(0x264F78),
                new Color(0x1E1E1E), new Color(0x232A3D), new Color(0x252526), new Color(0x2B3450),
                new Color(0x2E2E2F), ansi);
    }

    private static UiTheme createLight() {
        Color[] ansi = {
                new Color(0x202124), new Color(0xA31515), new Color(0x0B6B45), new Color(0x745F00),
                new Color(0x0B57D0), new Color(0x8A238A), new Color(0x00677A), new Color(0x4B4F56),
                new Color(0x6A6F76), new Color(0xB3261E), new Color(0x137333), new Color(0x8A4B00),
                new Color(0x1F5FAF), new Color(0xA32AA3), new Color(0x00758A), new Color(0x000000)
        };
        return new UiTheme(
                new Color(0xFFFFFF), new Color(0xF5F6F8), new Color(0xFFFFFF), new Color(0xFAFAFA),
                new Color(0xE1E4E8), new Color(0x8A919A), new Color(0x202124), new Color(0x5C6370),
                new Color(0x707070), new Color(0x1F5FAF), new Color(0x1A4F91), new Color(0x153F75),
                new Color(0xFFFFFF), new Color(0x1F5FAF), new Color(0x137333), new Color(0x8A4B00),
                new Color(0xB3261E), new Color(0x0B57D0), new Color(0xCCE2FF), new Color(0x202124),
                new Color(0xECEFF3), new Color(0xE0E5EB), new Color(0xF0F1F3), new Color(0x9AA0A6),
                new Color(0x73777D), new Color(0x202124), new Color(0xCCE2FF),
                new Color(0xFFFFFF), new Color(0xEEF3FB), new Color(0xF7F9FC), new Color(0xE3EEFC),
                new Color(0xE9EAEC), ansi);
    }

    private static final class DarkHolder {
        private static final UiTheme INSTANCE = createDark();
    }

    private static final class LightHolder {
        private static final UiTheme INSTANCE = createLight();
    }
}
