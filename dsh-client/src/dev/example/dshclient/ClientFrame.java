package dev.example.dshclient;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;

import dev.example.dshclient.term.SshShell;
import dev.example.dshclient.term.TerminalPalette;
import dev.example.dshclient.term.TerminalPanel;
import dev.example.dshclient.term.TerminalScreen;
import dev.example.dshclient.ui.ContextMenu;
import dev.example.dshclient.ui.DownloadPanel;
import dev.example.dshclient.ui.DownloadRow;
import dev.example.dshclient.ui.GradientCard;
import dev.example.dshclient.ui.ModernButton;
import dev.example.dshclient.ui.StatusBar;
import dev.example.dshclient.ui.TabStrip;
import dev.example.dshclient.ui.TerminalFrame;
import dev.example.dshclient.ui.ThemeTransition;
import dev.example.dshclient.ui.UiFonts;
import dev.example.dshclient.ui.StatusPill;
import dev.example.dshclient.ui.ThemeManager;
import dev.example.dshclient.ui.UiTheme;

/**
 * 客户端主窗口：只做三件事 —— 显示隧道状态、打开 DSH 界面、断了自动重连。
 *
 * 界面本身用系统里的 Edge/Chrome 以 `--app=` 模式打开（没有地址栏，看起来就是个独立程序）。
 * 这样就不需要往 jar 里塞 WebView 内核，也不用装任何额外运行时。
 */
final class ClientFrame extends JFrame {

    private final DshClient.Config cfg;
    /** 这次走哪条路（auto 已在 DshClient 里解析成具体路线）。1.9.0：连不上时可退，故非 final。 */
    private RouteProbe.Route route;
    /** 路线是不是 auto 挑的 —— 只有 auto 才允许运行时改判（显式指定的路线要尊重用户）。 */
    private boolean routeAuto;
    private final JTextField hostField;
    private final JTextField portField;
    private final JTextField userField;
    private final JTextField keyField;
    private final JTextField localPortField;
    private final JTextField tokenField;
    private final javax.swing.JCheckBox wbEnabledBox;
    private final JTextField wbLocalPortField;
    private final JTextField wbPasswordField;
    /** 1.10.0：WorkBuddy 的域名入口（Cloudflare ingress → 容器 6080）。 */
    private final JTextField wbWebUrlField;
    /** 1.26.0：界面上的**手动选路**（4 个选项：自动 / SSH 直连 / 域名(CF隧道) / 网页隧道）。 */
    private final dev.example.dshclient.ui.ChoiceField routeBox;
    /** WorkBuddy 的 4 个路径选项。 */
    private final dev.example.dshclient.ui.ChoiceField wbRouteBox;
    private final JTextField wbContainerField;
    private final JTextField wbSshExeField;
    private final JTextField wbSshKeyField;
    private final javax.swing.JCheckBox wbAutoOpenBox;
    /** WorkBuddy 容器状态显示（1.5.0） */
    private final JLabel wbStateLabel = new JLabel("WorkBuddy 容器：未知");

