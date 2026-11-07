package dev.example.dshclient.ui;

import java.awt.Font;
import java.awt.GraphicsEnvironment;

/**
 * 集中字体阶梯。
 *
 * <p><b>为什么不能直接写 "Segoe UI" / "Consolas"</b>：这两个是**物理字体**，本身不含汉字，
 * 而 Java 只对**逻辑字体**（Dialog / SansSerif / Monospaced）做逐字回退。用物理字体画中文，
 * Windows 上会得到一片"□"（2.11.0 在用户机器上就是这个现象）。
 *
 * <p>策略：UI 优先选**自带汉字的物理字体**（微软雅黑），选不到再退回逻辑字体（它自己会拼
 * Segoe UI + 雅黑的回退）；终端保留 Consolas 画 ASCII，汉字另用 {@link #cjk(float)} 单独绘制。
 */
public final class UiFonts {

    /** UI 字体：必须能显示汉字，否则整窗都是豆腐块。 */
    public static final String UI_FAMILY = pick(new String[]{
            "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
            "Noto Sans CJK SC", "Source Han Sans SC", "PingFang SC",
            "WenQuanYi Micro Hei", "DengXian", "等线", "SimSun", "宋体",
            "Segoe UI"
    }, true);

    /** 等宽字体：只保证 ASCII 等宽（终端里的汉字由 {@link #CJK_FAMILY} 单独画）。 */
    public static final String MONO_FAMILY = pick(new String[]{
            "Consolas", "Cascadia Mono", "Cascadia Code",
            "DejaVu Sans Mono", "Noto Sans Mono", "Monospaced"
    }, false);

    /** 终端里画汉字用的字体（含 CJK）。 */
    public static final String CJK_FAMILY = pick(new String[]{
            "Microsoft YaHei UI", "Microsoft YaHei", "微软雅黑",
            "SimSun", "宋体", "Noto Sans CJK SC", "Source Han Sans SC",
            "WenQuanYi Micro Hei", "Dialog"
    }, true);

    public static final float WINDOW_TITLE_SIZE = 20f;
    public static final float SECTION_TITLE_SIZE = 11f;
    public static final float LABEL_SIZE = 13f;
    public static final float BODY_SIZE = 13f;
    public static final float NOTE_SIZE = 12f;
    public static final float TERMINAL_SIZE = 13f;

    private UiFonts() {
    }

    public static Font ui(int style, float size) {
        return font(UI_FAMILY, style, size);
    }

    public static Font mono(float size) {
        return font(MONO_FAMILY, Font.PLAIN, size);
    }

    /** 终端里的汉字字体（同字号）。 */
    public static Font cjk(float size) {
        return font(CJK_FAMILY, Font.PLAIN, size);
    }

    private static Font font(String family, int style, float size) {
        // deriveFont 保留小数字号，便于高 DPI 下统一缩放。
        return new Font(family, style, 1).deriveFont(size);
    }

    /**
     * 在候选里挑第一个"装了、且（需要时）能显示汉字"的家族。
     * 逻辑字体（Dialog/SansSerif/Monospaced）永远可选 —— 它们会自己做字形回退。
     */
    private static String pick(String[] candidates, boolean needCjk) {
        String[] installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        for (String candidate : candidates) {
            if (isLogical(candidate)) {
                if (!needCjk || new Font(candidate, Font.PLAIN, 12).canDisplay('\u4E2D')) {
                    return candidate;
                }
                continue;
            }
            for (String available : installed) {
                if (!available.equalsIgnoreCase(candidate)) {
                    continue;
                }
                Font f = new Font(available, Font.PLAIN, 12);
                if (!needCjk || f.canDisplay('\u4E2D')) {
                    return available;
                }
            }
        }
        return needCjk ? "Dialog" : "Monospaced";
    }

    private static boolean isLogical(String family) {
        return "Dialog".equals(family) || "SansSerif".equals(family)
                || "Serif".equals(family) || "Monospaced".equals(family);
    }
}
