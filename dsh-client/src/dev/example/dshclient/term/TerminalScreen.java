package dev.example.dshclient.term;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 终端屏幕模型：一个可增长的缓冲区，最后 {@code rows} 行就是屏幕，往上滚出去的自动进历史。
 *
 * <p>为什么要自己写而不用 JTextPane：终端是**字符矩阵**，不是富文本文档。用 StyledDocument
 * 存 5000 行 × 每字符一段样式，翻页和重绘会明显卡；而且 {@code \r}、局部擦除、光标定位这些
 * 控制序列在文档模型里没有对应概念，硬套只会越写越歪。这里一条线 = 三个并行数组
 * （字符 / 前景 / 背景 / 属性），重绘时按"同色连续段"合并成一次 drawString。
 *
 * <p>颜色不存 Color 对象，存**编码值**，由 {@link TerminalPalette} 在绘制时解析 —— 这样切换
 * 暗/亮主题时，终端里已经打出来的彩色输出会跟着换一套配色，而不是留在旧主题的颜色上。
 */
public final class TerminalScreen {

    /** 默认前景/背景：跟随当前主题，不写死。 */
    static final int DEFAULT = -1;
    /** 属性位。 */
    static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, REVERSE = 16;

    /** 超过这个行数就从最老的开始丢（内存上限约 5000×200×13B ≈ 13MB）。 */
    private static final int MAX_LINES = 5000;

    /** 一行：字符 + 前景色编码 + 背景色编码 + 属性位。 */
    public static final class Line {
        char[] ch;
        int[] fg;
        int[] bg;
        byte[] at;
        int len;

        Line(int cols) {
            alloc(cols);
        }

        private void alloc(int cols) {
            if (ch != null && ch.length >= cols) {
                return;
            }
            int n = Math.max(cols, 16);
            ch = new char[n];
            fg = new int[n];
            bg = new int[n];
            at = new byte[n];
            len = n;
            clear(DEFAULT, DEFAULT);
        }

        void clear(int fgColor, int bgColor) {
            for (int i = 0; i < len; i++) {
                ch[i] = ' ';
                fg[i] = fgColor;
                bg[i] = bgColor;
                at[i] = 0;
            }
        }

        void clearRange(int from, int to, int fgColor, int bgColor) {
            int a = Math.max(0, from);
            int b = Math.min(len - 1, to);
            for (int i = a; i <= b; i++) {
                ch[i] = ' ';
                fg[i] = fgColor;
                bg[i] = bgColor;
                at[i] = 0;
            }
        }

        void set(int x, char c, int fgColor, int bgColor, int attr) {
            ch[x] = c;
            fg[x] = fgColor;
            bg[x] = bgColor;
            at[x] = (byte) attr;
        }

        /** 缩放到新宽度：变宽补空格，变窄截断（溢出的内容不折行 —— 折行由 putChar 负责）。 */
        void resize(int cols) {
            if (ch == null || ch.length < cols) {
                int oldLen = ch == null ? 0 : len;
                char[] oc = ch;
                int[] of = fg;
                int[] ob = bg;
                byte[] oa = at;
                alloc(cols);
                for (int i = 0; i < oldLen && i < len; i++) {
                    ch[i] = oc[i];
                    fg[i] = of[i];
                    bg[i] = ob[i];
                    at[i] = oa[i];
                }
            } else {
                len = cols;
            }
        }
    }

    private final List<Line> buf = new ArrayList<>();
    private int cols;
    private int rows;
    /** 屏幕上第一行在 buf 里的下标。 */
    private int top;

    private int cx;
    private int cy;
    private int curFg = DEFAULT;
    private int curBg = DEFAULT;
    private int curAttr;
    /** 右边界写完最后一个字符后"待折行"，等下一个可打印字符再真正换行。 */
    private boolean wrapPending;

    private int regionTop;
    private int regionBottom;

