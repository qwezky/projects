package dev.example.dshclient;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
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
    private final javax.swing.JComboBox<String> routeBox = new javax.swing.JComboBox<>(
            new String[]{"自动（判读 + 实测）", "SSH 直连（IPv6:22）", "域名（CF 隧道）", "网页隧道（浏览器）"});
    /** WorkBuddy 的 4 个路径选项。 */
    private final javax.swing.JComboBox<String> wbRouteBox = new javax.swing.JComboBox<>(
            new String[]{"自动（跟随 DSH 路线）", "① 直连 IPv6 [服务器]:6080", "② 域名 wb.example.com", "③ SSH 转发 127.0.0.1:6080"});
    private final JTextField wbContainerField;
    private final JTextField wbSshExeField;
    private final JTextField wbSshKeyField;
    private final javax.swing.JCheckBox wbAutoOpenBox;
    /** WorkBuddy 容器状态显示（1.5.0） */
    private final JLabel wbStateLabel = new JLabel("WorkBuddy 容器：未知");

    private final JLabel status = new JLabel("未连接");
    private final JTextArea log = new JTextArea(8, 64);

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
        this.wbEnabledBox = new javax.swing.JCheckBox("同一个 jar 再开一个 WorkBuddy 桌面窗口", cfg.wbEnabled);
        this.wbLocalPortField = new JTextField(String.valueOf(cfg.wbLocalPort));
        this.wbPasswordField = new JTextField(cfg.wbPassword);
        this.wbWebUrlField = new JTextField(cfg.wbWebUrl);
        this.wbContainerField = new JTextField(cfg.wbContainer);
        this.wbSshExeField = new JTextField(cfg.wbSshExe);
        this.wbSshKeyField = new JTextField(cfg.wbSshKey);
        this.wbAutoOpenBox = new javax.swing.JCheckBox("打开时自动开 WB 窗口", cfg.wbAutoOpen);
        // 界面选路：把当前设置反映到下拉里；改动立刻保存并生效（DSH 路线会重连）
        routeBox.setSelectedIndex(routeIndexOf(cfg.route));
        wbRouteBox.setSelectedIndex(wbRouteIndexOf(cfg.wbRoute));
        routeBox.addActionListener(e -> applyRouteChoice(routeIdOf(routeBox.getSelectedIndex())));
        wbRouteBox.addActionListener(e -> applyWbRouteChoice(wbRouteIdOf(wbRouteBox.getSelectedIndex())));

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                stop();
                dispose();
                System.exit(0);
            }
        });
        setLayout(new BorderLayout(8, 8));
        add(configPanel(), BorderLayout.NORTH);
        add(logPanel(), BorderLayout.CENTER);
        add(southPanel(), BorderLayout.SOUTH);
        setMinimumSize(new Dimension(760, 460));
        pack();
        setLocationRelativeTo(null);
    }

    /** 启动：连隧道 → 打开界面 → 起看门狗。（web 路线没有隧道可守） */
    void startAndOpen() {
        new Thread(() -> {
            logLine("DSH 客户端 " + DshClient.VERSION + " · " + DshClient.jarPathHint());
            if (!cfg.resolveNote.isEmpty()) logLine(cfg.resolveNote);
            if (!DshClient.routeReason.isEmpty()) logLine(DshClient.routeReason);
            logLine("本次连接目标：" + cfg.hostResolved + ":" + cfg.port
                    + "｜token：" + (cfg.tokenResolved == null || cfg.tokenResolved.isEmpty()
                    ? "(空)" : cfg.tokenResolved.substring(0, Math.min(6, cfg.tokenResolved.length())) + "…"));
            if (!connect()) return;
            if (route == RouteProbe.Route.WEB) {
                if (cfg.openBrowser) {
                    openDshWindow(DshClient.webUrl(cfg));
                    logLine("已在独立窗口打开网页隧道（走 Cloudflare，本机没有 SSH 转发）。");
                    logLine("WorkBuddy 仍然可用：点「打开 WorkBuddy 桌面」时会临时经 CF 单开一条只做 6080 的会话。");
                    if (cfg.wbAutoOpen) openWorkbuddyWindow();
                    else logLine("WorkBuddy 窗口没有自动打开（默认关；点「打开 WorkBuddy 桌面」即可）");
                }
                return;
            }
            if (cfg.openBrowser) {
                openDshWindow(tunnel.url(null));
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

    private JPanel configPanel() {
        JPanel p = new JPanel(new GridLayout(0, 4, 6, 4));
        p.setBorder(BorderFactory.createTitledBorder("服务器（和手机端同一把 key、同一个 22 端口）"));
        p.add(new JLabel("走哪条路（立即生效·会记住）"));
        p.add(routeBox);
        p.add(new JLabel("WorkBuddy 走哪条"));
        p.add(wbRouteBox);
        p.add(new JLabel("服务器（auto = 每次经隧道发现）"));
        p.add(hostField);
        p.add(new JLabel("SSH 端口"));
        p.add(portField);
        p.add(new JLabel("用户名"));
        p.add(userField);

        JPanel keyRow = new JPanel(new BorderLayout(4, 0));
        keyRow.add(keyField, BorderLayout.CENTER);
        JButton pickKey = new JButton("…");
        pickKey.setToolTipText("选择私钥文件（%USERPROFILE%\\.ssh 下；不一定是 id_ed25519）");
        pickKey.addActionListener(e -> {
            File start = new File(keyField.getText().trim()).getParentFile();
            JFileChooser fc = new JFileChooser(start != null && start.isDirectory() ? start : new File(DshClient.sshDir()));
            fc.setFileHidingEnabled(false);
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                keyField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        keyRow.add(pickKey, BorderLayout.EAST);
        p.add(new JLabel("私钥文件"));
        p.add(keyRow);

        p.add(new JLabel("本地端口"));
        p.add(localPortField);
        p.add(new JLabel("Token（auto = 每次经隧道取最新的）"));
        p.add(tokenField);
        p.add(new JLabel("WorkBuddy 桌面"));
        p.add(wbEnabledBox);
        p.add(new JLabel("WB 本机转发端口"));
        p.add(wbLocalPortField);
        p.add(new JLabel("WB VNC 密码"));
        p.add(wbPasswordField);
        p.add(new JLabel("WB 域名入口（空=关）"));
        p.add(wbWebUrlField);
        p.add(new JLabel("WB 容器名（打开前 docker start）"));
        p.add(wbContainerField);
        p.add(new JLabel("WB 自动开窗（默认关）"));
        p.add(wbAutoOpenBox);
        p.add(new JLabel("WB 系统 ssh（auto/空=自动）"));
        p.add(wbSshExeField);
        p.add(new JLabel("WB ssh 私钥（空=同隧道）"));
        p.add(wbSshKeyField);
        return p;
    }

    private JPanel logPanel() {
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(log), BorderLayout.CENTER);
        return p;
    }

    private JPanel southPanel() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        JButton open = new JButton("打开 DSH 界面");
        open.addActionListener(e -> {
            if (route == RouteProbe.Route.WEB) {
                // 1.27.0：网页路线下**没有 SSH 会话**，这个按钮就该直接（重）开那个网页窗口 ——
                // 以前它只回一句「隧道还没连上，先点重新连接」，在网页路线上等于按钮失灵。
                dshWindowOpened = true;
                String u = DshClient.webUrl(cfg);
                openInAppWindow(u, true);
                logLine("已（重新）打开网页隧道：" + u + "　※ 这条路没有 SSH 会话，窗口里按 F5 即可刷新");
                return;
            }
            if (tunnel == null || !tunnel.isAlive()) {
                logLine("隧道还没连上，先点『重新连接』");
                return;
            }
            dshWindowOpened = true;
            openInAppWindow(tunnel.url(currentToken()), true);
        });
        JButton openWb = new JButton("打开 WorkBuddy 桌面");
        openWb.addActionListener(e -> new Thread(this::openWorkbuddyWindow, "dsh-wb").start());
        JButton reconnect = new JButton("重新连接");
        reconnect.addActionListener(e -> new Thread(() -> {
            speedDone = false;          // 手动重连时重新量一遍速度
            if (!connect()) return;
            if (route == RouteProbe.Route.WEB) {
                // 1.27.0：网页路线没有 SSH 会话，「重连」对用户来说就是「把那个网页窗口重新打开」
                dshWindowOpened = true;
                String u = DshClient.webUrl(cfg);
                openInAppWindow(u, true);
                logLine("已重新打开网页隧道：" + u);
            } else {
                logLine("✅ 已重连。浏览器里的页面按 F5 刷新一下就好（本地端口没变）。");
            }
        }, "dsh-reconnect").start());
        JButton save = new JButton("保存设置");
        save.addActionListener(e -> saveSettings());
        JButton stop = new JButton("断开并退出");
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

        JPanel wbRow = new JPanel();
        JButton wbStart = new JButton("启动 WorkBuddy 容器");
        wbStart.addActionListener(e -> new Thread(() -> wbAction("start"), "dsh-wb-start").start());
        JButton wbStop = new JButton("停止容器");
        wbStop.addActionListener(e -> new Thread(() -> wbAction("stop"), "dsh-wb-stop").start());
        JButton wbRefresh = new JButton("刷新状态");
        wbRefresh.addActionListener(e -> new Thread(this::refreshWbState, "dsh-wb-state").start());
        wbRow.add(wbStart);
        wbRow.add(Box.createHorizontalStrut(6));
        wbRow.add(wbStop);
        wbRow.add(Box.createHorizontalStrut(6));
        wbRow.add(wbRefresh);
        wbRow.add(Box.createHorizontalStrut(12));
        wbRow.add(wbStateLabel);

        JPanel p = new JPanel(new BorderLayout(8, 6));
        p.add(buttons, BorderLayout.NORTH);
        p.add(wbRow, BorderLayout.CENTER);
        p.add(status, BorderLayout.SOUTH);
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
            openInAppWindow(bestUrl, true);
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
     * 打开 DSH 界面窗口（自动路径专用）：**只开一次**（1.19.0）。
     *
     * 以前看门狗重连、或路线回落（直连→域名→网页）时会再 `openInAppWindow` 一次，
     * 于是"重连一次多弹一个窗口"。现在自动路径只开第一个，之后提示按 F5；
     * 只有你**手点**「打开 DSH 界面」时才强制再开。
     */
    private void openDshWindow(String url) {
        if (dshWindowOpened) {
            logLine("DSH 窗口开过了 → 在已开那个窗口里按 F5 刷新即可（不重复开窗）：" + url);
            return;
        }
        dshWindowOpened = true;
        openInAppWindow(url, true);
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
        String s = connText
                + "　·　路线=" + routeShort(route)
                + (lastKbps > 0 ? String.format("（实测 %.0f KB/s）", lastKbps) : "")
                + (routeAuto ? "　［auto］" : "　［显式指定］");
        SwingUtilities.invokeLater(() -> {
            status.setText(s);
            status.setForeground(connError ? Color.RED.darker() : new Color(0, 110, 0));
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
     * 用 Edge/Chrome 的 `--app=` 模式打开：一个没有地址栏的独立窗口。
     * 找不到就用系统默认浏览器（这时会有地址栏，功能一样）。
     */
    static void openInAppWindow(String url, boolean appMode) {
        if (appMode) {
            for (String exe : DshClient.browserCandidates()) {
                if (exe != null && new File(exe).isFile()) {
                    try {
                        new ProcessBuilder(exe, "--app=" + url, "--new-window").start();
                        return;
                    } catch (Exception ignore) {
                        // 换下一个候选
                    }
                }
            }
        }
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
        } catch (Exception e) {
            System.out.println("（打不开浏览器，请手动访问：" + url + "）");
        }
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