    private final JLabel status = new JLabel("未连接");
    /**
     * 「日志」页签的文本框。
     *
     * <p>为什么是匿名子类：Swing 在**右键按下**时会把插入点挪到鼠标处、顺手清掉选区，
     * 等我们的 MouseListener 收到事件时已经没东西可复制了（用户实测"右键点不动、Ctrl+C 也没反应"）。
     * 这里在 super 处理之前把选区记下来，右键菜单里的「复制」才有内容可用。
     */
    private final JTextArea log = new JTextArea(8, 64) {
        private static final long serialVersionUID = 1L;

        @Override
        protected void processMouseEvent(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
                rememberLogSelection();
            }
            super.processMouseEvent(e);
        }
    };
    /** 日志页签的滚动容器：只在"本来就贴在底部"时跟随新行，用户翻上去看历史时不被拽回来。 */
    private JScrollPane logScroll;
    /** 右键/复制用的选区兜底（右键清掉选区后仍能复制刚才选的那段）。 */
    private String logSelBuffer = "";
    private int logSelStart = -1;
    private int logSelEnd = -1;

    /** 重连防抖：同一时刻只允许一条重连线程；失败按 2→4→8→16→30 秒退避。 */
    private final java.util.concurrent.atomic.AtomicBoolean reconnecting =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long reconnectNotBefore = 0L;
    private int reconnectTries;
    private boolean tunnelWasAlive;
    /** 转发探活：SSH 会话"看着还连着"但转发已经没数据时，靠它发现（见 startForwardProbe）。 */
    private java.util.Timer forwardProbe;
    private volatile int probeFails;
    /** WorkBuddy 的 6080 被 permitopen 拒：完整修法只打一次，别每次重连都刷一遍。 */
    private boolean wbPermitNoteShown;
    /** 10 分钟窗口内的掉线次数 / 窗口起点（钉死路线时用来提示换路）。 */
    private long dropWindowStart;
    private int dropsInWindow;
    /**
     * 本次运行里隧道已经"烧掉"了（3.2.1）。
     *
     * <p>隧道掉过 2 次就说明这条承载层不稳（大文件上传必断），自动切到直连后**不要再因为
     * 直连掉线就切回隧道** —— 否则两条路各掉两次，就在 DIRECT / TUNNEL 之间来回跳。
     * 直连真连不上时仍会走 connect() 的失败回落（那次是"连不上"，不是"掉线"）。
     */
    private boolean tunnelBurned;
    /** 3.2.2：启动时没拿到全局 IPv6 的复查定时器（见 startIpv6Watch）。 */
    private javax.swing.Timer ipv6Watch;

    // ---------------- 2.10.0：内置 SSH 终端 + 暗/亮主题 ----------------
    /** 终端控件（自绘字格，见 term 包）。 */
    private final TerminalPanel terminal;
    /** 中间的「终端 / 日志」页签。 */
    private final TabStrip centerTabs = new TabStrip(ThemeManager.get());
    /** 主题切换的 220ms 过渡（v2）。 */
    private final ThemeTransition themeTransition = new ThemeTransition();
    /** 顶部状态条（v2：渐变 + 自绘徽记）。 */
    private StatusBar topStatusBar;
    /** 终端页签的根面板：窗口级键盘接管用它判断"当前是不是在终端页"。 */
    private JComponent terminalTabPanel;
    /** 开始页「连接方式」右侧的说明文字（跟路线实时同步）。 */
    private JLabel routeHintLabel;
    /** 下载弹层（自绘，跟随主题）。 */
    private DownloadPanel downloadPanel;
    /** 设置窗口（单例，非模态）。 */
    private javax.swing.JDialog settingsDialog;
    /** 终端外框（v2）；标题栏显示连接状态。 */
    private TerminalFrame terminalFrame;
    /** 合并窗口用的本地中转页（DSH + WorkBuddy 两个标签页）。 */
    private final WebHub hub = new WebHub();
    private boolean hubOpened;
    /** 内嵌浏览器两个页签（JCEF）；null = 还没建。 */
    private JcefView embedDsh;
    private JcefView embedWb;
    private String embedDshUrl = "";
    private String embedWbUrl = "";
    /** 固定页签下标：开始=0、终端=1、日志=2。 */
    private static final int START_TAB = 0;
    private static final int TERMINAL_TAB = 1;
    private static final int LOG_TAB = 2;
    /** 外观卡片里的"内嵌浏览器"开关。 */
    private final javax.swing.JCheckBox embedBrowserBox = new javax.swing.JCheckBox("启用");
    /** 外观卡片里的"合并窗口"开关。 */
    private final javax.swing.JCheckBox mergeWindowsBox = new javax.swing.JCheckBox("启用");
    /** 外观卡片里的主题下拉；用同步标志避免"程序改选中"触发成用户操作。 */
    private final dev.example.dshclient.ui.ChoiceField themeCombo;
    private boolean syncingThemeCombo;
    /** 终端自己的状态（跟隧道状态分开显示，免得"隧道通了但 shell 被 no-pty 拒了"看不出来）。 */
    private final JLabel termState = new JLabel("终端：未连接");
    /** 当前 shell 会话；null = 没开。 */
    private SshShell shell;
    private boolean darkMode = true;
    /** 主题中枢（WB 专家产出的 ui 包）：颜色令牌 + 切换广播 + 持久化桥。 */
    private final ThemeManager tm = ThemeManager.get();
    /** 顶部那颗主题按钮；外部（系统切换/设置下拉）改模式时也要跟着改文字。 */
    private ModernButton themeBtn;
    /** 跟随系统的复查定时器（每 3 秒问一次系统，变了就走过渡）。 */
    private javax.swing.Timer themeWatch;
    /** 后台探测是否还在跑（避免上一个 reg 还没回来又起一个）。 */
    private boolean themeProbeRunning;
    /** 已经推给内嵌 DSH 页面的亮暗；过渡动画每帧都会走 applyTheme，值没变就不重复推。 */
    private Boolean pushedPageDark;
    /** 主题模式表：下标与设置里的下拉一一对应，也是顶部按钮的循环顺序。 */
    private static final String[] THEME_MODES = {"system", "light", "dark"};
    /** 顶部那颗状态徽标（连接中·已连接·已断开·错误）。 */
    private final StatusPill pill;
    /** 需要跟着主题换底色的普通控件（递归刷一遍，简单可靠）。 */
    private final java.util.List<java.awt.Component> themed = new java.util.ArrayList<>();

    private SshTunnel tunnel;
    /** 1.9.0：网页隧道路线下"只做 6080"的那条临时会话（点 WB 时才建）。 */
    private SshTunnel wbOnlyTunnel;
    private javax.swing.Timer watchdog;
    private boolean stopped;
    /** 1.11.0：auto 模式下直连掉线次数（≥2 就切域名）；连稳 5 分钟清零。 */
    private int directDrops = 0;
    private long connectedAt = 0;
    /** 速度比较只做一次（auto 模式）；点「重新连接」会重置。 */
    private boolean speedDone = false;
    /** 状态栏文案 / 是否红色 / 最近一次实测吞吐（1.12.1，用来让显示跟住真实路线）。 */
    private String connText = "未连接";
    private boolean connError = false;
    private double lastKbps = -1;
    /** DSH 界面窗口是否已经开过（自动路径只开一次；1.19.0）。 */
    private boolean dshWindowOpened = false;

    ClientFrame(DshClient.Config cfg, RouteProbe.Route route) {
        super("DSH 客户端 " + DshClient.VERSION + " —— " + ClientFrame.routeTitle(route));
        this.cfg = cfg;
        this.route = route;
        this.routeAuto = RouteProbe.Route.of(cfg.route) == null;
        this.hostField = new JTextField(cfg.host);
        this.portField = new JTextField(String.valueOf(cfg.port));
        this.userField = new JTextField(cfg.user);
        this.keyField = new JTextField(cfg.key);
        this.localPortField = new JTextField(String.valueOf(cfg.localPort));
        this.tokenField = new JTextField(cfg.token);
        this.wbEnabledBox = new javax.swing.JCheckBox("启用", cfg.wbEnabled);
        this.wbLocalPortField = new JTextField(String.valueOf(cfg.wbLocalPort));
        this.wbPasswordField = new JTextField(cfg.wbPassword);
        this.wbWebUrlField = new JTextField(cfg.wbWebUrl);
        this.wbContainerField = new JTextField(cfg.wbContainer);
        this.wbSshExeField = new JTextField(cfg.wbSshExe);
        this.wbSshKeyField = new JTextField(cfg.wbSshKey);
        this.wbAutoOpenBox = new javax.swing.JCheckBox("启用", cfg.wbAutoOpen);
        // 界面选路：把当前设置反映到下拉里；改动立刻保存并生效（DSH 路线会重连）
        routeBox = new dev.example.dshclient.ui.ChoiceField(
                new String[]{"自动（判读 + 实测）", "SSH 直连（IPv6:22）", "域名（CF 隧道）", "网页隧道（浏览器）"}, tm);
        wbRouteBox = new dev.example.dshclient.ui.ChoiceField(
                new String[]{"自动（跟随 DSH 路线）", "① 直连 IPv6 [服务器]:6080",
                        "② 域名 wb.example.com", "③ SSH 转发 127.0.0.1:6080"}, tm);
        themeCombo = new dev.example.dshclient.ui.ChoiceField(
                new String[]{"跟随系统", "亮色", "暗色"}, tm);
        // 3.2.4：这个下拉之前没有监听器（改了下拉毫无反应）。顺序与 THEME_MODES 对齐。
        themeCombo.addSelectionListener(v -> {
            if (syncingThemeCombo) {
                return;
            }
            int index = themeCombo.selectedIndex();
            if (index >= 0 && index < THEME_MODES.length) {
                tm.setMode(THEME_MODES[index]);
                syncThemeControls();
            }
        });

        routeBox.setSelectedIndex(routeIndexOf(cfg.route));
        wbRouteBox.setSelectedIndex(wbRouteIndexOf(cfg.wbRoute));
        routeBox.addSelectionListener(v -> {
            updateRouteHint();
            applyRouteChoice(routeIdOf(routeBox.selectedIndex()));
        });
        wbRouteBox.addSelectionListener(v -> applyWbRouteChoice(wbRouteIdOf(wbRouteBox.selectedIndex())));

        // 终端先建好：布局要用它；暗色先给一版，applyTheme 会按设置再刷一次
        this.terminal = new TerminalPanel(new TerminalScreen(100, 30), TerminalPalette.dark());
        this.terminal.setMonoFamily(UiFonts.MONO_FAMILY);
        this.terminal.setInputSink(bytes -> {
            SshShell sh = shell;
            if (sh != null) sh.write(bytes);
        });
        this.terminal.setSizeSink((cols, rows) -> {
            SshShell sh = shell;
            if (sh != null) sh.setPtySize(cols, rows);
        });

        this.pill = new StatusPill(StatusPill.State.DISCONNECTED, "未连接", tm);

        // 这两个开关以前在 configPanel() 里初始化，而设置面板改成懒加载后构造函数根本不跑
        // → 启动时恒为未勾选 → "内嵌"永远不生效（2.13.4 的实测现象：DSH 又单独弹窗）。
        // 现在在构造期就按配置同步一次，不依赖设置窗口是否被打开过。
        mergeWindowsBox.setSelected(cfg.mergeWindows);
        embedBrowserBox.setSelected(cfg.embeddedBrowser);
        embedBrowserBox.setEnabled(JcefGate.available());
        // 把判定依据一次讲清：以后"为什么弹了独立窗口"直接看这一行
        if (!JcefGate.available()) {
            logLine("内嵌浏览器：不可用（cef\\ 里缺 jar / 用的不是 run-cef.bat）→ DSH 会开成独立窗口");
        } else if (!cfg.embeddedBrowser) {
            logLine("内嵌浏览器：设置里被关掉了 → DSH 会开成独立窗口（设置 → 外观 → 内嵌浏览器 勾上）");
        } else {
            logLine("内嵌浏览器：开 → DSH / WorkBuddy 进客户端页签");
        }

        // 主题：系统探测 → 持久化 → 按"模式"定色 → 监听统一重绘。
        //
        // 3.2.4 之前的三个坑（"跟随系统"永远是深色、也切不动）：
        //   1. 这里把 system 先折成 dark/light 再 setMode，而 setMode 会把参数写回配置
        //      → 首次启动后配置就变成固定色，"跟随系统"永久丢失；
        //   2. ThemeManager 的 detectSystemDark 看的是 Swing 默认控件底色（Windows 上恒为浅色），
        //      从来没读过系统的深色开关；
        //   3. 全程只算一次，系统切换深色不会重算，也没有任何定时复查。
        tm.setSystemDarkSource(this::systemDark);
        tm.setPersistence(new ThemeManager.Persistence() {
            @Override
            public java.util.function.Consumer<String> persist() {
                return m -> {
                    cfg.theme = m;
                    saveSettings();
                };
            }

            @Override
            public String load() {
                return cfg.theme == null ? "system" : cfg.theme;
            }
        });
        String want = cfg.theme == null ? "system" : cfg.theme.trim().toLowerCase();
        try {
            tm.setMode(want);
        } catch (IllegalArgumentException legacy) {
            // 旧版本可能把垃圾值写进了配置
            tm.setMode("system");
        }
        tm.addListener(this::applyTheme);

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stop();
                hub.stop();
                if (embedDsh != null) embedDsh.disposeBrowser();
                if (embedWb != null) embedWb.disposeBrowser();
                dispose();
                System.exit(0);
            }
        });
        // 2.10.0 布局：顶部状态条 / 左侧连接参数（可收起）/ 中间 终端+日志 / 底部操作行
        setLayout(new BorderLayout(0, 0));
        installTerminalKeyTrap();
        add(statusBar(), BorderLayout.NORTH);
        add(centerPane(), BorderLayout.CENTER);
        setMinimumSize(new Dimension(900, 560));
        setSize(1000, 660);
        setLocationRelativeTo(null);
        applyTheme();
        startThemeWatch();
        terminal.printLocal("DSH 客户端 " + DshClient.VERSION + " · 内置 SSH 终端\n"
                + "点上方「连接终端」开一条 shell —— 用的是同一个账号、同一把 key，和端口转发互不影响。\n"
                + "（Ctrl+Shift+C 复制，Ctrl+Shift+V 粘贴，Ctrl+滚轮 或 A+/A- 改字号）\n\n");
    }

    /** 启动：连隧道 → 打开界面 → 起看门狗。（web 路线没有隧道可守） */
    void startAndOpen() {
        new Thread(() -> {
            logLine("DSH 客户端 " + DshClient.VERSION + " · " + DshClient.jarPathHint());
            logLine("内嵌浏览器（JCEF）：" + (JcefGate.available() ? "可用" : "不可用 —— " + JcefGate.diagnose()));
            logLine("字体：界面=" + UiFonts.UI_FAMILY + "｜等宽=" + UiFonts.MONO_FAMILY
                    + "｜汉字=" + UiFonts.CJK_FAMILY + "（出现方块就是这三个都没汉字）");
            if (!cfg.resolveNote.isEmpty()) logLine(cfg.resolveNote);
            // 3.2.2：把"这台机器看到哪些 IPv6"直接写进日志。
            // 以前只有一句"本机没有任何全局 IPv6 地址"，既不说是哪块网卡、也不说枚举是否成功，
            // 结果明明是 Windows 有公网 IPv6 却判成没有，谁也没法查。
            String[] v6 = RouteProbe.findGlobalIpv6();
            logLine("本机 IPv6：" + (v6 == null ? "无全局地址" : v6[0] + " → " + v6[1])
                    + "　［全部：" + RouteProbe.ipv6Inventory() + "］");
            if (v6 == null) startIpv6Watch();
            if (!DshClient.routeReason.isEmpty()) logLine(DshClient.routeReason);
            if (!routeAuto) {
                logLine("　※ 路线是按设置**钉死**的（只有「自动」才会在连不上时回落）。"
                        + "如果这条 IPv6 直连一直抖，把设置里的「路线」改成「自动（判读 + 实测）」或「域名（CF 隧道）」再试。");
            }
            logLine("本次连接目标：" + cfg.hostResolved + ":" + cfg.port
                    + "｜token：" + (cfg.tokenResolved == null || cfg.tokenResolved.isEmpty()
                    ? "(空)" : cfg.tokenResolved.substring(0, Math.min(6, cfg.tokenResolved.length())) + "…"));
            if (!connect()) return;
            if (route == RouteProbe.Route.WEB) {
                if (cfg.openBrowser) {
                    // 统一漏斗：能内嵌就进页签，别在这里直接开窗口
                    reloadOrOpenDsh(DshClient.webUrl(cfg));
                    logLine("网页隧道（走 Cloudflare，本机没有 SSH 转发）→ "
                            + (useEmbedded() ? "已放进「DSH 界面」页签" : "已在独立窗口打开"));
                    logLine("WorkBuddy 仍然可用：点「打开 WorkBuddy 桌面」时会临时经 CF 单开一条只做 6080 的会话。");
                    if (cfg.wbAutoOpen) openWorkbuddyWindow();
                    else logLine("WorkBuddy 窗口没有自动打开（默认关；点「打开 WorkBuddy 桌面」即可）");
                }
                return;
            }
            if (cfg.openBrowser) {
                openDshWindow(dshUrlWithFreshToken());
                logLine("已在独立窗口打开 DSH 界面（没有地址栏的那个窗口）。");
                logLine("如果窗口里要求登录/显示 401：把「Token」那一栏填上 dsh web 启动时打印的那串，再点『重新打开』。");
                if (cfg.wbAutoOpen) {
                    openWorkbuddyWindow();
                } else {
                    logLine("WorkBuddy 窗口没有自动打开（默认关；点「打开 WorkBuddy 桌面」即可）");
                }
            }
            startWatchdog();
            refreshWbState();
        }, "dsh-connect").start();
    }

    /** 路线的人话名字（写清「SSH 自己走哪条网络路径」，别和「端口转发」混为一谈）。 */
    private static String routeTitle(RouteProbe.Route route) {
        if (route == RouteProbe.Route.WEB) return "路线=域名/网页隧道（不经 SSH）";
        if (route == RouteProbe.Route.TUNNEL) return "路线=域名（SSH 装进 WebSocket 走 Cloudflare）";
        return "路线=SSH 直连（IPv6:22，不经 Cloudflare）";
    }


    // ================= 2.10.0 新的窗口骨架 =================

    /**
     * 顶部状态条（v2）：StatusBar 自绘渐变底 + 几何徽记，右侧是状态徽标和两个操作按钮。
     * 路线摘要改成写进标题（`setTitle`），因为 48px 的高度里塞不下"标题+版本+徽标+两行文字"。
     */
    private JPanel statusBar() {
        StatusBar bar = new StatusBar("DSH 客户端", tm);
        JPanel actions = bar.actionArea();
        actions.add(pill);
        ModernButton dlBtn = new ModernButton("下载", ModernButton.Kind.GHOST, tm);
        dlBtn.setToolTipText("内嵌浏览器的下载列表（不经过系统浏览器）");
        DownloadCenter.get().addListener(() -> runOnEdt(() -> {
            int n = DownloadCenter.get().activeCount();
            dlBtn.setText(n > 0 ? "下载 " + n : "下载");
            dlBtn.repaint();
        }));
        dlBtn.addActionListener(e -> showDownloadMenu(dlBtn));
        actions.add(dlBtn);

        // 3.2.4：点一下走一格（跟随系统 → 亮色 → 暗色），颜色过渡由 ThemeManager 自己播；
        // 以前这里只会在亮/暗之间翻，而且永远回不到"跟随系统"。
        themeBtn = new ModernButton(themeLabel(), ModernButton.Kind.GHOST, tm);
        themeBtn.setToolTipText("点一下切换主题：跟随系统 → 亮色 → 暗色");
        themeBtn.addActionListener(e -> cycleThemeMode());
        ModernButton settingsBtn = new ModernButton("设置", ModernButton.Kind.GHOST, tm);
        settingsBtn.addActionListener(e -> showSettings());
        actions.add(themeBtn);
        actions.add(settingsBtn);
        topStatusBar = bar;
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(bar, BorderLayout.CENTER);
        wrap.add(accentLine(), BorderLayout.SOUTH);
        return wrap;
    }

    /**
     * 窗口级键盘接管：在终端页签里，除了输入框，其他按键一律送进终端。
     *
     * <p>为什么需要这个：点「清屏」「A+」这类按钮后，Swing 会把焦点交给按钮，
     * 之后键盘事件根本不进终端面板（用户实测"清屏后就打不了字"）。
     * 光靠 requestFocusInWindow 不可靠，所以在焦点管理者这一层兜住。
     */
    private void installTerminalKeyTrap() {
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (terminalTabPanel == null || centerTabs == null) {
                return false;
            }
            if (centerTabs.selected() != TERMINAL_TAB) {
                return false;
            }
            if (e.getID() != java.awt.event.KeyEvent.KEY_TYPED
                    && e.getID() != java.awt.event.KeyEvent.KEY_PRESSED) {
                return false;
            }
            java.awt.Component owner =
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            // 设置里那些输入框还要正常打字
            if (owner instanceof javax.swing.text.JTextComponent) {
                return false;
            }
            if (e.isConsumed()) {
                return false;
            }
            terminal.handleKeyEvent(e);
            return true;      // 已处理，别让 Swing 再分发
        });
    }

    /** 状态条下沿的 2px 强调渐变线：整窗唯一"一直亮着"的装饰，成本只有一次 fillRect。 */
    private JComponent accentLine() {
        return new JComponent() {
            private static final long serialVersionUID = 1L;

            @Override
            protected void paintComponent(java.awt.Graphics g0) {
                java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
                try {
                    UiTheme t = tm.theme();
                    g.setPaint(new java.awt.GradientPaint(0, 0, t.accent, getWidth(), 0, t.info));
                    g.fillRect(0, 0, getWidth(), getHeight());
                } finally {
                    g.dispose();
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(10, 2);
            }
        };
    }

    /**
     * 设置内容（三张渐变卡片）。它现在只活在**独立窗口**里 —— 主界面把宽度让给终端和网页页签。
     */
    private JPanel settingsContent() {
        return configPanel();
    }

    /** 打开设置窗口（单例，非模态：开着也能继续看终端）。 */
    private void showSettings() {
        if (settingsDialog == null) {
            // 无系统边框：SettingsPanel 自带标题条 + 关闭按钮，圆角/描边由它自绘
            settingsDialog = new javax.swing.JDialog(this, "DSH 客户端设置", false);
            settingsDialog.setUndecorated(true);
            settingsDialog.setContentPane(settingsContent());
            settingsDialog.setSize(660, 620);
            settingsDialog.setLocationRelativeTo(this);
        }
        settingsDialog.setVisible(true);
        settingsDialog.toFront();
    }

    /** 主区页签：设置 / 终端 / 日志（后面按需追加 DSH、WorkBuddy）。 */
    private JPanel centerPane() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 8));
        centerTabs.addTab("开始", startTab());
        centerTabs.addTab("终端", terminalTab());
        centerTabs.addTab("日志", logPanel());
        centerTabs.select(START_TAB);
        centerTabs.addSelectionListener(i -> {
            // 内嵌浏览器是原生子窗口：切走时必须让它隐藏并交出焦点，
            // 否则键盘会被它抢走（表现为"终端打的字跑到 DSH 页面里"）。
            if (embedDsh != null) {
                embedDsh.setActive(i == centerTabs.indexOf(embedDsh));
            }
            if (embedWb != null) {
                embedWb.setActive(i == centerTabs.indexOf(embedWb));
            }
            if (i == TERMINAL_TAB) {
                // 内嵌浏览器是原生子窗口，切走时它可能还拿着 Windows 焦点。
                // 1) 清掉旧的 Swing 焦点所有者（否则它仍可能是 CEF 的 Canvas）；
                // 2) 让顶层窗口拿回 OS 焦点；
                // 3) 再把焦点交给终端，并延迟补一次（CEF 的隐藏是异步的）。
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .clearGlobalFocusOwner();
                requestFocus();
                terminal.requestFocus();
                SwingUtilities.invokeLater(() -> {
                    terminal.requestFocus();
                    javax.swing.Timer retry = new javax.swing.Timer(80, e -> {
                        ((javax.swing.Timer) e.getSource()).stop();
                        terminal.requestFocus();
                    });
                    retry.setRepeats(false);
                    retry.start();
                });
            }
        });
        p.add(centerTabs, BorderLayout.CENTER);
        return p;
    }

    /** 「设置」页签：只是入口 —— 真正的表单在独立窗口里，避免把主界面挤窄。 */
    /** 终端页：v2 的 TerminalFrame 提供圆角外框 + 渐变标题条，内部仍是我的 TerminalPanel。 */
    private JPanel terminalTab() {
        TerminalFrame frame = new TerminalFrame("终端 · 未连接", tm);
        this.terminalTabPanel = frame;
        this.terminalFrame = frame;
        JPanel actions = frame.actionArea();
        ModernButton conn = new ModernButton("连接终端", ModernButton.Kind.PRIMARY, tm);
        conn.addActionListener(e -> new Thread(this::connectTerminal, "dsh-term-open").start());
        ModernButton dis = new ModernButton("断开", ModernButton.Kind.SECONDARY, tm);
        dis.addActionListener(e -> {
            disconnectTerminal();
            terminal.requestFocusInWindow();
        });
        ModernButton clr = new ModernButton("清屏", ModernButton.Kind.SECONDARY, tm);
        clr.addActionListener(e -> {
            terminal.clear();
            terminal.requestFocusInWindow();
        });
        ModernButton smaller = new ModernButton("A-", ModernButton.Kind.GHOST, tm);
        smaller.addActionListener(e -> {
            terminal.setFontSize(terminal.fontSize() - 1);
            terminal.requestFocusInWindow();
        });
        ModernButton bigger = new ModernButton("A+", ModernButton.Kind.GHOST, tm);
        bigger.addActionListener(e -> {
            terminal.setFontSize(terminal.fontSize() + 1);
            terminal.requestFocusInWindow();
        });
        actions.add(conn);
        actions.add(dis);
        actions.add(clr);
        actions.add(smaller);
        actions.add(bigger);
        frame.setTerminal(terminal);
        return frame;
    }

    /** 「开始」页签：把原来底部那两行按钮挪进来，主窗口不再被一条按钮栏占高度。 */
    private JPanel startTab() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));

        // ---------- 连接方式（原来在设置窗口里，挪到开始页方便随手切） ----------
        JPanel routeHead = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        routeHead.setOpaque(false);
        routeHead.add(new dev.example.dshclient.ui.SectionHeader("连接方式", tm));
        routeHead.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        routeHead.setAlignmentX(0f);

        JPanel routeRow = new JPanel(new BorderLayout(10, 0));
        routeRow.setOpaque(false);
        routeRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        routeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        routeRow.setAlignmentX(0f);
        routeBox.setPreferredSize(new Dimension(260, 32));
        routeBox.setMaximumSize(new Dimension(260, 32));
        JPanel routeLeft = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        routeLeft.setOpaque(false);
        routeLeft.add(routeBox);
        routeRow.add(routeLeft, BorderLayout.WEST);
        if (routeHintLabel == null) {
            routeHintLabel = new JLabel();
            routeHintLabel.setFont(UiFonts.ui(Font.PLAIN, 12f));
            routeHintLabel.setForeground(tm.theme().textSecondary);
        }
        routeRow.add(routeHintLabel, BorderLayout.CENTER);
        updateRouteHint();

        JPanel head1 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        head1.setOpaque(false);
        head1.add(new dev.example.dshclient.ui.SectionHeader("常用", tm));
        head1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        head1.setAlignmentX(0f);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 0, 10, 0));
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        buttons.setAlignmentX(0f);
        ModernButton open = new ModernButton("打开 DSH 界面", ModernButton.Kind.PRIMARY, tm);
        open.addActionListener(e -> new Thread(() -> {
            // 走 openDshWindow：能内嵌就是页签，不能内嵌才开浏览器窗口。
            // 以前这里直连 openInAppWindow，所以明明开了内嵌，点它还弹独立窗口。
            String url;
            if (route == RouteProbe.Route.WEB) {
                url = DshClient.webUrl(cfg);
                logLine("网页隧道（无 SSH 会话）：" + url);
            } else {
                if (tunnel == null || !tunnel.isAlive()) {
                    logLine("隧道还没连上，先点『重新连接』");
                    return;
                }
                url = dshUrlWithFreshToken();
            }
            final String u = url;
            runOnEdt(() -> {
                // 手点等于"我要再看一眼"：已有页签就切过去，没有就新建
                if (embedDsh != null && centerTabs.indexOf(embedDsh) >= 0) {
                    centerTabs.select(centerTabs.indexOf(embedDsh));
                    embedDsh.open(u);
                } else {
                    dshWindowOpened = false;
                    openDshWindow(u);
                }
            });
        }, "dsh-open").start());
        ModernButton openWb = new ModernButton("打开 WorkBuddy 桌面", ModernButton.Kind.SECONDARY, tm);
        openWb.addActionListener(e -> new Thread(this::openWorkbuddyWindow, "dsh-wb").start());
        ModernButton reconnect = new ModernButton("重新连接", ModernButton.Kind.SECONDARY, tm);
        reconnect.addActionListener(e -> new Thread(() -> {
            speedDone = false;
            if (!connect()) return;
            if (route == RouteProbe.Route.WEB) {
                String u = DshClient.webUrl(cfg);
                reloadOrOpenDsh(u);
                logLine("已重新连接网页隧道（" + (useEmbedded() ? "内嵌页签" : "浏览器窗口") + "）：" + u);
            } else {
                logLine("✅ 已重连。浏览器里的页面按 F5 刷新一下就好（本地端口没变）。");
            }
        }, "dsh-reconnect").start());
        ModernButton save = new ModernButton("保存设置", ModernButton.Kind.SECONDARY, tm);
        save.addActionListener(e -> saveSettings());
        ModernButton stop = new ModernButton("断开并退出", ModernButton.Kind.GHOST, tm);
        stop.addActionListener(e -> {
            stop();
            dispose();
            System.exit(0);
        });
        buttons.add(open);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(openWb);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(reconnect);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(save);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(stop);

        JPanel head2 = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
        head2.setOpaque(false);
        head2.add(new dev.example.dshclient.ui.SectionHeader("WorkBuddy 容器", tm));
        head2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        head2.setAlignmentX(0f);

        JPanel wbRow = new JPanel();
        wbRow.setLayout(new BoxLayout(wbRow, BoxLayout.X_AXIS));
        wbRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 8, 0));
        wbRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        wbRow.setAlignmentX(0f);
        ModernButton wbStart = new ModernButton("启动 WorkBuddy 容器", ModernButton.Kind.SECONDARY, tm);
        wbStart.addActionListener(e -> new Thread(() -> wbAction("start"), "dsh-wb-start").start());
        ModernButton wbStop = new ModernButton("停止容器", ModernButton.Kind.SECONDARY, tm);
        wbStop.addActionListener(e -> new Thread(() -> wbAction("stop"), "dsh-wb-stop").start());
        ModernButton wbRefresh = new ModernButton("刷新状态", ModernButton.Kind.GHOST, tm);
        wbRefresh.addActionListener(e -> new Thread(this::refreshWbState, "dsh-wb-state").start());
        wbRow.add(wbStart);
        wbRow.add(Box.createHorizontalStrut(6));
        wbRow.add(wbStop);
        wbRow.add(Box.createHorizontalStrut(6));
        wbRow.add(wbRefresh);
        wbRow.add(Box.createHorizontalStrut(12));
        wbRow.add(wbStateLabel);

        // 顺序很关键：标题 → 行 → 标题 → 行（BoxLayout 按 add 顺序排，别写反）
        p.add(routeHead);
        p.add(routeRow);
        p.add(head1);
        p.add(buttons);
        p.add(head2);
        p.add(wbRow);
        p.add(Box.createVerticalGlue());
        return p;
    }

    // ================= 终端生命周期 =================

    private void connectTerminal() {
        if (shell != null && shell.isConnected()) {
            centerTabs.select(TERMINAL_TAB);
            terminal.requestFocusInWindow();
            return;
        }
        String host = cfg.hostResolved != null && !cfg.hostResolved.isBlank() ? cfg.hostResolved : effectiveHost();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            port = cfg.port;
        }
        String user = userField.getText().trim();
        String key = keyField.getText().trim();
        com.jcraft.jsch.SocketFactory sf = route == RouteProbe.Route.TUNNEL
                ? new WsSshSocketFactory(cfg.tunnelWsUrl, 15_000) : null;
        setTermState("连接中…", false);
        logLine("开终端：" + user + "@" + host + ":" + port + "（同一个账号、同一把 key）");

        final String hostFinal = host;
        final int portFinal = port;
        SshShell sh = new SshShell(host, port, user, key, sf, new SshShell.Listener() {
            @Override
            public void onOutput(byte[] data, int len) {
                terminal.feed(data, len);
            }

            @Override
            public void onClosed(String reason) {
                SwingUtilities.invokeLater(() -> {
                    setTermState("已断开：" + reason, true);
                    logLine("终端断开：" + reason);
                    if (centerTabs.selected() == TERMINAL_TAB) {
                        terminal.scrollToBottom();
                    }
                });
            }
        });
        shell = sh;
        new Thread(() -> {
            try {
                sh.connect(terminal.cols(), terminal.rows(), 15_000);
                SwingUtilities.invokeLater(() -> {
                    setTermState("已连接 · " + hostFinal + ":" + portFinal, false);
                    centerTabs.select(TERMINAL_TAB);
                    terminal.requestFocusInWindow();
                });
            } catch (Exception ex) {
                String msg = String.valueOf(ex.getMessage()).replace('\n', ' ');
                SwingUtilities.invokeLater(() -> {
                    setTermState("连接失败", true);
                    logLine("终端连接失败：" + msg);
                    logLine("　※ 常见原因：服务器 authorized_keys 里那把 key 带了 restrict / no-pty；"
                            + "或者私钥路径不对。端口转发用同一把 key，能开页面不代表能开 shell。");
                });
                shell = null;
            }
        }, "dsh-term-connect").start();
    }

    private void disconnectTerminal() {
        SshShell sh = shell;
        shell = null;
        if (sh != null) {
            sh.close();
        }
        setTermState("未连接", false);
    }

    private void setTermState(String text, boolean error) {
        SwingUtilities.invokeLater(() -> {
            termState.setText("终端：" + text);
            if (terminalFrame != null) {
                terminalFrame.setTitle("终端 · " + text);
            }
            termState.setForeground(error
                    ? (darkMode ? new Color(0xF07178) : new Color(0xC02A2A))
                    : (darkMode ? new Color(0x8FB3EE) : new Color(0x1F5FA8)));
        });
    }

    // ================= 主题 =================

    /**
     * 系统当前是不是深色（"跟随系统"的唯一依据）。
     *
     * Windows：读 HKCU\...\Themes\Personalize\AppsUseLightTheme（0x0 = 深色）。
     * 其它系统：允许 -Ddsh.theme.systemDark=true/false 覆盖，便于在 Linux 上回归；
     * 都没就按亮色处理（宁可亮，也不要"读不到就锁死深色"——那正是 3.2.4 之前的症状）。
     */
    private boolean systemDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            Boolean fromRegistry = windowsAppsUseDarkTheme();
            if (fromRegistry != null) {
                return fromRegistry;
            }
        }
        String override = System.getProperty("dsh.theme.systemDark");
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return false;
    }

    /** 读 Windows 的"应用深色模式"开关；读不到返回 null（调用方决定兜底）。 */
    private static Boolean windowsAppsUseDarkTheme() {
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
            // reg.exe 正常几十毫秒返回；卡住就杀掉，绝不让 EDT 挂在这上面
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)")
                    .matcher(output);
            if (!matcher.find()) {
                return null;
            }
            return Integer.parseInt(matcher.group(1), 16) == 0;
        } catch (Exception unavailable) {
            if (process != null) {
                process.destroyForcibly();
            }
            return null;
        }
    }

    /**
     * 跟随系统：每 3 秒复查一次系统亮暗（非 system 模式直接跳过）。
     *
     * 探测本身要起一次 reg.exe（几十毫秒），所以放在后台线程；只有"真的变了"才回 EDT 播过渡。
     * 这样系统切深色时客户端会自己跟着变，不用重启 —— 3.2.4 之前这里完全没有复查。
     */
    private void startThemeWatch() {
        if (themeWatch != null) {
            return;
        }
        themeWatch = new javax.swing.Timer(3000, e -> {
            if (!"system".equals(tm.mode()) || themeProbeRunning) {
                return;
            }
            themeProbeRunning = true;
            Thread probe = new Thread(() -> {
                boolean systemIsDark = false;
                try {
                    systemIsDark = systemDark();
                } catch (RuntimeException ignored) {
                    // 探测失败就当没变化，下一轮再试
                }
                boolean dark = systemIsDark;
                SwingUtilities.invokeLater(() -> {
                    themeProbeRunning = false;
                    if ("system".equals(tm.mode())) {
                        tm.applySystemDark(dark);
                    }
                });
            }, "dsh-theme-probe");
            probe.setDaemon(true);
            probe.start();
        });
        themeWatch.start();
    }

    /** 顶部按钮：跟随系统 → 亮色 → 暗色 → 跟随系统。 */
    private void cycleThemeMode() {
        String current = tm.mode();
        String next;
        if ("system".equals(current)) {
            next = "light";
        } else if ("light".equals(current)) {
            next = "dark";
        } else {
            next = "system";
        }
        tm.setMode(next);
        syncThemeControls();
    }

    /** 当前模式的中文名（按钮文字 + 设置下拉共用）。 */
    private String themeLabel() {
        return themeModeLabel(tm.mode());
    }

    private static String themeModeLabel(String mode) {
        if ("light".equals(mode)) {
            return "亮色";
        }
        if ("dark".equals(mode)) {
            return "暗色";
        }
        return "跟随系统";
    }

    private static int themeModeIndex(String mode) {
        for (int i = 0; i < THEME_MODES.length; i++) {
            if (THEME_MODES[i].equals(mode)) {
                return i;
            }
        }
        return 0;
    }

    /** 把当前模式同步到顶部按钮和设置下拉（不当作一次用户操作）。 */
    private void syncThemeControls() {
        ModernButton button = themeBtn;
        if (button != null) {
            String label = themeLabel();
            if (!label.equals(button.getText())) {
                button.setText(label);
            }
        }
        dev.example.dshclient.ui.ChoiceField combo = themeCombo;
        if (combo != null && combo.selectedIndex() != themeModeIndex(tm.mode())) {
            syncingThemeCombo = true;
            try {
                combo.setSelectedIndex(themeModeIndex(tm.mode()));
            } finally {
                syncingThemeCombo = false;
            }
        }
    }

    /** 主题变了：从 ThemeManager 取当前令牌，刷整棵树 + 终端调色板。 */
    private void applyTheme() {
        applyThemeOf(tm.theme());
    }

    /** 应用一套具体令牌（过渡动画每帧也走这里）。 */
    private void applyThemeOf(UiTheme t) {
        this.darkMode = tm.isDarkEffective();
        getContentPane().setBackground(t.windowBg);
        themeTree(getContentPane(), t);
        if (settingsDialog != null) {
            settingsDialog.getContentPane().setBackground(t.windowBg);
            themeTree(settingsDialog.getContentPane(), t);
            settingsDialog.repaint();
        }
        status.setForeground(t.textSecondary);
        log.setBackground(t.terminalBg);
        log.setForeground(t.textPrimary);
        log.setCaretColor(t.textPrimary);
        terminal.setPalette(TerminalPalette.of(t));
        themeScrollBars(t);
        // 字体阶梯也走 ui.UiFonts：界面 Segoe UI、终端 Consolas，都带回退链
        Font uiFont = UiFonts.ui(Font.PLAIN, UiFonts.BODY_SIZE);
        Font labelFont = UiFonts.ui(Font.PLAIN, UiFonts.LABEL_SIZE);
        applyFonts(getContentPane(), uiFont, labelFont);
        syncThemeControls();
        pushPageTheme();
        repaint();
    }

    /** 客户端定了亮暗就同步给内嵌 DSH 页面（CEF 的 prefers-color-scheme 不可靠时，页面也能对上）。 */
    private void pushPageTheme() {
        JcefView view = embedDsh;
        if (view == null) {
            return;
        }
        Boolean want = tm.isDarkEffective();
        if (want.equals(pushedPageDark)) {
            return;
        }
        pushedPageDark = want;
        view.setPageDark(want);
    }

    /**
     * 滚动条：Metal 的默认外观是浅色，深色主题下像一条白杠。
     * 改 UIManager 后必须对每个 JScrollBar 重新 updateUI() 才会生效。
     */
    private void themeScrollBars(UiTheme t) {
        javax.swing.UIManager.put("ScrollBar.thumb", t.scrollThumb);
        javax.swing.UIManager.put("ScrollBar.track", t.scrollTrack);
        javax.swing.UIManager.put("ScrollBar.thumbHighlight", t.scrollThumbHover);
        javax.swing.UIManager.put("ScrollBar.thumbDarkShadow", t.scrollTrack);
        javax.swing.UIManager.put("ScrollBar.thumbShadow", t.scrollTrack);
        refreshScrollBars(getContentPane());
    }

    private void refreshScrollBars(java.awt.Component c) {
        if (c instanceof javax.swing.JScrollBar) {
            ((javax.swing.JScrollBar) c).updateUI();
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                refreshScrollBars(k);
            }
        }
    }

    /** 递归统一字体（不让 Metal 默认的 12px Dialog 混进来）。 */
    private void applyFonts(java.awt.Component c, Font body, Font label) {
        if (c instanceof JLabel) {
            c.setFont(label);
        } else if (c instanceof javax.swing.AbstractButton || c instanceof JTextField
                || c instanceof javax.swing.JComboBox || c instanceof JTextArea) {
            c.setFont(body);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                applyFonts(k, body, label);
            }
        }
    }

    /** 递归换色。Swing 没有全局变量，只能一个个刷；控件不多，一次遍历够快。 */
    private void themeTree(java.awt.Component c, UiTheme t) {
        if (c instanceof JPanel) {
            c.setBackground(t.panelBg);
            if (((JPanel) c).getBorder() instanceof javax.swing.border.TitledBorder) {
                javax.swing.border.TitledBorder tb =
                        (javax.swing.border.TitledBorder) ((JPanel) c).getBorder();
                tb.setTitleColor(t.textSecondary);
                tb.setBorder(BorderFactory.createLineBorder(t.separator));
            }
        } else if (c instanceof javax.swing.JScrollPane) {
            c.setBackground(t.windowBg);
        } else if (c instanceof JLabel) {
            c.setForeground(c == status ? t.textSecondary : t.textPrimary);
        } else if (c instanceof javax.swing.JCheckBox) {
            c.setBackground(t.panelBg);
            c.setForeground(t.textPrimary);
        } else if (c instanceof JTextField) {
            JTextField f = (JTextField) c;
            f.setBackground(t.inputBg);
            f.setForeground(t.textPrimary);
            f.setCaretColor(t.textPrimary);
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(t.border),
                    BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        } else if (c instanceof javax.swing.JButton && !(c instanceof ModernButton)) {
            JButton b = (JButton) c;
            b.setBackground(t.controlHover);
            b.setForeground(t.textPrimary);
            b.setFocusPainted(false);
        } else if (c instanceof javax.swing.JComboBox) {
            c.setBackground(t.inputBg);
            c.setForeground(t.textPrimary);
        } else if (c instanceof JTextArea) {
            c.setBackground(t.terminalBg);
            c.setForeground(t.textPrimary);
        } else if (c instanceof javax.swing.JScrollBar) {
            c.setBackground(t.scrollTrack);
            c.setForeground(t.scrollThumb);
        } else if (c instanceof JTabbedPane) {
            c.setBackground(t.panelBg);
            c.setForeground(t.textPrimary);
        } else if (c instanceof javax.swing.JSplitPane) {
            c.setBackground(t.windowBg);
        }
        if (c instanceof java.awt.Container) {
            for (java.awt.Component k : ((java.awt.Container) c).getComponents()) {
                themeTree(k, t);
            }
        }
    }

    /**
     * 左侧参数区（v2）：拆成「连接 / WorkBuddy / 外观」三张渐变卡片。
     * 卡片由 ui.GradientCard 自绘（渐变底 + 12px 圆角 + 1px 描边 + 顶部高光）。
     */
    /**
     * 设置表单（v3）：交给 ui.SettingsPanel 排版 —— 只有它知道怎么让输入框/下拉/复选
     * 在暗色下保持深底、亮色下保持浅底，以及长标签怎么换行不截断。
     */
    private JPanel configPanel() {
        dev.example.dshclient.ui.SettingsPanel sp = new dev.example.dshclient.ui.SettingsPanel(tm);
        sp.setOnClose(() -> {
            if (settingsDialog != null) {
                settingsDialog.dispose();
            }
        });

        dev.example.dshclient.ui.SettingsPanel.Group c1 = sp.group("连接");
        c1.row("WorkBuddy 走哪条", wbRouteBox);
        c1.row("服务器", hostField);
        c1.row("SSH 端口", portField);
        c1.row("用户名", userField);
        c1.row("私钥文件", keyRowPanel());
        c1.row("本地端口", localPortField);
        c1.row("Token", tokenField);

        dev.example.dshclient.ui.SettingsPanel.Group c2 = sp.group("WorkBuddy");
        c2.toggle("WorkBuddy 桌面", "同一个 jar 再开一个 WorkBuddy 桌面窗口", wbEnabledBox);
        c2.row("WB 本机转发端口", wbLocalPortField);
        c2.row("WB VNC 密码", wbPasswordField);
        c2.row("WB 域名入口", wbWebUrlField);
        c2.row("WB 容器名", wbContainerField);
        c2.toggle("WB 自动开窗", "打开客户端时自动弹出 WB 窗口", wbAutoOpenBox);
        c2.row("WB 系统 ssh", wbSshExeField);
        c2.row("WB ssh 私钥", wbSshKeyField);

        dev.example.dshclient.ui.SettingsPanel.Group c3 = sp.group("外观");
        c3.row("主题", themeCombo);
        c3.toggle("内嵌浏览器", JcefGate.available()
                ? "把 DSH / WorkBuddy 画进页签（JCEF）" : "cef\\ 目录缺 jar，当前不可用", embedBrowserBox);

        c3.hint("快捷键：Ctrl+Shift+C / V 复制粘贴，Ctrl+滚轮改字号，PgUp 翻历史。");
        return sp;
    }

    /** 私钥一行：文本框 + 选择按钮。 */
    private JPanel keyRowPanel() {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        // 走 ModernField 包装：SettingsPanel 只会给"直接传进去的 JTextField"套外观，
        // 这里多包了一层 JPanel，必须自己套，否则它是唯一一个白底输入框。
        row.add(new dev.example.dshclient.ui.ModernField(keyField, tm), BorderLayout.CENTER);
        ModernButton pick = new ModernButton("…", ModernButton.Kind.SECONDARY, tm);
        pick.setToolTipText("选择私钥文件");
        pick.addActionListener(e -> {
            File start2 = new File(keyField.getText().trim()).getParentFile();
            JFileChooser fc = new JFileChooser(start2 != null && start2.isDirectory()
                    ? start2 : new File(DshClient.sshDir()));
            fc.setFileHidingEnabled(false);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        row.add(pick, BorderLayout.EAST);
        return row;
    }

    private JPanel logPanel() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setFocusable(true);
        log.setMargin(new java.awt.Insets(6, 8, 6, 8));
        // 不可编辑的 JTextArea 默认没有右键菜单；Ctrl+Shift+C 也不认（那是终端页签的手势）。
        // 这里两样都补上，让"选中 → 右键/Ctrl+C/Ctrl+Shift+C"三条路都能复制。
        bindLogCopyKeys();
        logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        installLogPopup();
        JPanel p = new JPanel(new BorderLayout());
        p.add(logScroll, BorderLayout.CENTER);
        return p;
    }

    /** Ctrl+C / Ctrl+Shift+C / Ctrl+Shift+A：日志页签里也认这几个手势。 */
    private void bindLogCopyKeys() {
        int ctrl = java.awt.event.InputEvent.CTRL_DOWN_MASK;
        int shift = java.awt.event.InputEvent.SHIFT_DOWN_MASK;
        javax.swing.Action copyAction = new javax.swing.AbstractAction("复制日志") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                copyLogSelection();
            }
        };
        log.getActionMap().put("dsh-log-copy", copyAction);
        log.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, ctrl), "dsh-log-copy");
        log.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_C, ctrl | shift), "dsh-log-copy");
        log.getActionMap().put("dsh-log-selectall", new javax.swing.AbstractAction("全选") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                log.selectAll();
            }
        });
        log.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_A, ctrl | shift), "dsh-log-selectall");
    }

    /** 日志页签右键菜单：复制 / 全选 / 清空。 */
    private void installLogPopup() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem copy = new javax.swing.JMenuItem("复制");
        copy.addActionListener(e -> copyLogSelection());
        javax.swing.JMenuItem selectAll = new javax.swing.JMenuItem("全选");
        selectAll.addActionListener(e -> log.selectAll());
        javax.swing.JMenuItem clear = new javax.swing.JMenuItem("清空");
        clear.addActionListener(e -> {
            log.setText("");
            logSelBuffer = "";
            logSelStart = -1;
            logSelEnd = -1;
        });
        menu.add(copy);
        menu.add(selectAll);
        menu.addSeparator();
        menu.add(clear);

        log.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                log.requestFocusInWindow();
                if (log.getSelectedText() == null || log.getSelectedText().isEmpty()) {
                    restoreLogSelection();     // 右键清掉选区时还原，用户看得见要复制什么
                }
                menu.show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    /** 复制当前选区；选区被右键/刷新清掉时退回"刚才记下的那段"。 */
    private void copyLogSelection() {
        String text = log.getSelectedText();
        if (text == null || text.isEmpty()) {
            text = logSelBuffer;
        }
        if (text == null || text.isEmpty()) {
            return;
        }
        try {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(text), null);
        } catch (Exception ignore) {
            // 剪贴板被别的程序占着时忽略
        }
    }

    private void rememberLogSelection() {
        String text = log.getSelectedText();
        if (text != null && !text.isEmpty()) {
            logSelBuffer = text;
            logSelStart = log.getSelectionStart();
            logSelEnd = log.getSelectionEnd();
        }
    }

    private void restoreLogSelection() {
        int len = log.getDocument().getLength();
        if (logSelStart >= 0 && logSelEnd > logSelStart && logSelEnd <= len) {
            log.select(logSelStart, logSelEnd);
        }
    }

    // ---------------- 界面手动选路（1.26.0） ----------------

    private static int routeIndexOf(String route) {
        String r = route == null ? "auto" : route.trim().toLowerCase();
        switch (r) {
            case "direct": case "ipv6": return 1;
            case "tunnel": return 2;
            case "web": return 3;
            default: return 0;
        }
    }

    private static String routeIdOf(int idx) {
        switch (idx) {
            case 1: return "direct";
            case 2: return "tunnel";
            case 3: return "web";
            default: return "auto";
        }
    }

    private static int wbRouteIndexOf(String wbRoute) {
        String r = wbRoute == null ? "auto" : wbRoute.trim().toLowerCase();
        switch (r) {
            case "direct": case "ipv6": return 1;
            case "domain": case "web": case "cf": case "https": return 2;
            case "ssh": case "tunnel": return 3;
            default: return 0;
        }
    }

    private static String wbRouteIdOf(int idx) {
        switch (idx) {
            case 1: return "direct";
            case 2: return "domain";
            case 3: return "ssh";
            default: return "auto";
        }
    }

    /**
     * 你在界面上选了哪条 DSH 路线：写回设置 → **立刻按它重连**。
     * 选"自动"时会重新做一次判读（含 RTT）并重新实测比较，所以 CF 抖动时可以手动钉住某一条。
     */
    private void applyRouteChoice(String id) {
        cfg.route = id;
        routeAuto = "auto".equals(id) || "probe".equals(id);
        logLine("★ 你在界面上选了路线：" + id + (routeAuto ? "（自动：会重新判读 + 实测）" : "（钉死这条，不再自动切）"));
        if (route == RouteProbe.Route.WEB && !"web".equals(id)) {
            logLine("（之前跑的是网页隧道，本进程没有 SSH 会话；重连会新建）");
        }
        saveSettings();
        new Thread(() -> {
            try {
                if (routeAuto) {
                    setRoute(DshClient.pickAutoRoute(cfg), "界面选了自动");
                } else {
                    setRoute(RouteProbe.Route.of(id), "界面选了 " + id);
                }
                if (!connect()) logLine("连接失败，可以再点『重新连接』");
            } catch (Exception e) {
                logLine("切换路线失败：" + e.getMessage());
            }
        }, "dsh-route-apply").start();
    }

    /** WorkBuddy 路径选择：写回设置，下次开窗生效（不用重连）。 */
    private void applyWbRouteChoice(String id) {
        cfg.wbRoute = id;
        logLine("★ WorkBuddy 改走：" + id + "（下次点「打开 WorkBuddy 桌面」生效；auto = 四条/三条实测挑最快）");
        saveSettings();
        refreshWbState();
    }

    /** 按路线造一条会话（不 start）：同一条会话带两个转发 —— 3080（DSH）+ 6080（WorkBuddy）。 */
    private SshTunnel buildTunnel(RouteProbe.Route r) {
        boolean wbOn = DshClient.wbEnabled(cfg);
        int wbLocal = wbOn ? cfg.wbLocalPort : 0;
        int wbRemote = wbOn ? cfg.wbRemotePort : 0;
        int port = Integer.parseInt(portField.getText().trim());
        int lp = Integer.parseInt(localPortField.getText().trim());
        String user = userField.getText().trim();
        String key = keyField.getText().trim();
        if (r == RouteProbe.Route.TUNNEL) {
            // 走 WS 隧道时 host/port 只是 JSch 的记账，真正连的是 tunnelWsUrl
            return new SshTunnel(cfg.tunnelHost, port, user, key, lp, cfg.remotePort, wbLocal, wbRemote, cfg.tunnelWsUrl);
        }
        // direct：SSH 直连服务器 IPv6:22
        return new SshTunnel(effectiveHost(), port, user, key, lp, cfg.remotePort, wbLocal, wbRemote, null);
    }

    /** 造一条**已连好**的会话（测速决策要用）。 */
    private SshTunnel makeTunnel(RouteProbe.Route r) throws Exception {
        SshTunnel x = buildTunnel(r);
        x.start();
        return x;
    }

    /**
     * 连隧道（幂等：已连就直接返回 true）。web 路线不建 SSH，直接算成功。
     *
     * <p>3.1.0 起是 synchronized：看门狗、"重新连接"按钮、界面切换路线可能同时进来，
     * 以前两条并发连接都去 bind 同一个 3080，后到的那条必然失败，却被当成"这条路线不行"，
     * 于是自动回落去切路线 —— 表现出来就是"时断时续"。
     */
    private synchronized boolean connect() {
        try {
            if (route == RouteProbe.Route.WEB) {
                setStatus("网页隧道（不经 SSH）· " + DshClient.webUrl(cfg), false);
                return true;
            }
            if (tunnel != null && tunnel.isAlive()) {
                setStatus("已连接", false);
                return true;
            }
            if (tunnel != null) tunnel.close();
            setStatus("连接中…", false);
            logLine("连接 " + effectiveHost() + ":" + portField.getText().trim()
                    + "，把远端 127.0.0.1:" + cfg.remotePort + " 转发到本机 "
                    + localPortField.getText().trim() + " …");
            // 1.12.0：auto 下**每条连接都实测速度**（直连偏慢就把域名也量一遍，取快的）
            SshTunnel t;
            if (routeAuto && !speedDone && cfg.speedMinKbps > 0) {
                DshClient.RouteSpeed rs = DshClient.pickBySpeed(cfg, route, this::makeTunnel, this::logLine);
                lastKbps = rs.kbps;
                setRoute(rs.route, "按各条连接的实测速度选的");
                speedDone = true;
                if (rs.route == RouteProbe.Route.WEB) {
                    logLine("→ 实测判定：SSH 那两条带不动首屏（手机端 10-13 也是这么判的），改用网页隧道（不经 SSH）");
                    logLine("　※ 这条路上 WorkBuddy 仍走 wb.example.com；容器启停会按需借一条 CF 会话");
                    logLine("　※ 已知代价：网页隧道里**页面内下载**可能中途断（那是 10-10 的实测，要下载时用 --route tunnel）");
                    return connect();          // 交给上面的 WEB 分支处理（不再建 SSH）
                }
                t = rs.tunnel;
            } else {
                t = makeTunnel(route);
            }
            tunnel = t;
            tunnelWasAlive = true;
            reconnectTries = 0;
            reconnectNotBefore = 0;
            connectedAt = System.currentTimeMillis();
            setStatus("已连接 · " + t.url(null), false);   // refreshStatus 会把路线与实测速度拼上
            logLine("SSH 转发就绪（DSH 界面）→ " + t.url(null)
                    + "　※ 这条是「端口转发」：任何路线（直连/域名）都靠它把服务器上的 loopback 端口搬到本机");
            if (DshClient.wbEnabled(cfg)) {
                if (t.boundWbPort() > 0) {
                    String sc = t.lastWbSelfCheck();
                    boolean wbOk = sc != null && sc.startsWith("HTTP/");
                    logLine("WorkBuddy 的 SSH 转发就绪（③ 兜底）→ http://127.0.0.1:" + t.boundWbPort()
                            + "/" + (wbOk ? "（自检：" + sc + "）" : "（自检没过）"));
                    if (!wbOk && !wbPermitNoteShown) {
                        wbPermitNoteShown = true;
                        logLine("　⚠️ " + String.valueOf(sc).replace('\n', ' '));
                        logLine("　※ 服务器那把 key 的 permitopen 只放了 3080。一次性修法（服务器上改 authorized_keys 那一行）："
                                + "permitopen=\"127.0.0.1:3080\",permitopen=\"127.0.0.1:6080\"（不用重启 sshd）。"
                                + "DSH 的 3080 转发不受影响，这条只是 WorkBuddy 走 SSH 转发时会用到。");
                    }
                    logLine("　※ 它只是备好了；点「打开 WorkBuddy 桌面」时按 " + wbOrderText() + " 各实测一次，挑最快的");
                } else if (!t.wbForwardError().isEmpty()) {
                    logLine("⚠️ " + t.wbForwardError());
                }
            }
            saveSettings();
            return true;
        } catch (Exception e) {
            logLine("⚠️ " + String.valueOf(e.getMessage()).replace('\n', ' '));
            // 1.11.0：auto 挑的路线连不上时**按顺序换下一条**（direct → tunnel → web），不原地失败。
            if (routeAuto) {
                if (route == RouteProbe.Route.DIRECT) {
                    setRoute(RouteProbe.Route.TUNNEL, "SSH 直连连不上");
                    return connect();
                }
                if (route == RouteProbe.Route.TUNNEL) {
                    setRoute(RouteProbe.Route.WEB, "CF 隧道也连不上");
                    setStatus("已退到网页隧道", true);
                    logLine("→ 域名（CF 隧道）也连不上，退到网页隧道：" + DshClient.webUrl(cfg));
                    logLine("（这条路没有 SSH 通道：WorkBuddy 会自动改走 wb.example.com 那个域名入口）");
                    if (cfg.openBrowser) openDshWindow(DshClient.webUrl(cfg));
                    return true;
                }
            }
            setStatus("连接失败", true);
            logLine("❌ " + e.getMessage());
            return false;
        }
    }

    /**
     * 打开 WorkBuddy 桌面窗口（1.10.0：域名优先）。
     *
     * 三条路，按顺序挑**确实能用**的那条，每条都自检（真发一条请求）：
     *   ① 域名 `https://wb.example.com/vnc.html`（Cloudflare ingress，不依赖 IPv6、不占 SSH 转发位）
     *   ② 同一条 SSH 会话的 6080 转发（127.0.0.1:6080）
     *   ③ 直连服务器 IPv6 上的 6080
     * 三条都不通就打印**为什么**，不开空窗口。
     *
     * 容器状态/启停仍然需要 SSH（SFTP 代理），所以先 best-effort 拿一条会话；
     * 拿不到也不影响走域名那条开窗口。
     */
    private void openWorkbuddyWindow() {
        if (hubOpened && mergeWindowsBox.isSelected()) {
            logLine("WorkBuddy 在【合并窗口】的第二个标签页里 → 切过去即可（不另开窗口）");
            return;
        }
        if (!DshClient.wbEnabled(cfg)) {
            logLine("WorkBuddy 桌面窗口已关闭（--no-workbuddy / --wb-route off）");
            return;
        }
        cfg.wbWebUrl = wbWebUrlField.getText().trim();   // 界面上的值优先（空 = 关掉域名那条）
        String cname = wbContainer();
        String pwd = wbPasswordField.getText().trim();
        String kind = DshClient.wbRouteKind(cfg, route);

        // 先 best-effort 拿一条 SSH 会话：查/启动容器要用，SSH 转发那条也要用。
        // 借不到不致命 —— 域名那条不需要 SSH。
        SshTunnel t = tunnel != null && tunnel.isAlive() ? tunnel : null;
        if (t == null && route == RouteProbe.Route.WEB) {
            t = webRouteWbTunnel();
        }
        if (t != null) {
            String st = t.workbuddySwitch("status", cname, DshClient.resolveSshExe(cfg),
                    DshClient.resolveSshKey(cfg), cfg.hostResolved, cfg.port, cfg.user);
            if (st.startsWith("false")) {
                logLine("容器 " + cname + " 没在跑，先启动（最多等 40 秒）…");
                st = t.workbuddySwitch("start", cname, DshClient.resolveSshExe(cfg),
                        DshClient.resolveSshKey(cfg), cfg.hostResolved, cfg.port, cfg.user);
            }
            logLine("容器 " + cname + "：running=" + st);
            refreshWbState();
        }
        // 端口绑上 ≠ 能用：permitopen 被拒时端口照样绑、连接才被关。所以要求自检拿到 HTTP 状态行。
        boolean sshOk = t != null && t.boundWbPort() > 0
                && t.lastWbSelfCheck() != null && t.lastWbSelfCheck().startsWith("HTTP/");

        // ---- WorkBuddy 走哪条（2.0.0：**跟随 DSH 那条路**）----
        //   DSH 建了 SSH 会话（direct/tunnel）→ WB = ③ SSH 转发（同一条会话、同一条网络路径）
        //   DSH 走 web（没有 SSH）            → WB = ② 域名 wb.example.com（同样经 CF、不经 SSH）
        // 绑定那条先试；真不通才依次回落后面的（会写明原因）。显式 --wb-route X：只试 X。
        java.util.List<String> cands = DshClient.wbCandidates(cfg, route);
        logLine(DshClient.wbBindNote(route));
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < cands.size(); i++) {
            if (i > 0) order.append(" → ");
            order.append(wbLabel(cands.get(i)));
        }
        logLine("WorkBuddy 顺序：" + order);

        // 绑定那条先试，不通才回落 —— 每条都真发一次请求（顺便报速度）
        int maxBytes = cfg.speedBytes;
        String bestLabel = null;
        String bestUrl = null;
        DshClient.Speed bestSpeed = null;
        for (String c : cands) {
            DshClient.Speed sp;
            String label;
            String url;
            if (c.equals("direct")) {
                label = "① 直连 IPv6 " + DshClient.wbDirectHostShown(cfg);
                sp = DshClient.wbPathSpeed(cfg.hostResolved, cfg.wbDirectPort, false, maxBytes, 6000);
                url = DshClient.wbDirectUrl(cfg);
            } else if (c.equals("domain")) {
                String wh = DshClient.wbWebHost(cfg);
                label = "② 域名 " + DshClient.wbWebBase(cfg);
                if (wh.isEmpty()) {
                    sp = new DshClient.Speed();
                    sp.err = "没配域名入口";
                } else {
                    sp = DshClient.wbPathSpeed(wh, 443, true, maxBytes, 6000);
                }
                url = DshClient.wbWebFullUrl(cfg);
            } else {
                if (t == null || t.boundWbPort() <= 0) {
                    logLine("　③ SSH 转发（127.0.0.1:" + cfg.wbLocalPort + "）不可用："
                            + (t == null ? "没有可用 SSH 会话" : (t.wbForwardError().isEmpty()
                            ? "6080 转发没建起来" : t.wbForwardError())) + " → 试下一条");
                    continue;
                }
                label = "③ SSH 转发 127.0.0.1:" + t.boundWbPort();
                sp = DshClient.wbPathSpeed("127.0.0.1", t.boundWbPort(), false, maxBytes, 6000);
                url = t.workbuddyUrl(pwd);
            }
            logLine("　" + label + " → " + sp.text());
            if (sp.ok()) {
                bestLabel = label;
                bestUrl = url;
                bestSpeed = sp;
                break;                       // 绑定那条通了就用它，不再比其它
            }
        }
        if (bestUrl != null) {
            if (!openWebSurface(bestUrl, true)) {
                openInAppWindow(bestUrl, true);
            }
            logLine("已打开 WorkBuddy 桌面（" + bestLabel + " · " + bestSpeed.text() + "）："
                    + bestUrl.replaceAll("password=[^&]*", "password=…"));
            return;
        }
        logLine("❌ 三条路都不通，不开空窗口（顺序：直连 IPv6 → 域名 → SSH 转发）。排查顺序：wb.example.com 能不能打开 → "
                + "（服务器上）cloudflared 与容器在不在 → --route tunnel 重新连一次。");
    }

    /**
     * 网页隧道（WEB 路线）下专用：临时经 CF 单开一条**只做 6080 转发**的 SSH 会话。
     *
     * WEB 路线整条进程不建 SSH（省掉 SSH 开销是它的卖点），但 WorkBuddy 与容器启停都要 SSH 通道；
     * 1.8.0 的做法是"没有隧道就硬开直连地址"—— 在直连本来就不通的网络里必然开出一个空窗口。
     * 现在改成按需借一条：建不起来就说清楚为什么。
     */
    private SshTunnel webRouteWbTunnel() {
        if (wbOnlyTunnel != null && wbOnlyTunnel.isAlive()) return wbOnlyTunnel;
        logLine("网页隧道没有 SSH 通道 → 临时经 CF 单开一条只做 6080 转发的会话…");
        SshTunnel t = new SshTunnel(cfg.tunnelHost, cfg.port, cfg.user, cfg.key,
                0, 0, cfg.wbLocalPort, cfg.wbRemotePort, cfg.tunnelWsUrl);
        try {
            t.start();
        } catch (Exception e) {
            logLine("❌ 这条也没起来：" + String.valueOf(e.getMessage()).replace('\n', ' '));
            logLine("（WorkBuddy 需要一条 SSH 通道；如果 CF 隧道本身不通，改用 --route direct 或 --route tunnel）");
            return null;
        }
        wbOnlyTunnel = t;
        logLine("WB 转发就绪 → " + t.workbuddyUrl(wbPasswordField.getText().trim())
                + "（自检：" + t.lastWbSelfCheck() + "）");
        return t;
    }

    /** 当前配置下的 WorkBuddy 候选顺序（一行，日志用）。 */
    private String wbOrderText() {
        java.util.List<String> cs = DshClient.wbCandidates(cfg, route);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cs.size(); i++) {
            if (i > 0) sb.append(" → ");
            sb.append(wbLabel(cs.get(i)));
        }
        return sb.toString();
    }

    /** 三条路的中文标签（日志用）。 */
    private static String wbLabel(String kind) {
        switch (kind) {
            case "direct": return "① 直连 IPv6 [服务器IPv6]:6080";
            case "domain": return "② 域名 wb.example.com";
            default:       return "③ SSH 转发 127.0.0.1:6080";
        }
    }

    /** 当前可用于 WorkBuddy 的会话：主隧道优先，网页隧道下用那条临时的。 */
    private SshTunnel wbSession() {
        if (tunnel != null && tunnel.isAlive()) return tunnel;
        if (wbOnlyTunnel != null && wbOnlyTunnel.isAlive()) return wbOnlyTunnel;
        return null;
    }

    // ---------------- WorkBuddy 容器开关（1.5.0） ----------------

    /** 容器名（界面填了就用界面上的）。 */
    private String wbContainer() {
        String s = wbContainerField.getText().trim();
        return s.isEmpty() ? cfg.wbContainer : s;
    }

    private void setWbState(String text) {
        SwingUtilities.invokeLater(() -> wbStateLabel.setText(text));
    }

    /** 刷新容器状态（后台线程调用）。 */
    private void refreshWbState() {
        SshTunnel t = wbSession();
        if (t == null) {
            setWbState(route == RouteProbe.Route.WEB
                    ? "WorkBuddy 容器：未知（网页隧道没有 SSH；点『打开 WorkBuddy 桌面』会按需开一条）"
                    : "WorkBuddy 容器：未知（隧道没连上）");
            return;
        }
        String out = t.workbuddySwitch("status", wbContainer(), DshClient.resolveSshExe(cfg),
                DshClient.resolveSshKey(cfg), cfg.hostResolved, cfg.port, cfg.user);
        String shown = out.startsWith("true") ? "运行中" : out.startsWith("false") ? "已停止" : out;
        setWbState("WorkBuddy 容器：" + shown);
    }

    /** 启停容器（后台线程调用）。经 SFTP 代理 —— 受限 key（command="internal-sftp"）也能用。 */
    private void wbAction(String cmd) {
        SshTunnel t = wbSession();
        if (t == null && route == RouteProbe.Route.WEB) t = webRouteWbTunnel();   // 网页隧道下按需借一条
        if (t == null) {
            logLine("隧道没连上，先点『重新连接』");
            return;
        }
        setWbState("WorkBuddy 容器：" + ("stop".equals(cmd) ? "停止中…" : "启动中…"));
        String out = t.workbuddySwitch(cmd, wbContainer(), DshClient.resolveSshExe(cfg),
                DshClient.resolveSshKey(cfg), cfg.hostResolved, cfg.port, cfg.user);
        logLine("容器 " + cmd + " → " + out);
        refreshWbState();
    }

    /**
     * 看门狗：每 3 秒看一眼会话，断了自动重连。
     *
     * <p>3.1.0 修「连接时断时续、一直在重连」的两个自伤点：
     * <ul>
     *   <li><b>并发重连</b>：以前每 3 秒只要看到 tunnel 死了就再起一条线程，而 connect() 要 15 秒才超时，
     *       于是同一时刻可能有好几条在连同一条 3080 —— 后连的必然 "本地端口建立转发失败"，
     *       失败又触发路线回落（direct→tunnel→web），看起来就是"一直在断、一直在切"。
     *       现在整个重连是单飞的，connect() 也加了同步。</li>
     *   <li><b>失败无退避</b>：连不上就每 3 秒重试一遍，日志刷屏、把服务器也一起打。
     *       现在 2→4→8→16→30 秒退避，连上后清零。</li>
     * </ul>
     */
    private void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new javax.swing.Timer(3_000, e -> {
            // 网页隧道路线没有 SSH 会话可守（以前这里会一直刷"已重连"）
            if (stopped || tunnel == null || route == RouteProbe.Route.WEB) return;
            if (tunnel.isAlive()) {
                tunnelWasAlive = true;
                return;
            }
            if (!reconnecting.compareAndSet(false, true)) {
                return;                      // 已经有一条重连线程在跑（含退避等待）
            }
            boolean justLost = tunnelWasAlive;
            tunnelWasAlive = false;
            if (justLost) {
                onTunnelLost();
            }
            startReconnectThread();
        });
        watchdog.start();
        startForwardProbe();
    }

    /**
     * 3.2.2：启动那一刻没有全局 IPv6 时，隔 15 秒复查一次，最多 2 分钟。
     *
     * <p>为什么需要：Windows 刚连上 Wi-Fi 时，IPv6 地址往往比 IPv4 晚几秒到几十秒才出现。
     * 客户端只在启动瞬间判一次，就会一直把路线钉在隧道上 —— 而隧道传大文件会断。
     * 这里等它出现，出现了就把自动路线改回直连。
     */
    private void startIpv6Watch() {
        if (ipv6Watch != null) return;
        final int[] ticks = {0};
        ipv6Watch = new javax.swing.Timer(15_000, e -> {
            ticks[0]++;
            String[] v6 = RouteProbe.findGlobalIpv6();
            if (v6 != null) {
                ((javax.swing.Timer) e.getSource()).stop();
                logLine("★ 本机出现了全局 IPv6：" + v6[0] + " → " + v6[1]);
                if (routeAuto && route == RouteProbe.Route.TUNNEL) {
                    setRoute(RouteProbe.Route.DIRECT, "本机后来拿到了全局 IPv6，改走直连（隧道传大文件会断）");
                }
                return;
            }
            if (ticks[0] >= 8) {
                ((javax.swing.Timer) e.getSource()).stop();
                logLine("本机始终没有全局 IPv6（已复查 2 分钟）。要去直连得先让 Windows 拿到 IPv6："
                        + "看 设置 → 网络 → WLAN → IPv6 是否有公网 IPv6 地址。");
            }
        });
        ipv6Watch.start();
    }

    /** 刚发现掉线时打一次日志/计数/切路线；重试期间不再重复。 */
    private void onTunnelLost() {
        long aliveMs = connectedAt > 0 ? System.currentTimeMillis() - connectedAt : -1;
        // 10 分钟窗口内的掉线次数：钉死路线时给一句"该换路了"，别让人对着刷屏的日志发呆
        long now = System.currentTimeMillis();
        if (now - dropWindowStart > 600_000L) {
            dropWindowStart = now;
            dropsInWindow = 0;
        }
        dropsInWindow++;
        if (!routeAuto && dropsInWindow == 3) {
            logLine("⚠️ 这条钉死的路线 10 分钟内已经断了 3 次（" + routeTitle(route)
                    + "）。建议改成「自动」让它自己回落，或在设置里换一条。");
        }
        // 连稳 5 分钟以上才把"掉线计数"清零 —— 否则偶发一次就白记
        if (aliveMs > 300_000) directDrops = 0;
        if (routeAuto && route == RouteProbe.Route.DIRECT) {
            directDrops++;
            logLine("SSH 直连断了（本次运行第 " + directDrops + " 次"
                    + (aliveMs >= 0 ? "，这次只撑了 " + (aliveMs / 1000) + " 秒" : "") + "）");
            // 1.14.0：刚连上就断 = 这条路明显不行，**第一次就切**，不用等第二次
            // 3.2.1：但隧道已经"烧掉"（见 tunnelBurned）时不再切回去，否则两条路互相顶，来回跳。
            if (!tunnelBurned && (directDrops >= 2 || (aliveMs >= 0 && aliveMs < 60_000))) {
                setRoute(RouteProbe.Route.TUNNEL,
                        aliveMs >= 0 && aliveMs < 60_000 ? "直连只撑了 " + (aliveMs / 1000) + " 秒"
                                : "直连掉了 " + directDrops + " 次线");
            }
        } else {
            logLine("隧道断了，正在自动重连…");
            // 3.2.1：隧道（SSH over WebSocket）连着掉 = 这条路的承载层不稳（大文件上传实测必断）。
            // 本机有全局 IPv6 时改走直连：直连是纯 TCP，传多大的文件都不会被"帧突发"弄断。
            if (routeAuto && route == RouteProbe.Route.TUNNEL && dropsInWindow >= 2
                    && RouteProbe.hasGlobalIpv6()) {
                tunnelBurned = true;
                setRoute(RouteProbe.Route.DIRECT,
                        "隧道 10 分钟内掉了 " + dropsInWindow + " 次，改走 IPv6 直连（本次运行不再切回隧道）");
            }
        }
        // 3.1.1：把"断的时候是什么状态"记下来 —— 只看"断了"没法判断是对端 reset、keepalive 超时，
        // 还是本地转发被回收。真正的底层原因在 ~/.dsh-client.log 的 [jsch] 行里。
        if (tunnel != null) {
            logLine("　※ 会话诊断：server=" + tunnel.serverVersion()
                    + "，本地端口=" + (tunnel.boundPort() > 0 ? tunnel.boundPort() : -1)
                    + "，上次连上后撑了 " + (aliveMs >= 0 ? (aliveMs / 1000) + " 秒" : "未知"));
            // 3.2.0：WS 那一层到底发生了什么（收/发字节、关闭 code/reason、发送错误）。
            // 断线到底是"链路把 WS 关了"还是"我们自己掐的"，这一行能直接看出来。
            String td = tunnel.transportDiagnostics();
            if (td != null && !td.isEmpty()) {
                logLine("　※ 承载层（WS）：" + td);
            }
        }
    }

    /**
     * 转发探活：每 5 秒从本机端口发一条最小 HTTP 请求。
     *
     * <p>为什么需要：JSch 的 {@code isConnected()} 只反映 TCP/SSH 层；中间设备把这条流"静默回收"时，
     * 它可能还报"连着"，而页面其实一个字节都收不到 —— 表现就是"没断但打不开"。
     *
     * <p><b>3.2.0 修的自伤点</b>：上传大文件时整条隧道都在搬字节，探活请求排在后面超时是正常的；
     * 老逻辑连续 6 次（约 30 秒）就 {@code t.close()}，等于"用户一传图，客户端自己把好会话掐了"。
     * 现在多了两道闸：
     * <ul>
     *   <li>失败时先看 {@link SshTunnel#transportBytes()}：字节还在涨 = 会话活着，这次失败不算数；</li>
     *   <li>真要判死，阈值从 6 次放宽到 12 次（约 60 秒），且探活请求本身也是被挤的那个，本来就不该太灵。</li>
     * </ul>
     */
    private void startForwardProbe() {
        if (forwardProbe != null) return;
        forwardProbe = new java.util.Timer("dsh-forward-probe", true);
        forwardProbe.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                SshTunnel t = tunnel;
                if (stopped || t == null || route == RouteProbe.Route.WEB || !t.isAlive()) {
                    probeFails = 0;
                    return;
                }
                int p = t.boundPort() > 0 ? t.boundPort() : cfg.localPort;
                long bytesBefore = t.transportBytes();
                try {
                    SshTunnel.httpProbe("127.0.0.1", p, "/", 1_500, 4_000, "转发探活");
                    if (probeFails >= 3) {
                        logLine("转发探活恢复正常");
                    }
                    probeFails = 0;
                } catch (Exception e) {
                    // 3.2.0：字节还在涨 = 正在传文件，探活超时是被挤的，不是会话死了 —— 不计数。
                    //
                    // 为什么用 4KB 阈值而不是"有变化"：探活请求自己也会往隧道里写一百多个字节，
                    // 用"有变化"的话，哪怕 WS 已经死了、只是本地把字节收进了发送缓冲，也一样算"在动"，
                    // 结果就是永远判不出死会话（老的"没断但打不开"又回来了）。
                    // 真在传文件时一帧就是 16KB，拿 4KB 当门槛两边都能分开。
                    if (t.transportBytes() - bytesBefore > 4 * 1024) {
                        if (probeFails > 0) probeFails = 0;
                        return;
                    }
                    probeFails++;
                    if (probeFails == 3) {
                        logLine("⚠️ 转发连续 3 次探活失败（SSH 会话还连着，但本地端口没数据）："
                                + String.valueOf(e.getMessage()).replace('\n', ' '));
                    } else if (probeFails >= 12 && probeFails % 3 == 0) {
                        logLine("转发连续 " + probeFails + " 次失败、隧道里也没有字节在动（约 "
                                + (probeFails * 5) + " 秒）→ 主动断开这条会话，让看门狗重连");
                        t.close();
                    }
                }
            }
        }, 5_000, 5_000);
    }

    /** 起一条重连线程（调用方已用 reconnecting 占位）。失败时退避，成功后刷新内嵌页面。 */
    private void startReconnectThread() {
        new Thread(() -> {
            try {
                long wait = reconnectNotBefore - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                if (stopped) {
                    return;
                }
                if (connect()) {
                    reconnectTries = 0;
                    reconnectNotBefore = 0;
                    tunnelWasAlive = true;
                    logLine("✅ 已重连（本地端口没变，页面会自动刷新）。");
                    refreshWbState();
                    reloadEmbeddedPage();
                } else {
                    reconnectTries++;
                    long backoff = Math.min(30_000L, 2_000L * (1L << Math.min(reconnectTries - 1, 4)));
                    reconnectNotBefore = System.currentTimeMillis() + backoff;
                    logLine("重连失败（第 " + reconnectTries + " 次），" + (backoff / 1000) + " 秒后再试");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } finally {
                reconnecting.set(false);
            }
        }, "dsh-rewatch").start();
    }

    /** 自动重连成功后把内嵌页面刷一下 —— 否则要用户自己按 F5，看着像"客户端又挂了"。 */
    private void reloadEmbeddedPage() {
        if (embedDsh == null || centerTabs == null || centerTabs.indexOf(embedDsh) < 0) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                embedDsh.reload();
                logLine("内嵌页面已自动刷新（token 没变，不用按 F5）");
            } catch (Exception ignore) {
                // 浏览器已释放时忽略
            }
        });
    }

    private void stop() {
        stopped = true;
        if (watchdog != null) watchdog.stop();
        if (forwardProbe != null) forwardProbe.cancel();
        if (tunnel != null) tunnel.close();
        if (wbOnlyTunnel != null) wbOnlyTunnel.close();
        setStatus("已断开", false);
    }

    /**
     * 给内嵌浏览器用的 DSH 地址：**先验 token，不对就现取一次**。
     *
     * <p>DSH 的 token 每次重启 `dsh web` 都会变；客户端启动时解析出来的那串可能已经过期，
     * 页面就只回一句 `dsh web authentication required; reopen the URL printed by dsh web.`。
     * 这里在打开前用 HTTP 探一下：命中这句就经 SSH 重新取 token（或从本地日志取），再返回新地址。
     */
    private String dshUrlWithFreshToken() {
        String url = tunnel != null ? tunnel.url(currentToken()) : DshClient.webUrl(cfg);
        if (probeAuthOk(url)) {
            return url;
        }
        logLine("DSH 说 token 不对，重新取一次…");
        String fresh = fetchFreshToken();
        if (fresh != null && !fresh.isEmpty()) {
            cfg.tokenResolved = fresh;
            String retry = tunnel != null ? tunnel.url(fresh) : DshClient.webUrl(cfg);
            if (probeAuthOk(retry)) {
                logLine("已用新 token 打开 DSH 界面（" + fresh.length() + " 字符）");
                return retry;
            }
            return retry;
        }
        logLine("⚠️ 取不到新 token：请在「设置 → Token」里填 `dsh web` 启动时打印的那串");
        return url;
    }

    /** HTTP 探一下：返回 true = 不含 "authentication required"。 */
    private boolean probeAuthOk(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.setRequestProperty("User-Agent", "DshClient/auth-probe");
            int code = c.getResponseCode();
            if (code >= 400) {
                return false;
            }
            try (java.io.InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n <= 0) {
                    return true;
                }
                String body = new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
                return !body.contains("authentication required");
            }
        } catch (Exception e) {
            // 网络本身不通不代表 token 有问题，别误报
            return true;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
    }

    /** 现取 token：先问服务器（走 SSH），拿不到再退回本地日志。 */
    private String fetchFreshToken() {
        try {
            RemoteInfo.Fetched f = RemoteInfo.fetch(cfg.tunnelHost, cfg.port, cfg.user, cfg.key,
                    cfg.tunnelWsUrl, 15_000);
            if (f != null && f.token != null && !f.token.isEmpty()) {
                return f.token;
            }
        } catch (Exception e) {
            logLine("经隧道取 token 失败：" + e.getMessage());
        }
        return null;
    }

    /** 开始页那句路线说明：写清"现在实际走哪条"，以及它意味着什么。 */
    private void updateRouteHint() {
        if (routeHintLabel == null) {
            return;
        }
        String extra;
        switch (routeBox.selectedIndex()) {
            case 1:  extra = "不经 Cloudflare，最快；需要服务器 22 端口可直连"; break;
            case 2:  extra = "SSH 装进 WebSocket 走 Cloudflare，绕开 22 端口封锁"; break;
            case 3:  extra = "完全不开 SSH 会话；下载会经 Cloudflare，速度受它影响"; break;
            default: extra = "启动时自动判读，失败就依次回退"; break;
        }
        // 下拉框本身已经显示了路线名，这里只写"它意味着什么"，别重复一遍
        routeHintLabel.setText(extra);
        routeHintLabel.setForeground(tm.theme().textSecondary);
    }

    private String currentToken() {
        String typed = tokenField.getText().trim();
        // auto / 空 / log[:路径] 都表示"用启动时解析出来的那个"（见 DshClient.resolveEndpoints）
        if (typed.isEmpty() || DshClient.isAuto(typed)
                || typed.equals(DshClient.TOKEN_FROM_LOG)
                || typed.startsWith(DshClient.TOKEN_FROM_LOG + ":")) {
            return cfg.tokenResolved == null || cfg.tokenResolved.isEmpty() ? null : cfg.tokenResolved;
        }
        return typed;
    }

    /** 服务器地址：填了 auto 就用启动时经隧道发现的那个（见 DshClient.resolveEndpoints）。 */
    private String effectiveHost() {
        String typed = hostField.getText().trim();
        return DshClient.isAuto(typed) ? cfg.hostResolved : typed;
    }


    /**
     * 所有"要打开 DSH"的地方都走这里，别再各自调 openInAppWindow。
     *
     * <p>之前「打开 DSH 界面」修好了、「重新连接」还直连 openInAppWindow，于是重连一次又弹一个窗口。
     * 现在规则只有一条：**能内嵌就内嵌（已有页签就切过去重载），内嵌不可用才开浏览器窗口。**
     */
    private void reloadOrOpenDsh(String url) {
        runOnEdt(() -> {
            if (embedDsh != null && centerTabs.indexOf(embedDsh) >= 0) {
                centerTabs.select(centerTabs.indexOf(embedDsh));
                embedDshUrl = url;
                embedDsh.open(url);
                return;
            }
            dshWindowOpened = false;      // 允许这次真的建一个
            openDshWindow(url);
        });
    }

    /**
     * 打开 DSH 界面窗口（自动路径专用）：**只开一次**（1.19.0）。
     *
     * 以前看门狗重连、或路线回落（直连→域名→网页）时会再 `openInAppWindow` 一次，
     * 于是"重连一次多弹一个窗口"。现在自动路径只开第一个，之后提示按 F5；
     * 只有你**手点**「打开 DSH 界面」时才强制再开。
     */
    private void openDshWindow(String url) {
        if (dshWindowOpened) {
            // 已经有 DSH 页签就直接切过去（不是"什么都不做"，也不是再弹一个窗口）
            if (embedDsh != null && centerTabs.indexOf(embedDsh) >= 0) {
                centerTabs.select(centerTabs.indexOf(embedDsh));
                logLine("已切到 DSH 页签（要刷新就右键 → 重新加载）");
                return;
            }
            logLine("DSH 窗口开过了 → 在已开那个窗口里按 F5 刷新即可（不重复开窗）：" + url);
            return;
        }
        dshWindowOpened = true;
        if (openWebSurface(url, false)) {
            return;
        }
        // 「合并窗口」和独立窗口都停用了：内嵌不可用时只记日志，不再弹任何窗口
        logLine("⚠️ 内嵌浏览器不可用，无法在客户端里打开（不弹独立窗口）：" + url);
    }

    /**
     * 页面里的链接 → **系统默认浏览器**（用户明确要求）。
     *
     * <p>不在客户端开页签、不自己造窗口：Windows 用 `rundll32 url.dll,FileProtocolHandler`
     * （等价于你在资源管理器里双击这个链接），失败再退 `Desktop.browse`、`cmd /c start`。
     */
    private void openInDefaultBrowser(String url) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
            logLine("链接已交给默认浏览器：" + url);
            return;
        } catch (Exception e) {
            logLine("系统打开失败（" + e.getMessage() + "），改用 Desktop");
        }
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                logLine("链接已交给默认浏览器（Desktop）：" + url);
                return;
            }
        } catch (Exception e) {
            logLine("Desktop.browse 失败：" + e.getMessage());
        }
        try {
            new ProcessBuilder("cmd", "/c", "start", "", url).start();
            logLine("链接已交给默认浏览器（start）：" + url);
        } catch (Exception e) {
            logLine("⚠️ 打不开默认浏览器，请手动复制：" + url);
        }
    }

    /** 内嵌浏览器可用？= 开关打开 + cef 目录里的 jar 在 classpath 上。 */
    private boolean useEmbedded() {
        // -Ddsh.nojcef=1 是安全开关：彻底不碰 JCEF（排障用，等价于老版本行为）
        if ("1".equals(System.getProperty("dsh.nojcef"))) {
            return false;
        }
        return embedBrowserBox.isSelected() && JcefGate.available();
    }

    /**
     * 统一入口：能内嵌就内嵌成客户端里的一个页签，否则返回 false 让调用方开浏览器窗口。
     * 内嵌时页签是懒创建的 —— JCEF 首次初始化要下载 138MB 运行时，不能拖慢启动。
     */
    private boolean openWebSurface(String url, boolean wb) {
        if (!useEmbedded()) {
            return false;
        }
        // 调用方可能是连接线程；addTab / select / new JcefView 都是 Swing 操作，必须回 EDT
        runOnEdt(() -> {
            if (wb) {
                if (embedWb == null) {
                    embedWb = new JcefView(this::logLine, u -> {
                        logLine("内嵌失败（不弹窗口）：" + u);
                        openInAppWindow(u, true);
                    });
                    embedWb.setOnExternalLink(this::openInDefaultBrowser);
                    centerTabs.addTab("WorkBuddy 桌面", embedWb,
                            (anchor, x, y) -> showBrowserMenu(true, anchor, x, y));
                }
                embedWbUrl = url;
                embedWb.open(url);
                centerTabs.select(centerTabs.indexOf(embedWb));
                logLine("WorkBuddy 已内嵌到页签「WorkBuddy 桌面」（右键页签可关闭 / 重新加载）");
            } else {
                if (embedDsh == null) {
                    embedDsh = new JcefView(this::logLine, u -> {
                        logLine("内嵌失败（不弹窗口）：" + u);
                        openInAppWindow(u, true);
                    });
                    embedDsh.setOnExternalLink(this::openInDefaultBrowser);
                    embedDsh.setPageDark(tm.isDarkEffective());
                    pushedPageDark = tm.isDarkEffective();
                    centerTabs.addTab("DSH 界面", embedDsh,
                            (anchor, x, y) -> showBrowserMenu(false, anchor, x, y));
                }
                embedDshUrl = url;
                embedDsh.open(url);
                centerTabs.select(centerTabs.indexOf(embedDsh));
                logLine("DSH 界面已内嵌到页签「DSH 界面」（右键页签可关闭 / 重新加载）");
            }
        });
        return true;
    }

    /** 网页页签的右键菜单（自绘 ContextMenu）。 */
    private void showBrowserMenu(boolean wb, java.awt.Component anchor, int x, int y) {
        ContextMenu menu = new ContextMenu(tm);
        menu.addItem("重新加载", () -> {
            if (wb) {
                if (embedWb != null) {
                    embedWb.open(embedWbUrl);
                }
            } else if (embedDsh != null) {
                // DSH 这边顺手换一次 token：很多"重新加载还是那句 401"就是 token 过期。
                // 探测 + 取 token 会走网络/SSH，放后台，别冻结界面。
                new Thread(() -> {
                    final String u = dshUrlWithFreshToken();
                    embedDshUrl = u;
                    final JcefView v = embedDsh;
                    runOnEdt(() -> v.open(u));
                }, "dsh-reload-token").start();
            }
            logLine("已重新加载" + (wb ? " WorkBuddy " : " DSH ") + "页签");
        });
        menu.addItem("关闭页签", () -> closeWebTab(wb));
        menu.showAt(anchor, x, y);
    }

    private void showDownloadMenu(java.awt.Component anchor) {
        if (downloadPanel == null) {
            downloadPanel = new DownloadPanel(tm);
            downloadPanel.setFooterActions(
                    () -> DownloadCenter.get().clearFinished(),
                    this::openDownloadDir);
            // 弹层开着时下载进度还在变：以前只在"打开那一刻"拍一份快照，
            // 于是文件早下完了、面板还停在"下载中"。这里让它在可见期间跟着数据刷新。
            DownloadCenter.get().addListener(() -> runOnEdt(() -> {
                if (downloadPanel != null && downloadPanel.isVisible()) {
                    downloadPanel.setRows(buildDownloadRows());
                }
            }));
        }
        downloadPanel.setRows(buildDownloadRows());
        downloadPanel.showAt(anchor, Math.max(0, anchor.getWidth() - 400), anchor.getHeight() + 4);
    }

    /** 把下载中心的记录翻成弹层要的行。 */
    private java.util.List<DownloadRow> buildDownloadRows() {
        java.util.List<DownloadRow> rows = new java.util.ArrayList<>();
        for (DownloadCenter.Item it : DownloadCenter.get().items()) {
            boolean done = "已完成".equals(it.state);
            java.io.File path = it.path == null ? new java.io.File(".") : it.path;
            rows.add(new DownloadRow(
                    it.name == null ? "未命名" : it.name,
                    it.state,
                    it.percent,
                    path,
                    done,
                    done ? () -> openFileQuietly(path) : null,
                    path.getParentFile() != null ? () -> openDirSafely(path.getParentFile()) : null,
                    () -> deleteDownload(it)));
        }
        return rows;
    }

    /** 删一条下载：确认后删磁盘文件并移除记录（状态栏下载面板里的垃圾桶图标）。 */
    private void deleteDownload(DownloadCenter.Item it) {
        String name = it.name == null ? "这个文件" : it.name;
        int ok = javax.swing.JOptionPane.showConfirmDialog(this,
                "确定删除「" + name + "」？\n文件会移到回收站（可在那里还原）。",
                "删除下载", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.WARNING_MESSAGE);
        if (ok != javax.swing.JOptionPane.OK_OPTION) {
            return;
        }
        // 走回收站要起一次 powershell（约 0.5–1.5s），不能放在 EDT 上阻塞界面
        final boolean hasFile = "已完成".equals(it.state);
        new Thread(() -> {
            String note = DownloadCenter.get().delete(it.id, hasFile);
            SwingUtilities.invokeLater(() -> logLine(note.isEmpty()
                    ? "已删除下载（已移到回收站）：" + name
                    : "已删除下载：" + name + "（" + note + "）"));
        }, "dsh-download-delete").start();
        if (downloadPanel != null) {
            downloadPanel.hidePopup();
        }
    }

    private void openFileQuietly(java.io.File f) {
        if (f == null) {
            return;
        }
        try {
            java.awt.Desktop.getDesktop().open(f);
        } catch (Exception e) {
            logLine("打不开：" + f + "（" + e.getMessage() + "）");
        }
    }

    private static String escapeHtml(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 打开真实的下载目录。
     *
     * <p>以前直接 `Desktop.open(JcefView.downloadDir())`：当这个路径不存在（比如 Downloads 被重定向到别的盘、
     * 或者注册表读到的值不可用）时，Windows 会退化成打开**当前工作目录**——也就是客户端自己所在的文件夹，
     * 看起来就是"点了却跳到程序目录"。
     *
     * <p>现在的顺序：最近一条下载记录的父目录 → downloadDir() → 最后才兜底；每一步都校验 isDirectory，
     * 打不开再用 explorer.exe（Desktop.open 在部分环境里静默失败）。
     */
    private void openDownloadDir() {
        java.util.List<DownloadCenter.Item> items = DownloadCenter.get().items();
        java.io.File dir = null;
        for (DownloadCenter.Item it : items) {
            if (it.path != null && it.path.getParentFile() != null
                    && it.path.getParentFile().isDirectory()) {
                dir = it.path.getParentFile();
                break;
            }
        }
        if (dir == null) {
            dir = JcefView.downloadDir();
        }
        if (dir == null) {
            logLine("⚠️ 下载目录不可用（无法解析）");
            return;
        }
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        if (!dir.isDirectory()) {
            logLine("⚠️ 下载目录不存在且创建失败：" + dir.getAbsolutePath());
            return;
        }
        openDirSafely(dir);
    }

    /** 打开一个目录：先 Desktop，再 explorer（Windows 上 Desktop.open 有静默失败的情况）。 */
    private void openDirSafely(java.io.File dir) {
        try {
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(dir);
                return;
            }
        } catch (Exception e) {
            logLine("Desktop.open 失败：" + e.getMessage() + "，改用 explorer");
        }
        try {
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                return;
            }
            new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
        } catch (Exception e) {
            logLine("⚠️ 打不开目录：" + dir.getAbsolutePath() + "（" + e.getMessage() + "）");
        }
    }

    /** 关掉网页页签：先释放浏览器，再从页签条里移除。 */
    private void closeWebTab(boolean wb) {
        runOnEdt(() -> {
            JcefView view = wb ? embedWb : embedDsh;
            if (view == null) {
                return;
            }
            view.disposeBrowser();
            int i = centerTabs.indexOf(view);
            if (i >= 0) {
                centerTabs.removeTab(i);
            }
            if (wb) {
                embedWb = null;
                embedWbUrl = "";
            } else {
                embedDsh = null;
                embedDshUrl = "";
                dshWindowOpened = false;   // 关掉后允许「打开 DSH 界面」重新建页签
            }
            logLine((wb ? "WorkBuddy" : "DSH") + " 页签已关闭（再点对应按钮可重新打开）");
        });
    }

    /** 已经在 EDT 上就直接跑，否则排进 EDT —— 避免"后台线程碰 Swing"这类死锁。 */
    private static void runOnEdt(Runnable r) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(r);
        }
    }

    /**
     * 合并窗口：只开一个 Edge `--app=` 窗口，里面用标签页切换 DSH 与 WorkBuddy。
     * 依据是两边都没有 `X-Frame-Options`，且都在 127.0.0.1（同站点，cookie 能带）。
     * 起不来就老老实实退回"分开两个窗口"，不让用户卡住。
     */
    /**
     * 【已停用】合并窗口：原来起一个本地中转页再用独立窗口打开。
     * 现在所有页面都进客户端页签，这条路不再使用，只留一行日志便于排查。
     */
    private void openMergedWindow(String dshUrl) {
        logLine("（合并窗口功能已停用，页面都进客户端页签）");
    }

    private void setStatus(String text, boolean error) {
        connText = text;
        connError = error;
        refreshStatus();
    }

    /** 路线的短名（状态栏用）。 */
    private static String routeShort(RouteProbe.Route r) {
        if (r == RouteProbe.Route.WEB) return "域名/网页隧道（不经 SSH）";
        if (r == RouteProbe.Route.TUNNEL) return "域名（CF 隧道 · SSH over WebSocket）";
        return "SSH 直连（IPv6:22）";
    }

    /**
     * 状态栏 = 连接状态 + **当前真实路线** + 实测速度（1.12.1）。
     *
     * 以前标题只在窗口创建时写一次，之后路线被回落/掉线切换改掉了它却不更新 ——
     * 于是"显示的是直连、其实跑在域名上"（反过来也有）。现在标题、状态栏、日志三处
     * 都只能通过 {@link #setRoute} 改，任何改路线的地方都会一起更新。
     */
    private void refreshStatus() {
        String s = "路线=" + routeShort(route)
                + (lastKbps > 0 ? String.format("　实测 %.0f KB/s", lastKbps) : "")
                + (routeAuto ? "　［auto］" : "　［显式指定］");
        SwingUtilities.invokeLater(() -> {
            UiTheme t = tm.theme();
            status.setText(s);
            status.setForeground(t.textSecondary);
            if (topStatusBar != null) {
                topStatusBar.setTitle("DSH 客户端 " + DshClient.VERSION + " · " + routeShort(route));
            }
            StatusPill.State st;
            if (connError) {
                st = StatusPill.State.ERROR;
            } else if (connText.contains("连接中")) {
                st = StatusPill.State.CONNECTING;
            } else if (connText.contains("未连接")) {
                st = StatusPill.State.DISCONNECTED;
            } else {
                st = StatusPill.State.CONNECTED;
            }
            pill.setState(st, connText);
        });
    }

    /** 路线变了都走这里：窗口标题、状态栏、日志一起改（别再让标题骗人）。 */
    private void setRoute(RouteProbe.Route r, String why) {
        if (r == route) return;
        route = r;
        SwingUtilities.invokeLater(() -> setTitle("DSH 客户端 " + DshClient.VERSION + " —— " + routeTitle(r)));
        logLine("★ 路线改为：" + routeTitle(r) + (why == null || why.isEmpty() ? "" : "（" + why + "）"));
        refreshStatus();
    }

    /**
     * 往「日志」页签追加一行。
     *
     * <p>3.1.0 修"日志页签复制不了"：以前每来一行都 setCaretPosition(末尾)，
     * 而 Swing 的 setCaretPosition 会**把选区一起清掉** —— 你刚选中一段，下一行日志（看门狗每 3 秒就有一条）
     * 就把它抹了，Ctrl+C 自然复制不到东西。现在只在"没选中任何文字、并且本来就贴在底部"时才跟随滚动。
     *
     * <p>同时落一份到 {@code ~/.dsh-client.log}（带时间戳），"波动/掉线"时把那个文件发出来就能定位。
     */
    private void logLine(String text) {
        Startup.runtime(text);
        SwingUtilities.invokeLater(() -> {
            if (logScroll == null) {
                log.append(text + "\n");
                log.setCaretPosition(log.getDocument().getLength());
                return;
            }
            boolean noSelection = log.getSelectionStart() == log.getSelectionEnd();
            boolean follow = noSelection && logAtBottom();
            log.append(text + "\n");
            if (follow) {
                log.setCaretPosition(log.getDocument().getLength());
            }
        });
    }

    /** 视图是不是贴在底部（用户往上翻看历史时不抢滚动位置）。 */
    private boolean logAtBottom() {
        javax.swing.JScrollBar sb = logScroll.getVerticalScrollBar();
        return sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - 8;
    }

    /**
     * 【已停用】开独立窗口。
     *
     * <p>用户明确要求：**不再弹任何独立窗口**。这里保留方法签名（DshClient 里还有引用），
     * 但只打印一行提示，不启动浏览器、不建窗口。想要页面就进客户端页签。
     */
    static void openInAppWindow(String url, boolean appMode) {
        System.out.println("[no-window] 独立窗口功能已关闭，未打开：" + url);
    }

    private void saveSettings() {
        cfg.host = hostField.getText().trim();
        cfg.port = Integer.parseInt(portField.getText().trim());
        cfg.user = userField.getText().trim();
        cfg.key = keyField.getText().trim();
        cfg.localPort = Integer.parseInt(localPortField.getText().trim());
        cfg.token = tokenField.getText().trim();
        cfg.wbEnabled = wbEnabledBox.isSelected();
        cfg.wbLocalPort = Integer.parseInt(wbLocalPortField.getText().trim());
        cfg.wbPassword = wbPasswordField.getText().trim();
        cfg.wbContainer = wbContainerField.getText().trim();
        cfg.wbSshExe = wbSshExeField.getText().trim();
        cfg.wbSshKey = wbSshKeyField.getText().trim();
        cfg.wbAutoOpen = wbAutoOpenBox.isSelected();
        Properties p = new Properties();
        p.setProperty("host", cfg.host);
        p.setProperty("port", String.valueOf(cfg.port));
        p.setProperty("user", cfg.user);
        p.setProperty("key", cfg.key);
        p.setProperty("localPort", String.valueOf(cfg.localPort));
        p.setProperty("token", cfg.token);
        p.setProperty("openBrowser", String.valueOf(cfg.openBrowser));
        p.setProperty("wbEnabled", String.valueOf(cfg.wbEnabled));
        p.setProperty("wbRoute", cfg.wbRoute == null ? "auto" : cfg.wbRoute);
        p.setProperty("wbWebUrl", cfg.wbWebUrl == null ? "" : cfg.wbWebUrl);
        p.setProperty("wbLocalPort", String.valueOf(cfg.wbLocalPort));
        p.setProperty("wbRemotePort", String.valueOf(cfg.wbRemotePort));
        p.setProperty("wbDirectPort", String.valueOf(cfg.wbDirectPort));
        p.setProperty("wbPassword", cfg.wbPassword == null ? "" : cfg.wbPassword);
        p.setProperty("wbContainer", cfg.wbContainer == null ? "" : cfg.wbContainer);
        p.setProperty("wbSshExe", cfg.wbSshExe == null ? "" : cfg.wbSshExe);
        p.setProperty("wbSshKey", cfg.wbSshKey == null ? "" : cfg.wbSshKey);
        p.setProperty("wbAutoOpen", String.valueOf(cfg.wbAutoOpen));
        p.setProperty("wbDirectHost", cfg.wbDirectHost == null ? "" : cfg.wbDirectHost);
        p.setProperty("route", cfg.route == null ? "auto" : cfg.route);
        p.setProperty("theme", cfg.theme == null ? "system" : cfg.theme);
        p.setProperty("mergeWindows", String.valueOf(cfg.mergeWindows));
        p.setProperty("embeddedBrowser", String.valueOf(cfg.embeddedBrowser));
        p.setProperty("webUrl", cfg.webUrl);
        p.setProperty("tunnelHost", cfg.tunnelHost);
        p.setProperty("tunnelWsUrl", cfg.tunnelWsUrl);
        try (FileOutputStream out = new FileOutputStream(DshClient.settingsFile())) {
            p.store(out, "dsh desktop client settings");
        } catch (Exception e) {
            logLine("设置保存失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unused")
    private static Properties loadInto(File f) {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            p.load(in);
        } catch (Exception ignore) {
            // 没有配置文件就用默认值
        }
        return p;
    }
}