    /**
     * 一个 CJK 字符占几列。Windows 上 Consolas + 雅黑回退时是 2 列；
     * 某些 Linux/字体族里 CJK 只按 1 个等宽前进，这时硬按 2 列就会出现"字之间有空档"。
     * 由 TerminalPanel 量过字体后设置。
     */
    private int wideCols = 2;

    private int savedX;
    private int savedY;
    private int savedFg = DEFAULT;
    private int savedBg = DEFAULT;
    private int savedAttr;

    // ---- 解析状态机 ----
    private static final int GROUND = 0, ESC = 1, CSI = 2, OSC = 3, OSC_ESC = 4;
    private int state = GROUND;
    private final StringBuilder csi = new StringBuilder(32);
    private final StringBuilder osc = new StringBuilder(64);
    private ByteBuffer rawIn = ByteBuffer.allocate(1 << 16);
    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);

    public TerminalScreen(int cols, int rows) {
        this.cols = Math.max(2, cols);
        this.rows = Math.max(2, rows);
        this.regionBottom = this.rows - 1;
        for (int i = 0; i < this.rows; i++) {
            buf.add(new Line(this.cols));
        }
    }

    public int cols() {
        return cols;
    }

    public int rows() {
        return rows;
    }

    public int cursorX() {
        return cx;
    }

    public int cursorY() {
        return cy;
    }

    /** 历史行数（= 屏幕上方可回看多少行）。 */
    public int historySize() {
        return top;
    }

    /**
     * 取屏幕坐标 y 的行；{@code y} 可以是负数，表示回看历史（-1 = 屏幕上方的第一行）。
     * 越界返回 null。
     */
    public Line lineAt(int y) {
        int idx = top + y;
        if (idx < 0 || idx >= buf.size()) {
            return null;
        }
        return buf.get(idx);
    }

    /** 回看时把光标藏起来（历史区不画光标）。 */
    boolean cursorVisible(int scrollOffset) {
        return scrollOffset == 0;
    }

    public synchronized void resize(int newCols, int newRows) {
        int c = Math.max(2, newCols);
        int r = Math.max(2, newRows);
        if (c == cols && r == rows) {
            return;
        }
        for (Line l : buf) {
            l.resize(c);
        }
        cols = c;
        rows = r;
        regionTop = 0;
        regionBottom = rows - 1;
        while (buf.size() < top + rows) {
            buf.add(new Line(cols));
        }
        while (buf.size() > MAX_LINES + rows) {
            buf.remove(0);
            top = Math.max(0, top - 1);
        }
        cx = Math.min(cx, cols - 1);
        cy = Math.min(cy, rows - 1);
        wrapPending = false;
    }

    /** 清屏 + 清历史（Ctrl+L 那种"全清"，不是 shell 自己的 clear）。 */
    public synchronized void clearAll() {
        // 原地清空：**不重建 Line 对象**。
        // 之前是 buf.clear() 后重新 new，若外部（面板/滚动条/选择逻辑）持有旧 Line 引用，
        // 之后所有输出都写进"孤儿"对象，屏幕永远空白——表现出来就是"清屏后终端死了"。
        top = 0;
        while (buf.size() > rows) {
            buf.remove(buf.size() - 1);
        }
        while (buf.size() < rows) {
            buf.add(new Line(cols));
        }
        for (Line l : buf) {
            if (l.len < cols) {
                l.alloc(cols);
            }
            l.clear(DEFAULT, DEFAULT);
        }
        cx = 0;
        cy = 0;
        wrapPending = false;
        curFg = DEFAULT;
        curBg = DEFAULT;
        curAttr = 0;
        regionTop = 0;
        regionBottom = rows - 1;
    }

    synchronized void clearScrollback() {
        if (top <= 0) {
            return;
        }
        for (int i = 0; i < top; i++) {
            buf.remove(0);
        }
        top = 0;
    }

    // ---------------------------------------------------------------- 输入解析

    /** 喂一段字节（可能是半个 UTF-8 字符，跨包也能正确拼）。 */
    public synchronized void feed(byte[] data, int len) {
        if (len <= 0) {
            return;
        }
        if (rawIn.remaining() < len) {
            ByteBuffer bigger = ByteBuffer.allocate(Math.max(rawIn.capacity() * 2, rawIn.position() + len + 64));
            rawIn.flip();
            bigger.put(rawIn);
            rawIn.clear();
            rawIn = bigger;
        }
        rawIn.put(data, 0, len);
        rawIn.flip();
        CharBuffer out = CharBuffer.allocate(rawIn.remaining() + 8);
        try {
            decoder.decode(rawIn, out, false);
        } catch (Exception ignore) {
            // 解码失败就丢掉这一批，不能把整个终端卡死
            rawIn.clear();
            return;
        }
        rawIn.compact();
        out.flip();
        while (out.hasRemaining()) {
            consume(out.get());
        }
    }

    private void consume(char c) {
        switch (state) {
            case ESC:
                escChar(c);
                return;
            case CSI:
                if (c >= 0x40 && c <= 0x7e) {
                    csiFinal(c);
                    state = GROUND;
                } else if (csi.length() < 200) {
                    csi.append(c);
                }
                return;
            case OSC:
                if (c == 0x07) {
                    state = GROUND;
                } else if (c == 0x1b) {
                    state = OSC_ESC;
                } else if (osc.length() < 4000) {
                    osc.append(c);
                }
                return;
            case OSC_ESC:
                state = c == '\\' ? GROUND : ESC;
                return;
            default:
                break;
        }
        if (c == 0x1b) {
            state = ESC;
            csi.setLength(0);
        } else if (c == '\r') {
            cx = 0;
            wrapPending = false;
        } else if (c == '\n') {
            lineFeed();
            wrapPending = false;
        } else if (c == 0x08) {
            if (cx > 0) {
                cx--;
            }
            wrapPending = false;
        } else if (c == '\t') {
            int next = ((cx / 8) + 1) * 8;
            cx = Math.min(cols - 1, next);
            wrapPending = false;
        } else if (c == 0x07) {
            // 响铃：静音（远程跑命令时响个不停比不响更烦）
        } else if (c >= 0x20) {
            putChar(c);
        }
    }

    private void escChar(char c) {
        switch (c) {
            case '[':
                state = CSI;
                csi.setLength(0);
                return;
            case ']':
                state = OSC;
                osc.setLength(0);
                return;
            case '(':
            case ')':
            case '*':
            case '+':
                // 字符集选择：吃掉下一个字符后回 GROUND
                state = GROUND;
                return;
            case '7':
                saveCursor();
                state = GROUND;
                return;
            case '8':
                restoreCursor();
                state = GROUND;
                return;
            case 'c':
                hardReset();
                state = GROUND;
                return;
            default:
                state = GROUND;
        }
    }

    private void putChar(char c) {
        if (wrapPending) {
            // 上一行第 cols-1 列刚写满：真正换行发生在**下一个字符到来时**（延迟折行）
            wrapPending = false;
            cx = 0;
            lineFeed();
        }
        boolean wide = wideCols > 1 && isWide(c);
        if (wide && cx == cols - 1) {
            // 双宽字符不能劈成两半：行尾只剩一格时先折行（否则会和下一行首字叠在一起）
            cx = 0;
            lineFeed();
        }
        Line l = lineAt(cy);
        if (l != null) {
            l.set(cx, c, curFg, curBg, curAttr);
            if (wide && cx + 1 < cols) {
                // 第二格做成"占位"：真正绘制由宽字符自己跨过去，这里只保住列对齐
                l.set(cx + 1, '\0', curFg, curBg, curAttr);
            }
        }
        int next = cx + (wide ? 2 : 1);
        if (next >= cols) {
            cx = cols - 1;
            wrapPending = true;
        } else {
            cx = next;
        }
    }

    public synchronized int wideCols() {
        return wideCols;
    }

    /** 由界面按实际字体的 CJK 宽度设置（1 或 2）。 */
    public synchronized void setWideCols(int cols) {
        this.wideCols = cols >= 2 ? 2 : 1;
    }

    /** CJK / 全角标点 / emoji 这些在终端里占两格。 */
    static boolean isWide(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0x303E)
                || (c >= 0x3041 && c <= 0x33FF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xA000 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE4F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    private void lineFeed() {
        if (cy == regionBottom) {
            scrollUp(1);
        } else if (cy < rows - 1) {
            cy++;
        } else {
            // 光标已经贴在最下面（例如 DECSTBM 之外的换行）：整体上推
            scrollUp(1);
        }
    }

    private void scrollUp(int n) {
        for (int k = 0; k < n; k++) {
            if (regionTop == 0 && regionBottom == rows - 1) {
                // 全屏区域：把最上面那一行交给历史，缓冲区尾部补一行新的
                top++;
                while (buf.size() < top + rows) {
                    buf.add(new Line(cols));
                }
                while (buf.size() > MAX_LINES + rows) {
                    buf.remove(0);
                    top--;
                }
            } else {
                for (int y = regionTop; y < regionBottom; y++) {
                    copyLine(lineAt(y), lineAt(y + 1));
                }
                Line last = lineAt(regionBottom);
                if (last != null) {
                    last.clear(curFg, curBg);
                }
            }
        }
    }

    private void scrollDown(int n) {
        for (int k = 0; k < n; k++) {
            for (int y = regionBottom; y > regionTop; y--) {
                copyLine(lineAt(y), lineAt(y - 1));
            }
            Line first = lineAt(regionTop);
            if (first != null) {
                first.clear(curFg, curBg);
            }
        }
    }

    private static void copyLine(Line dst, Line src) {
        if (dst == null || src == null) {
            return;
        }
        int n = Math.min(dst.len, src.len);
        System.arraycopy(src.ch, 0, dst.ch, 0, n);
        System.arraycopy(src.fg, 0, dst.fg, 0, n);
        System.arraycopy(src.bg, 0, dst.bg, 0, n);
        System.arraycopy(src.at, 0, dst.at, 0, n);
    }

    // ---------------------------------------------------------------- CSI

    /** 带 '?' / '>' 前缀的私有序号（DEC 私有模式），这里只关心少数几个。 */
    private void csiFinal(char finalByte) {
        String s = csi.toString();
        boolean priv = s.startsWith("?") || s.startsWith(">") || s.startsWith("=");
        if (priv) {
            s = s.substring(1);
        }
        int[] p = parseInts(s);
        int n = p.length > 0 ? Math.max(1, p[0]) : 1;
        switch (finalByte) {
            case 'A':
                cy = Math.max(regionTop, cy - n);
                break;
            case 'B':
                cy = Math.min(regionBottom, cy + n);
                break;
            case 'C':
                cx = Math.min(cols - 1, cx + n);
                wrapPending = false;
                break;
            case 'D':
                cx = Math.max(0, cx - n);
                wrapPending = false;
                break;
            case 'E':
                cx = 0;
                cy = Math.min(regionBottom, cy + n);
                break;
            case 'F':
                cx = 0;
                cy = Math.max(regionTop, cy - n);
                break;
            case 'G':
                cx = clamp(n - 1, 0, cols - 1);
                break;
            case 'd':
                cy = clamp(n - 1, regionTop, regionBottom);
                break;
            case 'H':
            case 'f': {
                int row = p.length > 0 && p[0] > 0 ? p[0] - 1 : 0;
                int col = p.length > 1 && p[1] > 0 ? p[1] - 1 : 0;
                cy = clamp(row, regionTop, regionBottom);
                cx = clamp(col, 0, cols - 1);
                wrapPending = false;
                break;
            }
            case 'J':
                eraseDisplay(p.length > 0 ? p[0] : 0);
                break;
            case 'K':
                eraseLine(p.length > 0 ? p[0] : 0);
                break;
            case 'X': {
                Line l = lineAt(cy);
                if (l != null) {
                    l.clearRange(cx, cx + n - 1, curFg, curBg);
                }
                break;
            }
            case 'P': {
                Line l = lineAt(cy);
                if (l != null) {
                    shiftLeft(l, cx, n);
                }
                break;
            }
            case '@': {
                Line l = lineAt(cy);
                if (l != null) {
                    shiftRight(l, cx, n);
                }
                break;
            }
            case 'L':
                insertLines(n);
                break;
            case 'M':
                deleteLines(n);
                break;
            case 'S':
                scrollUp(n);
                break;
            case 'T':
                scrollDown(n);
                break;
            case 'm':
                sgr(p);
                break;
            case 'r': {
                int t = p.length > 0 && p[0] > 0 ? p[0] - 1 : 0;
                int b = p.length > 1 && p[1] > 0 ? p[1] - 1 : rows - 1;
                if (b > t) {
                    regionTop = clamp(t, 0, rows - 1);
                    regionBottom = clamp(b, regionTop + 1, rows - 1);
                    cx = 0;
                    cy = regionTop;
                }
                break;
            }
            case 's':
                saveCursor();
                break;
            case 'u':
                restoreCursor();
                break;
            case 'h':
            case 'l':
                // 私有模式（含 ?1049 备用屏 / 光标可见性）：不影响这里的字符矩阵，忽略
                break;
            default:
                break;
        }
    }

    private void insertLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int y = regionBottom; y > cy; y--) {
                copyLine(lineAt(y), lineAt(y - 1));
            }
            Line l = lineAt(cy);
            if (l != null) {
                l.clear(curFg, curBg);
            }
        }
    }

    private void deleteLines(int n) {
        for (int k = 0; k < n; k++) {
            for (int y = cy; y < regionBottom; y++) {
                copyLine(lineAt(y), lineAt(y + 1));
            }
            Line l = lineAt(regionBottom);
            if (l != null) {
                l.clear(curFg, curBg);
            }
        }
    }

    private void shiftLeft(Line l, int from, int n) {
        int cnt = Math.min(n, l.len - from);
        for (int i = from; i < l.len - cnt; i++) {
            l.ch[i] = l.ch[i + cnt];
            l.fg[i] = l.fg[i + cnt];
            l.bg[i] = l.bg[i + cnt];
            l.at[i] = l.at[i + cnt];
        }
        l.clearRange(l.len - cnt, l.len - 1, curFg, curBg);
    }

    private void shiftRight(Line l, int from, int n) {
        int cnt = Math.min(n, l.len - from);
        for (int i = l.len - 1; i >= from + cnt; i--) {
            l.ch[i] = l.ch[i - cnt];
            l.fg[i] = l.fg[i - cnt];
            l.bg[i] = l.bg[i - cnt];
            l.at[i] = l.at[i - cnt];
        }
        l.clearRange(from, from + cnt - 1, curFg, curBg);
    }

    private void eraseDisplay(int mode) {
        if (mode == 2 || mode == 3) {
            for (int y = 0; y < rows; y++) {
                Line l = lineAt(y);
                if (l != null) {
                    l.clear(curFg, curBg);
                }
            }
            if (mode == 3) {
                clearScrollback();
            }
            return;
        }
        if (mode == 1) {
            for (int y = 0; y < cy; y++) {
                Line l = lineAt(y);
                if (l != null) {
                    l.clear(curFg, curBg);
                }
            }
            Line cur = lineAt(cy);
            if (cur != null) {
                cur.clearRange(0, cx, curFg, curBg);
            }
            return;
        }
        Line cur = lineAt(cy);
        if (cur != null) {
            cur.clearRange(cx, cols - 1, curFg, curBg);
        }
        for (int y = cy + 1; y < rows; y++) {
            Line l = lineAt(y);
            if (l != null) {
                l.clear(curFg, curBg);
            }
        }
    }

    private void eraseLine(int mode) {
        Line l = lineAt(cy);
        if (l == null) {
            return;
        }
        if (mode == 1) {
            l.clearRange(0, cx, curFg, curBg);
        } else if (mode == 2) {
            l.clear(curFg, curBg);
        } else {
            l.clearRange(cx, cols - 1, curFg, curBg);
        }
    }

    private void sgr(int[] p) {
        if (p.length == 0) {
            p = new int[]{0};
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            if (v == 0) {
                curFg = DEFAULT;
                curBg = DEFAULT;
                curAttr = 0;
            } else if (v == 1) {
                curAttr |= BOLD;
            } else if (v == 2) {
                curAttr |= DIM;
            } else if (v == 3) {
                curAttr |= ITALIC;
            } else if (v == 4) {
                curAttr |= UNDERLINE;
            } else if (v == 7) {
                curAttr |= REVERSE;
            } else if (v == 22) {
                curAttr &= ~(BOLD | DIM);
            } else if (v == 23) {
                curAttr &= ~ITALIC;
            } else if (v == 24) {
                curAttr &= ~UNDERLINE;
            } else if (v == 27) {
                curAttr &= ~REVERSE;
            } else if (v >= 30 && v <= 37) {
                curFg = v - 30;
            } else if (v == 39) {
                curFg = DEFAULT;
            } else if (v >= 40 && v <= 47) {
                curBg = v - 40;
            } else if (v == 49) {
                curBg = DEFAULT;
            } else if (v >= 90 && v <= 97) {
                curFg = v - 90 + 8;
            } else if (v >= 100 && v <= 107) {
                curBg = v - 100 + 8;
            } else if (v == 38 || v == 48) {
                int mode = i + 1 < p.length ? p[i + 1] : -1;
                if (mode == 5 && i + 2 < p.length) {
                    int idx = clamp(p[i + 2], 0, 255);
                    if (v == 38) {
                        curFg = idx;
                    } else {
                        curBg = idx;
                    }
                    i += 2;
                } else if (mode == 2 && i + 4 < p.length) {
                    int rgb = ((clamp(p[i + 2], 0, 255) << 16)
                            | (clamp(p[i + 3], 0, 255) << 8)
                            | clamp(p[i + 4], 0, 255)) | 0x0100_0000;
                    if (v == 38) {
                        curFg = rgb;
                    } else {
                        curBg = rgb;
                    }
                    i += 4;
                }
            }
        }
    }

    private void saveCursor() {
        savedX = cx;
        savedY = cy;
        savedFg = curFg;
        savedBg = curBg;
        savedAttr = curAttr;
    }

    private void restoreCursor() {
        cx = clamp(savedX, 0, cols - 1);
        cy = clamp(savedY, 0, rows - 1);
        curFg = savedFg;
        curBg = savedBg;
        curAttr = savedAttr;
    }

    private void hardReset() {
        curFg = DEFAULT;
        curBg = DEFAULT;
        curAttr = 0;
        cx = 0;
        cy = 0;
        regionTop = 0;
        regionBottom = rows - 1;
        wrapPending = false;
        for (Line l : buf) {
            l.clear(DEFAULT, DEFAULT);
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 取某屏行（或历史行）的纯文本，给复制用。 */
    public static String textOf(Line l, int maxCols) {
        if (l == null) {
            return "";
        }
        int end = Math.min(maxCols, l.len);
        StringBuilder sb = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            if (l.ch[i] != '\0') {
                sb.append(l.ch[i]);   // 双宽字符的第二格是占位，不重复输出
            }
        }
        int cut = sb.length();
        while (cut > 0 && sb.charAt(cut - 1) == ' ') {
            cut--;
        }
        return sb.substring(0, cut);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 解析 "1;2;3" 这种参数串；空段当 0。 */
    private static int[] parseInts(String s) {
        if (s == null || s.isEmpty()) {
            return new int[0];
        }
        String[] parts = s.split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
