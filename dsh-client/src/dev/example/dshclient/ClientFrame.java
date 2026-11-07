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
    private final JTextArea log = new JTextArea(8, 64);

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

        // 主题：先把持久化接上（写回 properties），再按配置/系统判断定一版，最后挂监听统一重绘
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
        boolean wantDark = "dark".equals(want) || ("system".equals(want) && resolveThemeDark());
        tm.setMode(wantDark ? "dark" : "light");
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
            if (!DshClient.routeReason.isEmpty()) logLine(DshClient.routeReason);
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

        ModernButton themeBtn = new ModernButton(themeLabel(), ModernButton.Kind.GHOST, tm);
        themeBtn.addActionListener(e -> {
            UiTheme from = tm.theme();
            tm.setMode(tm.isDarkEffective() ? "light" : "dark");
            UiTheme to = tm.theme();
            themeBtn.setText(themeLabel());
            themeTransition.play(from, to, this::applyThemeOf);
        });
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

    private String themeLabel() {
        return tm.isDarkEffective() ? "切到亮色" : "切到暗色";
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

    private boolean resolveThemeDark() {
        String t = cfg.theme == null ? "system" : cfg.theme.trim().toLowerCase();
        if ("light".equals(t)) return false;
        if ("dark".equals(t)) return true;
        // 跟随系统：Windows 上读注册表的深色开关；读不到就按暗色（客户端本来就是深色界面）
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                Process p = new ProcessBuilder("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme").redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("AppsUseLightTheme")) {
                            return !line.trim().endsWith("0x1");
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // 读不到就用默认
        }
        return true;
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
        repaint();
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
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(log), BorderLayout.CENTER);
        return p;
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

    /** 连隧道（幂等：已连就直接返回 true）。web 路线不建 SSH，直接算成功。 */
    private boolean connect() {
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
            connectedAt = System.currentTimeMillis();
            setStatus("已连接 · " + t.url(null), false);   // refreshStatus 会把路线与实测速度拼上
            logLine("SSH 转发就绪（DSH 界面）→ " + t.url(null)
                    + "　※ 这条是「端口转发」：任何路线（直连/域名）都靠它把服务器上的 loopback 端口搬到本机");
            if (DshClient.wbEnabled(cfg)) {
                if (t.boundWbPort() > 0) {
                    logLine("WorkBuddy 的 SSH 转发就绪（③ 兜底）→ http://127.0.0.1:" + t.boundWbPort()
                            + "/（自检：" + t.lastWbSelfCheck() + "）");
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

    /** 看门狗：每 5 秒看一眼会话，断了自动重连（页面按 F5 即可）。 */
    private void startWatchdog() {
        if (watchdog != null) return;
        watchdog = new javax.swing.Timer(3_000, e -> {
            if (stopped || tunnel == null) return;
            if (!tunnel.isAlive()) {
                // 连稳 5 分钟以上才把"掉线计数"清零 —— 否则偶发一次就白记
                long aliveMs = connectedAt > 0 ? System.currentTimeMillis() - connectedAt : -1;
                if (aliveMs > 300_000) directDrops = 0;
                if (routeAuto && route == RouteProbe.Route.DIRECT) {
                    directDrops++;
                    logLine("SSH 直连断了（本次运行第 " + directDrops + " 次"
                            + (aliveMs >= 0 ? "，这次只撑了 " + (aliveMs / 1000) + " 秒" : "") + "）");
                    // 1.14.0：刚连上就断 = 这条路明显不行，**第一次就切**，不用等第二次
                    if (directDrops >= 2 || (aliveMs >= 0 && aliveMs < 60_000)) {
                        setRoute(RouteProbe.Route.TUNNEL,
                                aliveMs >= 0 && aliveMs < 60_000 ? "直连只撑了 " + (aliveMs / 1000) + " 秒"
                                        : "直连掉了 " + directDrops + " 次线");
                    }
                } else {
                    logLine("隧道断了，正在自动重连…");
                }
                new Thread(() -> {
                    if (connect()) {
                        logLine("✅ 已重连。浏览器里的页面按 F5 刷新一下就好（本地端口没变）。");
                        refreshWbState();
                    }
                }, "dsh-rewatch").start();
            }
        });
        watchdog.start();
    }

    private void stop() {
        stopped = true;
        if (watchdog != null) watchdog.stop();
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

    private void logLine(String text) {
        SwingUtilities.invokeLater(() -> {
            log.append(text + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        });
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
