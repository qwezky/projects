package dev.example.dshclient.term;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 一个自绘的终端控件：字格渲染 + 键盘 / 滚轮 / 选中复制。
 *
 * <p>三条性能约定（远程 shell 一次 burst 可能是几 MB 的 {@code cat}）：
 * <ol>
 *   <li>解析在**读线程**里做（{@link #feed} 只同步模型），EDT 只负责重绘；</li>
 *   <li>重绘用 16ms 合并定时器 —— 一帧最多画一次，不会因为回调太密把 UI 顶死；</li>
 *   <li>绘制按"同底色/同前景色的连续段"合并，一行通常 1–5 次 fillRect + drawString。</li>
 * </ol>
 */
public final class TerminalPanel extends JComponent {

    /** -Ddsh.term.debug=1 时把按键写进控制台，用来判断"键盘到底有没有送达"。 */
    private static final boolean DEBUG_KEYS = "1".equals(System.getProperty("dsh.term.debug"));

    private final TerminalScreen screen;
    private TerminalPalette palette;
    private String monoFamily = "Consolas";
    private int fontSize = 14;

    private Font font;
    private Font boldFont;
    /** 汉字的专用字体：Consolas 没有汉字，拿它画只会得到"□"。 */
    private Font cjkFont;
    private Font cjkBold;
    private FontMetrics metrics;
    private int cellW = 8;
    private int cellH = 16;
    private int ascent = 12;

    /** 向上回看多少行（0 = 贴着最底）。 */
    private int scrollOffset;

    /** 选中：绝对行号（含历史）+ 列。 */
    private int selAnchorLine = -1;
    private int selAnchorCol;
    private int selLine = -1;
    private int selCol;

    private Consumer<byte[]> inputSink;
    private BiConsumer<Integer, Integer> sizeSink;
    private Runnable onScrolled;

    private final Timer repaintTimer;
    private boolean repaintQueued;
    private boolean caretOn = true;
    private final Timer caretTimer;
    private int lastCols = -1;
    private int lastRows = -1;

    public TerminalPanel(TerminalScreen screen, TerminalPalette palette) {
        this.screen = screen;
        this.palette = palette;
        setFocusable(true);
        setOpaque(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
        rebuildFont();
        // 合并重绘：远程输出再猛，一帧也只画一次
        repaintTimer = new Timer(16, (ActionEvent e) -> {
            if (repaintQueued) {
                repaintQueued = false;
                repaint();
            }
        });
        repaintTimer.setRepeats(true);
        repaintTimer.start();
        // 光标闪烁：只在有焦点且贴着最底时闪，回看历史时冻住
        caretTimer = new Timer(560, (ActionEvent e) -> {
            caretOn = !caretOn;
            if (hasFocus() && scrollOffset == 0) {
                repaint();
            }
        });
        caretTimer.start();
        installKeys();
        installMouse();
    }

    // ------------------------------------------------------------ 对外接口

    public void setInputSink(Consumer<byte[]> sink) {
        this.inputSink = sink;
    }

    public void setSizeSink(BiConsumer<Integer, Integer> sink) {
        this.sizeSink = sink;
    }

    void setOnScrolled(Runnable r) {
        this.onScrolled = r;
    }

    public void setPalette(TerminalPalette p) {
        this.palette = p;
        repaint();
    }

    TerminalPalette palette() {
        return palette;
    }

    public void setMonoFamily(String family) {
        if (family != null && !family.isBlank()) {
            this.monoFamily = family;
            rebuildFont();
            revalidate();
            repaint();
        }
    }

    public void setFontSize(int px) {
        int v = Math.max(9, Math.min(28, px));
        if (v != fontSize) {
            fontSize = v;
            rebuildFont();
            revalidate();
            repaint();
        }
    }

    public int fontSize() {
        return fontSize;
    }

    /** 读线程调用：解析 + 排一帧重绘。 */
    public void feed(byte[] data, int len) {
        synchronized (screen) {
            int before = screen.historySize();
            screen.feed(data, len);
            int grew = screen.historySize() - before;
            if (grew > 0 && scrollOffset > 0) {
                // 用户正在回看历史：新行进了历史区，把偏移补上，视口内容才不会跟着跳
                scrollOffset = Math.min(screen.historySize(), scrollOffset + grew);
            }
        }
        queueRepaint();
    }

    /** 本地打印（不走 SSH）：启动横幅、连接失败提示用。 */
    public void printLocal(String text) {
        // 本地文本按 \n 写：终端里 LF 只下移不回车，所以这里补成 \r\n，否则每行会从上行中间开始
        String normalized = text.replace("\r\n", "\n").replace("\n", "\r\n");
        byte[] b = normalized.getBytes(StandardCharsets.UTF_8);
        synchronized (screen) {
            screen.feed(b, b.length);
        }
        scrollOffset = 0;
        repaint();
    }

    public void clear() {
        synchronized (screen) {
            screen.clearAll();
        }
        scrollOffset = 0;
        repaint();
        // 清屏是点按钮触发的，焦点会留在按钮上——不抢回来，之后敲键盘就"没反应"
        SwingUtilities.invokeLater(this::requestFocusInWindow);
    }

    public void scrollToBottom() {
        scrollOffset = 0;
        repaint();
    }

    public int cols() {
        return Math.max(2, screen.cols());
    }

    public int rows() {
        return Math.max(2, screen.rows());
    }

    // ------------------------------------------------------------ 绘制

    @Override
    public void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        try {
            g.setColor(palette.background);
            g.fillRect(0, 0, getWidth(), getHeight());
            syncGridSize();
            paintCells(g);
            paintSelection(g);
            paintCursor(g);
            paintScrollBar(g);
        } finally {
            g.dispose();
        }
    }

    /** 按当前字体和控件尺寸算出 cols×rows，变了就同步给模型（并通知 shell 改 PTY 尺寸）。 */
    private void syncGridSize() {
        int w = getWidth();
        int h = getHeight();
        int cols = Math.max(2, w / Math.max(1, cellW));
        int rows = Math.max(2, h / Math.max(1, cellH));
        if (cols != lastCols || rows != lastRows) {
            lastCols = cols;
            lastRows = rows;
            synchronized (screen) {
                screen.resize(cols, rows);
            }
            if (sizeSink != null) {
                sizeSink.accept(cols, rows);
            }
        }
    }

    private void paintCells(Graphics2D g) {
        int rows = Math.max(1, getHeight() / Math.max(1, cellH));
        int cols = Math.max(1, getWidth() / Math.max(1, cellW));
        g.setFont(font);

        for (int y = 0; y < rows; y++) {
            TerminalScreen.Line line;
            synchronized (screen) {
                line = screen.lineAt(y - scrollOffset);
            }
            if (line == null) {
                continue;
            }
            int baseY = y * cellH;

            // 1) 背景：同色连续段一次 fillRect
            int start = 0;
            int curBg = Integer.MIN_VALUE;
            for (int x = 0; x <= cols; x++) {
                int enc = x < cols ? line.bg[Math.min(x, line.len - 1)] : Integer.MIN_VALUE;
                int attr = x < cols ? line.at[Math.min(x, line.len - 1)] : 0;
                boolean reverse = (attr & TerminalScreen.REVERSE) != 0;
                int eff = reverse ? line.fg[Math.min(x, line.len - 1)] : enc;
                if (x == 0) {
                    curBg = eff;
                    start = 0;
                    continue;
                }
                if (x == cols || eff != curBg) {
                    Color c = palette.bg(curBg);
                    if (!c.equals(palette.background)) {
                        g.setColor(c);
                        g.fillRect(start * cellW, baseY, (x - start) * cellW, cellH);
                    }
                    curBg = eff;
                    start = x;
                }
            }

            // 2) 文字：同前景色 + 同属性的连续段一次 drawString；
            //    双宽字符单独画（它自己跨两格），否则后面的字会被它的宽度顶偏
            int runStart = 0;
            int curFg = Integer.MIN_VALUE;
            int curAttr = Integer.MIN_VALUE;
            StringBuilder run = new StringBuilder(cols + 4);
            for (int x = 0; x <= cols; x++) {
                boolean end = x == cols;
                int idx = end ? 0 : Math.min(x, line.len - 1);
                char ch = end ? 0 : line.ch[idx];
                boolean pad = !end && ch == '\0';
                // 只有纯 ASCII 能安全地"整段 drawString"（等宽字体里前进量正好等于 cellW）；
                // 中文/emoji 由回退字体绘制，宽度跟 cellW 不一定成整数倍，必须单独定位
                boolean wide = !end && !pad && ch > 0x7F;
                int fgEnc = end ? Integer.MIN_VALUE : line.fg[idx];
                int attr = end ? Integer.MIN_VALUE : line.at[idx];
                boolean reverse = !end && (attr & TerminalScreen.REVERSE) != 0;
                int effFg = end ? Integer.MIN_VALUE : (reverse ? line.bg[idx] : fgEnc);
                int effAttr = end ? Integer.MIN_VALUE : (attr & ~TerminalScreen.REVERSE);

                boolean styleChanged = end || pad || wide
                        || effFg != curFg || effAttr != curAttr;
                if (x == 0) {
                    curFg = effFg;
                    curAttr = effAttr;
                    runStart = 0;
                    run.setLength(0);
                } else if (styleChanged) {
                    drawRun(g, run, runStart, baseY, curFg, curAttr == Integer.MIN_VALUE ? 0 : curAttr);
                    run.setLength(0);
                    curFg = effFg;
                    curAttr = effAttr;
                    runStart = x;
                }
                if (end || pad) {
                    continue;
                }
                if (wide) {
                    drawRun(g, run, runStart, baseY, effFg, effAttr == Integer.MIN_VALUE ? 0 : effAttr);
                    run.setLength(0);
                    drawWide(g, ch, x, baseY, effFg, effAttr == Integer.MIN_VALUE ? 0 : effAttr);
                    // 关键：后续文本从"这个字符实际占了几列"之后继续，否则每遇到一个中文就往后错一列
                    runStart = x + Math.max(1, wideColsNow());
                } else if (!styleChanged) {
                    run.append(ch);
                } else {
                    run.append(ch);
                }
            }
        }
    }

    private int wideColsNow() {
        synchronized (screen) {
            return screen.wideCols();
        }
    }

    /** 画一个非 ASCII 字符（按格定位，宽度交给字体自己决定）。 */
    private void drawWide(Graphics2D g, char ch, int col, int baseY, int fgEnc, int attr) {
        Color fg = palette.fg(fgEnc);
        if ((attr & TerminalScreen.DIM) != 0) {
            fg = TerminalPalette.blend(palette.background, fg, 0.65);
        }
        g.setFont((attr & TerminalScreen.BOLD) != 0 ? cjkBold : cjkFont);
        g.setColor(fg);
        g.drawString(String.valueOf(ch), col * cellW, baseY + ascent);
    }

    private void drawRun(Graphics2D g, StringBuilder run, int startCol, int baseY, int fgEnc, int attr) {
        if (run.length() == 0) {
            return;
        }
        Color fg = palette.fg(fgEnc);
        if ((attr & TerminalScreen.DIM) != 0) {
            fg = TerminalPalette.blend(palette.background, fg, 0.65);
        }
        g.setFont((attr & TerminalScreen.BOLD) != 0 ? boldFont : font);
        g.setColor(fg);
        int x = startCol * cellW;
        int y = baseY + ascent;
        g.drawString(run.toString(), x, y);
        if ((attr & TerminalScreen.UNDERLINE) != 0) {
            g.setColor(fg);
            g.drawLine(x, y + 2, x + run.length() * cellW, y + 2);
        }
    }

    private void paintSelection(Graphics2D g) {
        if (selAnchorLine < 0 || selLine < 0 || selAnchorLine == selLine && selAnchorCol == selCol) {
            return;
        }
        int loLine = Math.min(selAnchorLine, selLine);
        int hiLine = Math.max(selAnchorLine, selLine);
        int loCol;
        int hiCol;
        if (selAnchorLine <= selLine) {
            loCol = selAnchorCol;
            hiCol = selCol;
        } else {
            loCol = selCol;
            hiCol = selAnchorCol;
        }
        int topLine = lineIndexOfTop();
        int cols = Math.max(1, getWidth() / Math.max(1, cellW));
        g.setColor(palette.selection);
        for (int line = loLine; line <= hiLine; line++) {
            int y = line - topLine;
            if (y < 0 || y * cellH > getHeight()) {
                continue;
            }
            int from = line == loLine ? loCol : 0;
            int to = line == hiLine ? hiCol : cols;
            from = Math.max(0, Math.min(cols, from));
            to = Math.max(0, Math.min(cols, to));
            if (to > from) {
                g.fillRect(from * cellW, y * cellH, (to - from) * cellW, cellH);
            }
        }
    }

    private void paintCursor(Graphics2D g) {
        if (!hasFocus() || scrollOffset != 0 || !caretOn) {
            return;
        }
        int x;
        int y;
        synchronized (screen) {
            x = screen.cursorX();
            y = screen.cursorY();
        }
        if (x < 0 || y < 0 || x >= getWidth() / cellW || y >= getHeight() / cellH) {
            return;
        }
        g.setColor(palette.cursor);
        g.fillRect(x * cellW, y * cellH, cellW, Math.max(2, cellH - 2));
    }

    private void paintScrollBar(Graphics2D g) {
        int total = 0;
        int hist;
        synchronized (screen) {
            hist = screen.historySize();
            total = hist + screen.rows();
        }
        if (hist <= 0) {
            return;
        }
        int h = getHeight();
        int rows = Math.max(1, h / Math.max(1, cellH));
        double visible = rows / (double) Math.max(rows, total);
        int barH = Math.max(24, (int) Math.round(h * visible));
        double maxOffset = Math.max(1, hist);
        int y = (int) Math.round((h - barH) * (1 - (scrollOffset / maxOffset)));
        g.setColor(palette.scrollThumb);
        g.fillRoundRect(getWidth() - 7, y, 4, barH, 4, 4);
    }

    // ------------------------------------------------------------ 键盘

    /**
     * 处理一次按键（窗口级键盘接管也调它）。
     * 这样即使焦点被工具栏按钮抢走，键盘输入依然能进终端。
     */
    public void handleKeyEvent(KeyEvent e) {
        if (e.getID() == KeyEvent.KEY_PRESSED) {
            handleKeyPressed(e);
        } else if (e.getID() == KeyEvent.KEY_TYPED) {
            handleKeyTyped(e);
        }
    }

    private void installKeys() {
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                handleKeyPressed(e);
            }

            @Override
            public void keyTyped(KeyEvent e) {
                handleKeyTyped(e);
            }
        });
    }

    private void handleKeyPressed(KeyEvent e) {
        {
                int code = e.getKeyCode();
                int mods = e.getModifiersEx();
                boolean ctrl = (mods & InputEvent.CTRL_DOWN_MASK) != 0;
                boolean shift = (mods & InputEvent.SHIFT_DOWN_MASK) != 0;
                boolean alt = (mods & InputEvent.ALT_DOWN_MASK) != 0;

                if (ctrl && shift && code == KeyEvent.VK_C) {
                    copySelection();
                    e.consume();
                    return;
                }
                if (ctrl && shift && code == KeyEvent.VK_V) {
                    paste();
                    e.consume();
                    return;
                }
                if (ctrl && (code == KeyEvent.VK_EQUALS || code == KeyEvent.VK_PLUS || code == KeyEvent.VK_ADD)) {
                    setFontSize(fontSize + 1);
                    e.consume();
                    return;
                }
                if (ctrl && (code == KeyEvent.VK_MINUS || code == KeyEvent.VK_SUBTRACT)) {
                    setFontSize(fontSize - 1);
                    e.consume();
                    return;
                }
                if (code == KeyEvent.VK_PAGE_UP) {
                    scrollBy((int) Math.round(getHeight() / (double) cellH * 0.9));
                    e.consume();
                    return;
                }
                if (code == KeyEvent.VK_PAGE_DOWN) {
                    scrollBy(-(int) Math.round(getHeight() / (double) cellH * 0.9));
                    e.consume();
                    return;
                }
                String seq = specialSequence(code, ctrl, alt);
                if (seq != null) {
                    write(seq);
                    scrollToBottom();
                    e.consume();
                }
        }
    }

    private void handleKeyTyped(KeyEvent e) {
        char c = e.getKeyChar();
        if (c == KeyEvent.CHAR_UNDEFINED || c == '\n' || c == '\r' || c < 0x20) {
            return;
        }
        if ((e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0) {
            return;     // Ctrl 组合由 keyPressed 处理（^C 之类）
        }
        write(String.valueOf(c));
        scrollToBottom();
        e.consume();
    }

    /** 特殊键 → 标准 xterm 转义序列。Ctrl 字母 → 控制字符。 */
    private String specialSequence(int code, boolean ctrl, boolean alt) {
        switch (code) {
            case KeyEvent.VK_ENTER:
                return "\r";
            case KeyEvent.VK_BACK_SPACE:
                return "\u007f";
            case KeyEvent.VK_TAB:
                return ctrl ? "\u001b[Z" : "\t";
            case KeyEvent.VK_ESCAPE:
                return "\u001b";
            case KeyEvent.VK_UP:
                return alt ? "\u001b[1;3A" : "\u001b[A";
            case KeyEvent.VK_DOWN:
                return alt ? "\u001b[1;3B" : "\u001b[B";
            case KeyEvent.VK_RIGHT:
                return alt ? "\u001b[1;3C" : "\u001b[C";
            case KeyEvent.VK_LEFT:
                return alt ? "\u001b[1;3D" : "\u001b[D";
            case KeyEvent.VK_HOME:
                return "\u001b[H";
            case KeyEvent.VK_END:
                return "\u001b[F";
            case KeyEvent.VK_DELETE:
                return "\u001b[3~";
            case KeyEvent.VK_INSERT:
                return "\u001b[2~";
            case KeyEvent.VK_F1:
                return "\u001bOP";
            case KeyEvent.VK_F2:
                return "\u001bOQ";
            case KeyEvent.VK_F3:
                return "\u001bOR";
            case KeyEvent.VK_F4:
                return "\u001bOS";
            case KeyEvent.VK_F5:
                return "\u001b[15~";
            case KeyEvent.VK_F6:
                return "\u001b[17~";
            case KeyEvent.VK_F7:
                return "\u001b[18~";
            case KeyEvent.VK_F8:
                return "\u001b[19~";
            case KeyEvent.VK_F9:
                return "\u001b[20~";
            case KeyEvent.VK_F10:
                return "\u001b[21~";
            case KeyEvent.VK_F11:
                return "\u001b[23~";
            case KeyEvent.VK_F12:
                return "\u001b[24~";
            default:
                break;
        }
        if (ctrl && code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
            return String.valueOf((char) (code - KeyEvent.VK_A + 1));
        }
        if (ctrl && code == KeyEvent.VK_SPACE) {
            return "\u0000";
        }
        return null;
    }

    private void write(String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        // 排障开关：-Ddsh.term.debug=1 时把每个按键写进控制台（DSH_DEBUG 模式下可见）
        if (DEBUG_KEYS) {
            System.out.println("[term] key -> " + s.replace("\r", "<CR>").replace("\u001b", "<ESC>")
                    + (inputSink == null ? "  (inputSink 为空：没连上)" : ""));
        }
        if (inputSink == null) {
            return;
        }
        inputSink.accept(s.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ 鼠标

    private void installMouse() {
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (SwingUtilities.isRightMouseButton(e)) {
                    showContextMenu(e);
                    return;
                }
                int col = e.getX() / Math.max(1, cellW);
                int line = lineIndexOfTop() + e.getY() / Math.max(1, cellH);
                selAnchorLine = line;
                selAnchorCol = col;
                selLine = line;
                selCol = col;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (selAnchorLine < 0) {
                    return;
                }
                selCol = e.getX() / Math.max(1, cellW);
                selLine = lineIndexOfTop() + e.getY() / Math.max(1, cellH);
                // 拖到边缘自动滚
                int rows = Math.max(1, getHeight() / Math.max(1, cellH));
                int row = e.getY() / Math.max(1, cellH);
                if (row < 0) {
                    scrollBy(2);
                } else if (row >= rows) {
                    scrollBy(-2);
                }
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                scrollBy(e.getWheelRotation() * 3);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !SwingUtilities.isRightMouseButton(e)) {
                    selectWord(e.getX(), e.getY());
                }
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    private void showContextMenu(MouseEvent e) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copy = new javax.swing.JMenuItem("复制（Ctrl+Shift+C）");
        copy.addActionListener(a -> copySelection());
        javax.swing.JMenuItem paste = new javax.swing.JMenuItem("粘贴（Ctrl+Shift+V）");
        paste.addActionListener(a -> paste());
        javax.swing.JMenuItem clear = new javax.swing.JMenuItem("清屏（Ctrl+L）");
        clear.addActionListener(a -> write("\u000c"));
        javax.swing.JMenuItem bottom = new javax.swing.JMenuItem("回到最底");
        bottom.addActionListener(a -> scrollToBottom());
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(clear);
        menu.add(bottom);
        menu.show(this, e.getX(), e.getY());
    }

    private void selectWord(int px, int py) {
        int line = lineIndexOfTop() + py / Math.max(1, cellH);
        TerminalScreen.Line l;
        synchronized (screen) {
            l = screen.lineAt(line - lineIndexOfTop());
        }
        if (l == null) {
            return;
        }
        int col = Math.max(0, Math.min(l.len - 1, px / Math.max(1, cellW)));
        int a = col;
        int b = col;
        while (a > 0 && !Character.isWhitespace(l.ch[a - 1])) {
            a--;
        }
        while (b < l.len - 1 && !Character.isWhitespace(l.ch[b + 1])) {
            b++;
        }
        selAnchorLine = line;
        selAnchorCol = a;
        selLine = line;
        selCol = b + 1;
        repaint();
    }

    private int lineIndexOfTop() {
        synchronized (screen) {
            return screen.historySize() - scrollOffset;
        }
    }

    void scrollBy(int lines) {
        int hist;
        synchronized (screen) {
            hist = screen.historySize();
        }
        int next = Math.max(0, Math.min(hist, scrollOffset + lines));
        if (next != scrollOffset) {
            scrollOffset = next;
            if (onScrolled != null) {
                onScrolled.run();
            }
            repaint();
        }
    }

    // ------------------------------------------------------------ 复制 / 粘贴

    void copySelection() {
        String text = selectedText();
        if (text == null || text.isEmpty()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    String selectedText() {
        if (selAnchorLine < 0 || selLine < 0) {
            return "";
        }
        int loLine = Math.min(selAnchorLine, selLine);
        int hiLine = Math.max(selAnchorLine, selLine);
        int loCol = selAnchorLine <= selLine ? selAnchorCol : selCol;
        int hiCol = selAnchorLine <= selLine ? selCol : selAnchorCol;
        int topLine = lineIndexOfTop();
        StringBuilder sb = new StringBuilder();
        synchronized (screen) {
            for (int line = loLine; line <= hiLine; line++) {
                TerminalScreen.Line l = screen.lineAt(line - topLine);
                if (l == null) {
                    continue;
                }
                int from = line == loLine ? Math.max(0, loCol) : 0;
                int to = line == hiLine ? Math.min(l.len, hiCol) : l.len;
                for (int x = from; x < to; x++) {
                    sb.append(l.ch[x]);
                }
                if (line < hiLine) {
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    void paste() {
        try {
            Object o = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (o != null) {
                write(o.toString());
                scrollToBottom();
            }
        } catch (Exception ignore) {
            // 剪贴板里不是文本，忽略
        }
    }

    // ------------------------------------------------------------ 字体

    private void rebuildFont() {
        font = new Font(monoFamily, Font.PLAIN, fontSize);
        boldFont = new Font(monoFamily, Font.BOLD, fontSize);
        // 终端里的汉字必须换一个真正含 CJK 的字体（Windows 上是微软雅黑），
        // 否则物理字体不回退，屏幕上全是"□"。
        cjkFont = dev.example.dshclient.ui.UiFonts.cjk(fontSize);
        cjkBold = cjkFont.deriveFont(Font.BOLD);
        FontMetrics fm = getFontMetrics(font);
        cellW = Math.max(6, fm.charWidth('M'));
        // 用汉字字体的实际宽度决定"一个汉字占几格"，而不是拿 ASCII 字体瞎猜
        int hanW = getFontMetrics(cjkFont).charWidth('\u4E2D');
        int wide = hanW >= cellW * 1.5 ? 2 : 1;
        synchronized (screen) {
            screen.setWideCols(wide);
        }
        cellH = Math.max(10, fm.getHeight());
        ascent = fm.getAscent();
        setFont(font);
        lastCols = -1;
        lastRows = -1;
        revalidate();
        queueRepaint();
    }

    private void queueRepaint() {
        repaintQueued = true;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(cellW * 100, cellH * 30);
    }

    /** 只给测试用：拿到整屏文本。 */
    String dumpText() {
        StringBuilder sb = new StringBuilder();
        synchronized (screen) {
            for (int y = 0; y < screen.rows(); y++) {
                sb.append(TerminalScreen.textOf(screen.lineAt(y), screen.cols())).append('\n');
            }
        }
        return sb.toString();
    }

}
