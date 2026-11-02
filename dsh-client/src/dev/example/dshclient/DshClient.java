package dev.example.dshclient;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电脑版 DSH 客户端。
 *
 * 做的事只有一件：在本机用 SSH（IPv6 直连、22 端口、和手机 App 同一把 key）
 * 把服务器上只监听 loopback 的 DSH 界面转发到本机，然后用 Edge/Chrome 的
 * `--app=` 模式打开 —— 一个没有地址栏的独立窗口，看起来就是个客户端程序。
 *
 * 为什么不用「服务器直接监听 IPv6」或「Cloudflare 隧道」：
 *  - 服务器零改动、不新增公网监听端口
 *  - 全程 SSH 加密，DSH 的会话 cookie 不在明文链路上跑
 *  - 浏览器看到的是 loopback ⇒ DSH 的信任围栏natural放行，不用改 trustedHosts
 *  - 页面和页面内下载同源（都是 http://127.0.0.1:3080）⇒ 没有混合内容拦截，
 *    也绕开了隧道那条"下到 36KB 就断"的路
 *
 * 用法：
 *   java -jar DshClient.jar                 图形界面（默认）
 *   java -jar DshClient.jar --serve         只起隧道并把 URL 打在控制台（脚本/排错用）
 *   java -jar DshClient.jar --print-url     只打印 URL，不起隧道
 *   java -jar DshClient.jar --no-open       起隧道但不自动开浏览器
 */
public final class DshClient {

    /**
     * 发现失败时的兜底地址（**最后一次已知值**，不是"正确的值"）。
     *
     * 默认设置是 `--host auto`：先用隧道问服务器要当前地址（见 {@link RemoteInfo}）。
     * 校园网的 /64 前缀会随认证/重连换（实测 `…:9e65` → `…:fdf4`），所以写死的地址
     * 迟早会过期 —— 这个常量只保证"隧道也问不到时还能试一把"。
     */
    static final String DEFAULT_HOST = "127.0.0.1";
    /** 兜底 token 留空：默认走 `--token auto`（经隧道从服务器日志取）。 */
    static final String DEFAULT_TOKEN = "";
    /** 网页隧道（web 模式）的地址 */
    /** 版本号 —— **由 build-jar.sh 按参数自动写入**（别手改，改了就两边不一致）。 */
    static final String VERSION = "1.0.0";

    static final String DEFAULT_WEB_URL = "https://dsh.example.com/";
    /**
     * WorkBuddy 桌面的**域名入口**（1.10.0）：Cloudflare ingress `wb.example.com → http://127.0.0.1:6080`，
     * 也就是容器里的 noVNC / websockify。有了它，WorkBuddy 不依赖 IPv6、也不占 SSH 转发位。
     */
    static final String DEFAULT_WB_WEB_URL = "https://wb.example.com/";
    /** WorkBuddy 测速用的路径：noVNC 的静态资源（容器里 105 KB），三条路都取它才可比。 */
    static final String WB_SPEED_PATH = "/app/ui.js";   // 58 KB，够比出快慢又不至于在慢链路上等太久
    /** 样本太小时不报速度（KB）。 */
    static final int SPEED_MIN_SAMPLE_KB = 16;
    /** `--host auto` / `--token auto`：每次启动经隧道发现（见 {@link RemoteInfo}）。 */
    static final String AUTO = "auto";
    /**
     * `--token log[:路径]`：读 dsh-web 日志里最新那行 `...?token=...`。
     * **先试本机路径**（在服务器本机跑这个客户端时就是它），读不到再经隧道读服务器的同一路径。
     */
    static final String TOKEN_FROM_LOG = "log";
    /**
     * 历史版本内置过的默认值。老安装的 `~/.dsh-client.properties` 里存的是它们的字面量，
     * 那是"写死的旧值"而不是用户的选择 —— 载入时迁移成 `auto`，否则升级后依旧会连旧地址。
     */
    static final String LEGACY_HOST = "";
    static final String LEGACY_TOKEN = "";
    /** SSH over WebSocket 的地址（tunnel 模式） */
    static final String DEFAULT_TUNNEL_HOST = "ssh.example.com";
    static final String DEFAULT_TUNNEL_WS = "wss://ssh.example.com/";

    /** 最近一次探测结果（auto 模式下用来解释"为什么选了这条"）。 */
    static RouteProbe.Result lastProbe;

    /** 这次为什么走这条路（人话，GUI 与命令行都会打印；1.9.0）。 */
    static String routeReason = "";

    static final class Config {
        /** 设置值：`auto`（默认，经隧道发现）或 IPv6/域名（用户指定就不去发现）。 */
        String host = AUTO;
        int port = 22;
        String user = "user";
        String key = detectKeyPath();
        /** 本机监听端口（浏览器访问的那个） */
        int localPort = 3080;
        /** 服务器上 DSH 实际监听的端口 */
        int remotePort = 3080;
        /** 设置值：`auto`（默认）/ `log[:路径]` / 字面 token。 */
        String token = AUTO;
        /** 走哪条路：auto（探测后挑）/ direct / tunnel / web；见 RouteProbe。 */
        String route = "auto";
        String webUrl = DEFAULT_WEB_URL;
        String tunnelHost = DEFAULT_TUNNEL_HOST;
        String tunnelWsUrl = DEFAULT_TUNNEL_WS;
        boolean openBrowser = true;
        /** 仅用于自测：连上 N 秒后主动断开一次，验证"看门狗自动重连"这条路（0 = 不模拟）。 */
        int simulateDropSeconds = 0;
        /** `--no-discover`：跳过"经隧道发现"，直接用上面两个设置的原值/内置兜底。 */
        boolean discover = true;

        // ---------------- WorkBuddy 桌面（1.4.0） ----------------
        /** 是否启用 WorkBuddy 桌面窗口（界面上那个按钮）。 */
        boolean wbEnabled = true;
        /** 打开 jar 后**是否自动**弹出 WorkBuddy 窗口。默认 false = 只开 DSH 窗口，WB 点按钮才开。 */
        boolean wbAutoOpen = false;
        /** WorkBuddy 走哪条路：auto（域名优先）/ domain / ssh / direct / off。 */
        String wbRoute = "auto";
        /** WorkBuddy 的域名入口（Cloudflare ingress → 容器 6080）；空/off = 关掉这条路。 */
        String wbWebUrl = DEFAULT_WB_WEB_URL;

        // ---------------- 实测速度（1.12.0） ----------------
        /** 判"够快"的门槛（KB/s）。直连达到它就照用；达不到就把域名那条也连上量一遍，取快的。0 = 关掉测速比较。 */
        int speedMinKbps = 1000;
        /**
         * "两条都烂到没必要比"的下限（KB/s，1.12.2）。判读选中的那条实测低于它时，
         * 不再多连一条来比 —— 33 vs 42 KB/s 这种差距改变不了体验，却要多花一次连接和几秒。
         */
        int speedFloorKbps = 120;
        /**
         * "移动网络特征"下限（KB/s，1.13.0）。SSH 路线实测低于它时，**把网页隧道也量一遍** ——
         * 依据是手机端 2026-10-13 的实测：移动数据直连 18% 重传 / 0.33 Mbit/s（≈41 KB/s，
         * 与 PC 端在热点上量到的 33/42 KB/s 吻合），SSH-over-WS 71 秒才走完 33 个请求（首屏要 ~65 个），
         * 而**网页直接走 CF** 服务器↔CF 有 630–770 KB/s、手机 5–6 秒出首屏 → 移动网络下只有这条能用。
         */
        int mobileFloorKbps = 150;
        /**
         * "高 RTT = 移动网络特征"的阈值（毫秒，1.20.0）。握手 RTT ≥ 它、且网页那条可达时，
         * **直接用网页隧道**，不再要求"网页比 SSH 快多少"。
         *
         * 为什么：高 RTT 链路上 SSH 的瓶颈是「每个请求一条连接 × 一个 RTT」（手机端实测 71 秒才 33 个请求，
         * 首屏要 ~65 个），而浏览器走 HTTP/2 多路复用 —— 用户实测"用浏览器连接反而更稳定"。
         * 校园网 RTT 3ms 时远低于它，仍然走 SSH 直连。0 = 关掉这条判据。
         */
        int mobileRttMs = 60;
        /** 每条路测速时最多读多少字节（内存/流量上限）。 */
        int speedBytes = 192 * 1024;
        /** 每条路测速最多花多少毫秒。 */
        long speedMaxMs = 1500;
        /** 本机监听端口（SSH 转发用） */
        int wbLocalPort = 6080;
        /** 服务器上 noVNC 的端口（SSH 转发目标 127.0.0.1:<这个>） */
        int wbRemotePort = 6080;
        /** 直连时用的端口（服务器 IPv6 上对外发布的那个） */
        int wbDirectPort = 6080;
        /** noVNC 的 VNC 密码（服务器上 x11vnc -rfbauth 设的那个）；空 = 不带 */
        String wbPassword = "";
        /** 容器名：打开窗口前 best-effort `docker start <它>` */
        String wbContainer = "workbuddy-test";
        /** 非空 = 启停容器时**先**用系统 ssh（Windows 的 ssh.exe / Linux 的 ssh）。要求那把 key 允许 exec。 */
        String wbSshExe = AUTO;
        /** 系统 ssh 用的私钥（留空 = 与隧道同一把；也可以填 Windows 上别的 key 或用 agent） */
        String wbSshKey = "";
        /** 直连用的主机（默认 = 发现到的服务器地址；填了就按填的用） */
        String wbDirectHost = "";

        /** 解析后的实际连接地址（host 是 auto 时来自隧道发现）。 */
        String hostResolved = DEFAULT_HOST;
        /** 解析后的实际 token（token 是 auto 时来自服务器日志）。 */
        String tokenResolved = DEFAULT_TOKEN;
        /** 解析过程的人话说明，GUI 与命令行都会打印。 */
        String resolveNote = "";
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        if (opt.containsKey("version")) {
            System.out.println("DSH 客户端（电脑版）" + VERSION);
            System.out.println("  jar: " + jarPathHint());
            return;
        }
        if (opt.containsKey("help")) {
            usage();
            return;
        }
        Config cfg = loadConfig();
        if (opt.containsKey("host")) cfg.host = opt.get("host");
        if (opt.containsKey("port")) cfg.port = parseInt(opt.get("port"), cfg.port);
        if (opt.containsKey("user")) cfg.user = opt.get("user");
        if (opt.containsKey("key")) cfg.key = opt.get("key");
        if (opt.containsKey("local-port")) cfg.localPort = parseInt(opt.get("local-port"), cfg.localPort);
        if (opt.containsKey("remote-port")) cfg.remotePort = parseInt(opt.get("remote-port"), cfg.remotePort);
        if (opt.containsKey("token")) cfg.token = opt.get("token");
        if (opt.containsKey("route")) cfg.route = opt.get("route");
        if (opt.containsKey("web-url")) cfg.webUrl = opt.get("web-url");
        if (opt.containsKey("tunnel-ws")) cfg.tunnelWsUrl = opt.get("tunnel-ws");
        if (opt.containsKey("no-open")) cfg.openBrowser = false;
        if (opt.containsKey("no-discover")) cfg.discover = false;
        if (opt.containsKey("simulate-drop")) cfg.simulateDropSeconds = parseInt(opt.get("simulate-drop"), 0);
        if (opt.containsKey("no-workbuddy")) cfg.wbEnabled = false;
        if (opt.containsKey("workbuddy")) cfg.wbEnabled = true;
        if (opt.containsKey("wb-open")) cfg.wbAutoOpen = true;
        if (opt.containsKey("wb-route")) cfg.wbRoute = opt.get("wb-route");
        if (opt.containsKey("wb-web-url")) cfg.wbWebUrl = opt.get("wb-web-url");
        if (opt.containsKey("speed-min")) cfg.speedMinKbps = parseInt(opt.get("speed-min"), cfg.speedMinKbps);
        if (opt.containsKey("speed-bytes")) cfg.speedBytes = Math.max(64 * 1024, parseInt(opt.get("speed-bytes"), cfg.speedBytes));
        if (opt.containsKey("speed-floor")) cfg.speedFloorKbps = parseInt(opt.get("speed-floor"), cfg.speedFloorKbps);
        if (opt.containsKey("mobile-floor")) cfg.mobileFloorKbps = parseInt(opt.get("mobile-floor"), cfg.mobileFloorKbps);
        if (opt.containsKey("mobile-rtt")) cfg.mobileRttMs = parseInt(opt.get("mobile-rtt"), cfg.mobileRttMs);
        if (opt.containsKey("wb-local-port")) cfg.wbLocalPort = parseInt(opt.get("wb-local-port"), cfg.wbLocalPort);
        if (opt.containsKey("wb-remote-port")) cfg.wbRemotePort = parseInt(opt.get("wb-remote-port"), cfg.wbRemotePort);
        if (opt.containsKey("wb-direct-port")) cfg.wbDirectPort = parseInt(opt.get("wb-direct-port"), cfg.wbDirectPort);
        if (opt.containsKey("wb-password")) cfg.wbPassword = opt.get("wb-password");
        if (opt.containsKey("wb-container")) cfg.wbContainer = opt.get("wb-container");
        if (opt.containsKey("wb-ssh")) cfg.wbSshExe = opt.get("wb-ssh");
        if (opt.containsKey("wb-ssh-key")) cfg.wbSshKey = opt.get("wb-ssh-key");
        if (opt.containsKey("wb-direct-host")) cfg.wbDirectHost = opt.get("wb-direct-host");

        // 先把"每次重启都可能变的两个值"（当前 IPv6 / 当前 token）解析成具体值：
        // 默认设置是 auto，经隧道问服务器（见 RemoteInfo）；用户显式填了就尊重用户。
        resolveEndpoints(cfg);
        if (!cfg.resolveNote.isEmpty()) {
            System.out.println(cfg.resolveNote);
            System.out.flush();
        }

        // --linktest：不碰 SSH、不碰转发，直接量"这台机器的网络到底多快"（1.17.0）
        if (opt.containsKey("linktest")) {
            linkTest(cfg);
            return;
        }

        // --speedtest：把**每条连接**都真连一遍、各测一次吞吐，打完表就退出（1.12.0）
        if (opt.containsKey("speedtest")) {
            speedTest(cfg);
            return;
        }

        // --probe：只探测+判读，不起任何通道。脚本/排错用，也是"自动选路"的验收方式。
        if (opt.containsKey("probe")) {
            boolean v6 = RouteProbe.hasGlobalIpv6();
            System.out.println("DSH 客户端 " + VERSION);
            System.out.println("本机全局 IPv6：" + (v6 ? "有" : "没有（直连无从谈起，直接走域名）"));
            RouteProbe.Route r = RouteProbe.Route.TUNNEL;
            if (v6) {
                System.out.println("probe " + cfg.hostResolved + ":" + cfg.port
                    + " ...（只是 TCP 握手量 RTT 与丢包，**不建 SSH 会话**）");
                RouteProbe.Result res = RouteProbe.probe(cfg.hostResolved, cfg.port, 10, 5, 300, 3_000);
                System.out.println("  " + res.summary());
                r = res.route;
            }
            System.out.println("auto（默认）候选三条：direct（SSH 直连 IPv6:22）/ tunnel（域名 · CF 隧道）"
                    + " / web（浏览器直连 CF）；RTT ≥ " + cfg.mobileRttMs + "ms 时先试 web");
            System.out.println("  判读结果 → 这次先试：" + r + (r == RouteProbe.Route.DIRECT
                    ? "（直连 = SSH 直连 IPv6:22；掉线 2 次/不达标会自动换）" : ""));
            System.out.println("  " + (r == RouteProbe.Route.DIRECT
                    ? "local url: " + localUrl(cfg) + "   (SSH forward)"
                    : r == RouteProbe.Route.WEB
                    ? "web url:  " + webUrl(cfg) + "   (no SSH)"
                    : "tunnel:   SSH over WebSocket → 本机 " + cfg.localPort + " (DSH) + "
                      + cfg.wbLocalPort + " (WorkBuddy)"));
            // WorkBuddy：① 直连 ② 域名 ③ SSH 转发 —— **每条都实测速度**，挑最快的
            System.out.println("  workbuddy：**跟随 DSH 那条路**（2.0.0）"
                    + (wbEnabled(cfg) ? "　" + wbBindNote(r) : "（整个 WB 关掉了）"));
            if (wbEnabled(cfg)) {
                java.util.List<Speed> sp = new java.util.ArrayList<>();
                java.util.List<String> spName = new java.util.ArrayList<>();
                java.util.List<String> spUrl = new java.util.ArrayList<>();
                int n = 0;
                for (String c : wbCandidates(cfg, r)) {
                    n++;
                    if (c.equals("direct")) {
                        Speed x = wbPathSpeed(cfg.hostResolved, cfg.wbDirectPort, false, cfg.speedBytes, 6000);
                        System.out.println("    " + n + ". 直连 "
                                + wbDirectUrl(cfg).replaceAll("password=[^&]*", "password=…") + "  → " + x.text());
                        sp.add(x);
                        spName.add("① 直连 IPv6");
                        spUrl.add(wbDirectUrl(cfg));
                    } else if (c.equals("domain")) {
                        String wh = wbWebHost(cfg);
                        Speed x = new Speed();
                        if (wh.isEmpty()) x.err = "没配域名入口";
                        else x = wbPathSpeed(wh, 443, true, cfg.speedBytes, 6000);
                        System.out.println("    " + n + ". 域名 "
                                + wbWebFullUrl(cfg).replaceAll("password=[^&]*", "password=…") + "  → " + x.text());
                        sp.add(x);
                        spName.add("② 域名");
                        spUrl.add(wbWebFullUrl(cfg));
                    } else {
                        System.out.println("    " + n + ". SSH 转发 http://127.0.0.1:"
                                + cfg.wbLocalPort + wbPath(cfg) + "  → 要连上 SSH 会话才知道（连上后会实测）");
                    }
                }
                System.out.println("    （每条最多读 " + (cfg.speedBytes / 1024)
                        + " KB；三条都连上会话后各测一次，挑最快的）");
                int best = pickBestIndex(sp);
                if (best >= 0) {
                    System.out.println("    → auto 开窗时用：" + spName.get(best) + "（" + sp.get(best).text()
                            + "）→ " + spUrl.get(best).replaceAll("password=[^&]*", "password=…"));
                } else if (sp.isEmpty()) {
                    System.out.println("    → auto 开窗时用：③ SSH 转发（要连上会话才知道）");
                }
            } else {
                System.out.println("  （--no-workbuddy / --wb-route off：整个 WB 关掉了）");
            }
            return;
        }

        System.out.println("DSH 客户端 " + VERSION + " · " + jarPathHint());
        RouteProbe.Route route = resolveRoute(cfg);
        if (!routeReason.isEmpty()) {
            System.out.println(routeReason);
            System.out.flush();
        }

        if (opt.containsKey("print-url")) {
            System.out.println(urlFor(cfg, route));
            if (wbEnabled(cfg)) System.out.println(wbUrl(cfg, route));
            return;
        }
        if (opt.containsKey("serve")) {
            serveForever(cfg, route);
            return;
        }

        // 容器启停一次性动作（1.5.0）：连上后做一件事就退出（脚本/排错用）
        String wbAction = opt.containsKey("wb-start") ? "start"
                : opt.containsKey("wb-stop") ? "stop"
                : opt.containsKey("wb-status") ? "status" : null;
        if (wbAction != null) {
            if (route == RouteProbe.Route.WEB) {
                System.out.println("workbuddy " + wbAction + "：web 路线没有 SSH 通道，做不了（用 direct/tunnel）");
                return;
            }
            SshTunnel t = newTunnel(cfg, route);
            try {
                t.start();
                String out = t.workbuddySwitch(wbAction, cfg.wbContainer, resolveSshExe(cfg),
                        resolveSshKey(cfg), cfg.hostResolved, cfg.port, cfg.user);
                String shown = "status".equals(wbAction)
                        ? (out.startsWith("true") ? "运行中" : out.startsWith("false") ? "已停止" : out) : out;
                System.out.println("workbuddy " + wbAction + "：" + shown);
            } finally {
                t.close();
            }
            return;
        }

        final Config c = cfg;
        final RouteProbe.Route r = route;
        SwingUtilities.invokeLater(() -> {
            ClientFrame f = new ClientFrame(c, r);
            f.setVisible(true);
            f.startAndOpen();
        });
    }

    /**
     * 解析最终路线：显式指定就用它；auto 才自己挑（见 {@link #pickAutoRoute}）。
     *
     * 1.25.0（回档版）：**三条路都在** —— direct（SSH 直连 IPv6:22）/ tunnel（域名 · CF 隧道）/
     * web（浏览器直连 dsh.example.com）。1.23/1.24 把 direct 从 auto 摘了、甚至删了代码路径，这里全部恢复。
     */
    static RouteProbe.Route resolveRoute(Config cfg) {
        String raw = cfg.route == null ? "auto" : cfg.route.trim().toLowerCase();
        if ("probe".equals(raw)) return pickAutoRoute(cfg);          // probe 是 auto 的别名
        RouteProbe.Route explicit = RouteProbe.Route.of(cfg.route);
        if (explicit != null) {
            routeReason = "路线：显式指定 --route " + cfg.route
                    + (explicit == RouteProbe.Route.WEB
                    ? "（网页隧道不经 SSH：WorkBuddy 走域名那条，容器启停按需借一条 CF 会话）"
                    : explicit == RouteProbe.Route.DIRECT
                    ? "（SSH 直连服务器 IPv6:22，不经 Cloudflare）" : "");
            return explicit;
        }
        return pickAutoRoute(cfg);
    }

    /** auto 的三条候选：direct（SSH 直连 IPv6:22）、tunnel（域名 · CF 隧道）、web（浏览器直连 CF）。 */
    static RouteProbe.Route pickAutoRoute(Config cfg) {
        if (!RouteProbe.hasGlobalIpv6()) {
            routeReason = "路线：本机没有任何全局 IPv6 地址 → 域名（CF 隧道 · SSH over WebSocket）；"
                    + "若它也不通会自动退网页隧道";
            return RouteProbe.Route.TUNNEL;
        }
        RouteProbe.Result r = RouteProbe.probe(cfg.hostResolved, cfg.port, 10, 5, 300, 3_000);
        lastProbe = r;
        if (cfg.mobileRttMs > 0 && r.bestMs >= cfg.mobileRttMs) {
            routeReason = "路线：握手 RTT " + r.bestMs + "ms ≥ " + cfg.mobileRttMs
                    + "ms（移动网络/热点特征）→ **先试网页隧道**（浏览器直连 CF，HTTP/2 多路复用；"
                    + "SSH 在这种链路上是「每请求一连接 × 一个 RTT」）；不通再退回域名。判读：" + r.summary();
            return RouteProbe.Route.WEB;
        }
        if (r.route == RouteProbe.Route.DIRECT) {
            routeReason = "路线：IPv6 优先 → " + r.summary()
                    + "（直连 = SSH 直连服务器 IPv6:22，不经 Cloudflare；掉线 2 次自动切域名）";
            return RouteProbe.Route.DIRECT;
        }
        routeReason = "路线：IPv6 优先，但直连这次不达标 → " + r.summary()
                + " → 域名（CF 隧道）；两条 SSH 与网页都会实测速度，谁快用谁";
        return RouteProbe.Route.TUNNEL;
    }

    /** 按路线建隧道；web 模式**不建 SSH**，返回 null。 */
    static SshTunnel newTunnel(Config cfg, RouteProbe.Route route) {
        if (route == RouteProbe.Route.WEB) return null;
        // 1.4.0：命令行这条路也要带上 WorkBuddy 的副转发（否则 --serve 只建 3080）
        boolean wbOn = wbEnabled(cfg);
        int wbLocal = wbOn ? cfg.wbLocalPort : 0;
        int wbRemote = wbOn ? cfg.wbRemotePort : 0;
        if (route == RouteProbe.Route.TUNNEL) {
            // 走 WS 时 host/port 只作 JSch 记账，真正连的是 tunnelWsUrl
            return new SshTunnel(cfg.tunnelHost, cfg.port, cfg.user, cfg.key,
                    cfg.localPort, cfg.remotePort, wbLocal, wbRemote, cfg.tunnelWsUrl);
        }
        // direct：SSH 直连服务器 IPv6:22
        return new SshTunnel(cfg.hostResolved, cfg.port, cfg.user, cfg.key,
                cfg.localPort, cfg.remotePort, wbLocal, wbRemote, null);
    }

    /** 网页隧道的地址（带 token）。 */
    static String webUrl(Config cfg) {
        String base = cfg.webUrl == null ? "" : cfg.webUrl.trim();
        if (!base.endsWith("/")) base = base + "/";
        String t = cfg.tokenResolved == null ? "" : cfg.tokenResolved.trim();
        return t.isEmpty() ? base : base + "?token=" + t;
    }

    /** 该路线下浏览器要打开的地址。 */
    static String urlFor(Config cfg, RouteProbe.Route route) {
        return route == RouteProbe.Route.WEB ? webUrl(cfg) : localUrl(cfg);
    }

    static String localUrl(Config cfg) {
        String t = cfg.tokenResolved == null ? "" : cfg.tokenResolved.trim();
        return "http://127.0.0.1:" + cfg.localPort + "/" + (t.isEmpty() ? "" : "?token=" + t);
    }

    // ---------------- WorkBuddy 桌面（1.4.0） ----------------

    /** 系统 ssh 路径：`auto`/空 → 自动探测（Windows 找 ssh.exe，其他系统找 /usr/bin/ssh、PATH），找不到返回空串。 */
    static String resolveSshExe(Config cfg) {
        String v = cfg.wbSshExe == null ? "" : cfg.wbSshExe.trim();
        // off / none / - ＝显式关掉这条路（只走 SFTP 代理）；auto 或空 = 自动探测
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none") || v.equals("-")) return "";
        if (!v.isEmpty() && !AUTO.equalsIgnoreCase(v)) return v;
        String os = System.getProperty("os.name", "").toLowerCase();
        java.util.List<String> cands = new java.util.ArrayList<>();
        if (os.contains("win")) {
            cands.add("C:\\Windows\\System32\\OpenSSH\\ssh.exe");
            cands.add("ssh.exe");
        } else {
            cands.add("/usr/bin/ssh");
            cands.add("/bin/ssh");
            cands.add("ssh");
        }
        for (String c : cands) {
            if (c.contains("/") || c.contains("\\")) {
                if (new java.io.File(c).isFile()) return c;
            } else {
                String path = System.getenv("PATH");
                for (String dir : (path == null ? "" : path).split(java.io.File.pathSeparator)) {
                    java.io.File f = new java.io.File(dir, c);
                    if (f.isFile()) return f.getAbsolutePath();
                }
            }
        }
        return "";
    }

    /** 系统 ssh 用的私钥：填了用自己的；留空 = 与隧道同一把（受限时系统 ssh 会失败并自动回落代理）。 */
    static String resolveSshKey(Config cfg) {
        String v = cfg.wbSshKey == null ? "" : cfg.wbSshKey.trim();
        return v.isEmpty() ? cfg.key : v;
    }

    /** WorkBuddy 窗口是否启用（--no-workbuddy 或 --wb-route off 都算关）。 */
    static boolean wbEnabled(Config cfg) {
        return cfg.wbEnabled && !"off".equalsIgnoreCase(String.valueOf(cfg.wbRoute).trim());
    }

    /** noVNC 的路径与查询串（带密码 —— noVNC 1.3 认 password 参数，省得每次手输）。 */
    static String wbPath(Config cfg) {
        StringBuilder sb = new StringBuilder("/vnc.html?autoconnect=1&resize=scale");
        if (cfg.wbPassword != null && !cfg.wbPassword.trim().isEmpty()) {
            sb.append("&password=").append(java.net.URLEncoder.encode(cfg.wbPassword.trim(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 直连地址：服务器 IPv6 上发布的那个端口（IPv6 字面量要加方括号）。 */
    static String wbDirectUrl(Config cfg) {
        String h = (cfg.wbDirectHost == null || cfg.wbDirectHost.trim().isEmpty())
                ? cfg.hostResolved : cfg.wbDirectHost.trim();
        if (h.contains(":") && !h.startsWith("[")) h = "[" + h + "]";
        return "http://" + h + ":" + cfg.wbDirectPort + wbPath(cfg);
    }

    /** 直连用的主机（裸地址，不带方括号）—— 日志里给人看的。 */
    static String wbDirectHostShown(Config cfg) {
        String h = (cfg.wbDirectHost == null || cfg.wbDirectHost.trim().isEmpty())
                ? cfg.hostResolved : cfg.wbDirectHost.trim();
        return (h.contains(":") ? "[" + h + "]" : h) + ":" + cfg.wbDirectPort;
    }

    /**
     * 直连那个地址**现在到底能不能用**：null = 能，非 null = 人话原因。
     *
     * 为什么必须自检：直连那条路"能不能开"取决于**当前网络**，而路线判读只在启动时做过一次；
     * 端口连不上时浏览器窗口照样会弹出来，只是一个 ERR_CONNECTION_* 空页面 ——
     * 用户看到的就是"WB 窗口能打开但连不上"。所以打开之前先发一条真 HTTP。
     */
    static String probeDirectWb(Config cfg) {
        String h = (cfg.wbDirectHost == null || cfg.wbDirectHost.trim().isEmpty())
                ? cfg.hostResolved : cfg.wbDirectHost.trim();
        return SshTunnel.directProbeError(h, cfg.wbDirectPort, "/vnc.html", 3_000, 4_000);
    }

    /**
     * WorkBuddy 走哪条路。1.10.0 起**默认走域名**（`https://wb.example.com`，经 Cloudflare），
     * 因为直连 IPv6 那条实测不稳；域名那条不依赖 IPv6、也不占 SSH 转发位。
     *
     * @return "domain" / "ssh" / "direct"
     */
    static String wbRouteKind(Config cfg, RouteProbe.Route route) {
        String r = cfg.wbRoute == null ? "auto" : cfg.wbRoute.trim().toLowerCase();
        switch (r) {
            case "domain": case "web": case "cf": case "https":
                if (wbWebBase(cfg).isEmpty()) return "ssh";   // 域名被清空 → 退回 SSH 转发
                return "domain";
            case "direct": case "ipv6":
                return "direct";
            case "ssh": case "tunnel":
                return "ssh";
            default:
                // auto：**跟着路线，IPv6 优先** —— 路线是直连就先直连；否则域名（没配域名就 SSH 转发）
                if (route == RouteProbe.Route.DIRECT) return "direct";
                return wbWebBase(cfg).isEmpty() ? "ssh" : "domain";
        }
    }

    /** 路线的中文名（日志用）。 */

    /** 运行中的 jar 路径与版本（`--version` 与日志里用来核对"我跑的是哪一版"）。 */
    static String jarPathHint() {
        try {
            java.io.File f = new java.io.File(DshClient.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return f.getAbsolutePath();
        } catch (Exception e) {
            return "(未知)";
        }
    }

    static String routeName(RouteProbe.Route r) {
        switch (r) {
            case DIRECT: return "SSH 直连（IPv6:22，不经 Cloudflare）";
            case TUNNEL: return "域名（CF 隧道 · SSH over WebSocket）";
            default:     return "网页隧道（不经 SSH）";
        }
    }

    /** 建一条隧道（GUI 用界面上的值，CLI 用配置值）。 */
    interface TunnelFactory {
        SshTunnel make(RouteProbe.Route route) throws Exception;
    }

    /** 一条路线的实测结果（含已连好的会话）。 */
    static final class RouteSpeed {
        final RouteProbe.Route route;
        final SshTunnel tunnel;
        double kbps = -1;

        RouteSpeed(RouteProbe.Route r, SshTunnel t) {
            route = r;
            tunnel = t;
        }

        String text() {
            return kbps < 0 ? "没测出速度" : String.format("%.0f KB/s", kbps);
        }
    }

    /**
     * 1.12.0：**每条连接都实测速度**再决定走哪条（auto 模式）。
     *
     * 顺序：
     *   1. 先连 primary（auto 判读出来的那条）并实测吞吐；
     *   2. primary 是直连、且实测**低于 `--speed-min`（默认 1000 KB/s）** → 把域名那条也连上、
     *      也实测一遍，然后**取快的**（域名要快 15% 以上才值得换，避免抖动来回）；
     *   3. 直连达标 / 量不出速度 / 门槛设 0 → 就用它，不折腾。
     *
     * 为什么要有这一步：丢包 0% + 握手 3ms 也可能"握手快、传数据慢"（手机热点就是这个样子）。
     * 只会返回一条**已经连好**的会话；落选的那条会被关掉。
     */
    static RouteSpeed pickBySpeed(Config cfg, RouteProbe.Route primary, TunnelFactory f,
                                  java.util.function.Consumer<String> log) throws Exception {
        // primary ∈ {DIRECT, TUNNEL, WEB}（auto 三条都可能；WEB 时先只量网页，不通再退域名）
        double webK = -1;
        if (primary == RouteProbe.Route.WEB) {
            webK = measureWebRouteKbps(cfg, cfg.speedBytes, 3500);
            log.accept("网页隧道（浏览器直连 CF）实测 " + kbText(webK));
            if (webK > 0) return new RouteSpeed(RouteProbe.Route.WEB, null);
            log.accept("（网页那条不通 → 退回域名 · CF 隧道）");
            primary = RouteProbe.Route.TUNNEL;
        }
        RouteSpeed a = new RouteSpeed(primary, f.make(primary));
        a.kbps = measureSshRoute(cfg, a.tunnel, log, routeName(primary));
        log.accept(routeName(primary) + "（判读选中的）实测 " + a.text());
        if (cfg.speedMinKbps <= 0) return a;                        // 速度比较已关
        if (primary == RouteProbe.Route.DIRECT && a.kbps >= cfg.speedMinKbps) return a;   // 直连够快，省事

        double directK = primary == RouteProbe.Route.DIRECT ? a.kbps : -1;
        double tunnelK = primary == RouteProbe.Route.TUNNEL ? a.kbps : -1;
        boolean rttMobile = lastProbe != null && cfg.mobileRttMs > 0 && lastProbe.bestMs >= cfg.mobileRttMs;

        // 移动网络特征（高 RTT / 量不出来 / 吞吐低）→ 把网页那条也量出来
        if (cfg.mobileFloorKbps > 0 && (rttMobile || a.kbps < 0 || a.kbps < cfg.mobileFloorKbps)) {
            if (webK < 0) {
                webK = measureWebRouteKbps(cfg, cfg.speedBytes, 3500);
                log.accept("网页隧道（浏览器直连 CF）实测 " + kbText(webK)
                        + (rttMobile ? "　← RTT " + lastProbe.bestMs + "ms ≥ " + cfg.mobileRttMs
                        + "ms，SSH 在这种链路上是每请求一连接" : ""));
            }
            if (webK > 0 && (rttMobile || a.kbps < 0 || webK >= a.kbps * 1.05)) {
                log.accept("→ 改走网页隧道（" + kbText(webK) + " vs " + a.text() + "）");
                a.tunnel.close();
                return new RouteSpeed(RouteProbe.Route.WEB, null);
            }
        }

        // 另一条 SSH 也量一遍（谁快用谁；要快 15% 以上才换）
        RouteProbe.Route other = (primary == RouteProbe.Route.DIRECT)
                ? RouteProbe.Route.TUNNEL : RouteProbe.Route.DIRECT;
        log.accept("（把另一条也连上量一遍：" + routeName(other) + "）");
        a.tunnel.close();                                            // 先让出本机端口
        RouteSpeed b;
        try {
            b = new RouteSpeed(other, f.make(other));
        } catch (Exception e) {
            log.accept(routeName(other) + " 连不上（" + oneLineMsg(e) + "）→ 就用 " + routeName(primary));
            return new RouteSpeed(primary, f.make(primary));
        }
        b.kbps = measureSshRoute(cfg, b.tunnel, log, routeName(other));
        log.accept(routeName(other) + " 实测 " + b.text());
        if (other == RouteProbe.Route.DIRECT) directK = b.kbps; else tunnelK = b.kbps;

        String pick = chooseBySpeeds(directK, tunnelK, webK, cfg.mobileFloorKbps, rttMobile);
        if ("web".equals(pick)) {
            log.accept("→ 用网页隧道（不经 SSH）");
            b.tunnel.close();
            return new RouteSpeed(RouteProbe.Route.WEB, null);
        }
        RouteProbe.Route want = "tunnel".equals(pick) ? RouteProbe.Route.TUNNEL : RouteProbe.Route.DIRECT;
        if (want == other) {
            log.accept("→ 用 " + routeName(other) + "（" + b.text() + "）");
            return b;
        }
        b.tunnel.close();
        RouteSpeed back = new RouteSpeed(want, f.make(want));        // 端口已让出，重建
        back.kbps = (want == RouteProbe.Route.DIRECT) ? directK : tunnelK;
        log.accept("→ 用 " + routeName(want) + "（" + back.text() + "）");
        return back;
    }

    /** KB/s 的显示（-1 = 没测出来）。 */
    static String kbText(double kbps) {
        return kbps < 0 ? "没测出来" : String.format("%.0f KB/s", kbps);
    }

    /** 一次带可选 Cookie 的 HTTP(S) 抓取（用来量"网页隧道"那条，与 SSH 路线同法）。 */
    private static final class Http {
        int status;
        long bodyBytes;
        long ms;          // 从发出请求到读完的总时间
        long bodyMs = -1; // 去掉首字节等待（TTFB）之后的传输时间
        String cookie = "";
        String bodyHead = "";
        String err = "";
    }

    private static Http fetchHttp(String url, String cookie, int maxBytes, int timeoutMs) {
        Http h = new Http();
        try {
            java.net.URI u = java.net.URI.create(url);
            boolean tls = "https".equalsIgnoreCase(u.getScheme());
            String host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
            if (u.getRawQuery() != null) path += "?" + u.getRawQuery();
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            Exception last = null;
            for (java.net.InetAddress a : addrs) {
                java.net.Socket raw = new java.net.Socket();
                java.net.Socket sock = raw;
                try {
                    raw.connect(new java.net.InetSocketAddress(a, port), timeoutMs);
                    if (tls) {
                        javax.net.ssl.SSLSocketFactory f =
                                (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                        javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) f.createSocket(raw, host, port, true);
                        javax.net.ssl.SSLParameters pp = ss.getSSLParameters();
                        pp.setEndpointIdentificationAlgorithm("HTTPS");
                        pp.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(host)));
                        ss.setSSLParameters(pp);
                        ss.startHandshake();
                        sock = ss;
                    }
                    sock.setSoTimeout(timeoutMs);
                    StringBuilder req = new StringBuilder();
                    req.append("GET ").append(path).append(" HTTP/1.1\r\n")
                            .append("Host: ").append(host).append("\r\n")
                            .append("User-Agent: dsh-client-probe\r\n")
                            .append("Accept: */*\r\n");
                    if (cookie != null && !cookie.isEmpty()) req.append("Cookie: ").append(cookie).append("\r\n");
                    req.append("Connection: close\r\n\r\n");
                    java.io.OutputStream out = sock.getOutputStream();
                    out.write(req.toString().getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    java.io.InputStream in = sock.getInputStream();
                    byte[] buf = new byte[16 * 1024];
                    java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
                    long t0 = System.nanoTime();
                    long firstByteMs = 0;
                    int cap = maxBytes + 64 * 1024;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (all.size() == 0) firstByteMs = (System.nanoTime() - t0) / 1_000_000L;
                        all.write(buf, 0, n);
                        if (all.size() >= cap) break;
                        if ((System.nanoTime() - t0) / 1_000_000L >= timeoutMs) break;
                    }
                    h.ms = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                    byte[] data = all.toByteArray();
                    int idx = indexOfCrlfCrlf(data);
                    if (idx < 0) throw new java.io.IOException("没读到完整的响应头");
                    h.bodyMs = Math.max(1L, h.ms - firstByteMs);
                    String head = new String(data, 0, idx, StandardCharsets.ISO_8859_1);
                    String[] lines = head.split("\r\n");
                    h.status = Integer.parseInt(lines[0].split(" ")[1]);
                    for (String line : lines) {
                        if (line.toLowerCase().startsWith("set-cookie:")) {
                            String v = line.substring("set-cookie:".length()).trim();
                            int semi = v.indexOf(';');
                            h.cookie = (semi > 0 ? v.substring(0, semi) : v);
                            break;
                        }
                    }
                    h.bodyBytes = data.length - (idx + 4);
                    int hl = (int) Math.min(h.bodyBytes, 65536);
                    h.bodyHead = new String(data, idx + 4, hl, StandardCharsets.ISO_8859_1);
                    return h;
                } catch (Exception e) {
                    last = e;
                } finally {
                    try {
                        if (sock != raw) raw.close();
                    } catch (Exception ignore) {
                        // 忽略
                    }
                    try {
                        sock.close();
                    } catch (Exception ignore) {
                        // 忽略
                    }
                }
            }
            if (last != null) throw last;
            h.err = "没有可用地址";
        } catch (Exception e) {
            h.err = oneLineMsg(e);
        }
        return h;
    }

    private static double kbpsOf(long bytes, long ms) {
        return (bytes >= SPEED_MIN_SAMPLE_KB * 1024L) ? (bytes / 1024.0) / (ms / 1000.0) : -1;
    }

    /** 用去掉 TTFB 的流式时间算速率（量的是带宽，不是"等第一个字节"）。 */
    private static double streamKbps(Http h) {
        if (h.bodyMs > 150) return kbpsOf(h.bodyBytes, h.bodyMs);
        return kbpsOf(h.bodyBytes, h.ms);
    }

    private static final java.util.regex.Pattern ASSET_JS =
            java.util.regex.Pattern.compile("assets/[A-Za-z0-9_.\\-]+\\.js");

    /** 经某个本机转发端口，用与"网页那条"相同的方式量 DSH 的吞吐（同一个 633 KB bundle）。 */
    static double measureDshViaForwardKbps(Config cfg, int port, int maxBytes, int timeoutMs) {
        if (port <= 0) return -1;
        String base = "http://127.0.0.1:" + port + "/";
        String token = cfg.tokenResolved == null ? "" : cfg.tokenResolved.trim();
        Http h1 = fetchHttp(base + (token.isEmpty() ? "" : "?token=" + token), "", 4096, timeoutMs);
        if (!h1.err.isEmpty() || h1.status < 200 || h1.status >= 400) return -1;
        Http h2 = fetchHttp(base, h1.cookie, 64 * 1024, timeoutMs);
        if (!h2.err.isEmpty() || h2.status < 200 || h2.status >= 400) return -1;
        double shell = streamKbps(h2);
        java.util.regex.Matcher m = ASSET_JS.matcher(h2.bodyHead);
        if (m.find()) {
            Http h3 = fetchHttp(base + m.group(), h1.cookie, maxBytes, timeoutMs);
            if (h3.err.isEmpty() && h3.status >= 200 && h3.status < 400) {
                double k = streamKbps(h3);
                if (k > 0) return k;
            }
        }
        return shell;
    }

    /** 实测"网页隧道"那条（`https://dsh.example.com/`，完全不经 SSH）。通 → KB/s；-1 = 量不出来。 */
    static double measureWebRouteKbps(Config cfg, int maxBytes, int timeoutMs) {
        String base = cfg.webUrl == null ? "" : cfg.webUrl.trim();
        if (base.isEmpty()) return -1;
        if (!base.startsWith("http")) base = "https://" + base;
        if (!base.endsWith("/")) base = base + "/";
        String token = cfg.tokenResolved == null ? "" : cfg.tokenResolved.trim();
        Http h1 = fetchHttp(base + (token.isEmpty() ? "" : "?token=" + token), "", 4096, timeoutMs);
        if (!h1.err.isEmpty() || h1.status < 200 || h1.status >= 400) return -1;
        Http h2 = fetchHttp(base, h1.cookie, 64 * 1024, timeoutMs);
        if (!h2.err.isEmpty() || h2.status < 200 || h2.status >= 400) return -1;
        double shell = streamKbps(h2);
        java.util.regex.Matcher m = ASSET_JS.matcher(h2.bodyHead);
        if (m.find()) {
            Http h3 = fetchHttp(base + m.group(), h1.cookie, maxBytes, timeoutMs);
            if (h3.err.isEmpty() && h3.status >= 200 && h3.status < 400) {
                double k = streamKbps(h3);
                if (k > 0) return k;
            }
        }
        return shell;
    }

    /** 从按优先级排好的实测结果里挑最快那条（后面的要快 15% 以上才顶掉前面的）；-1 = 都不通。 */
    static int pickBestIndex(java.util.List<Speed> list) {
        int best = -1;
        for (int i = 0; i < list.size(); i++) {
            Speed sp = list.get(i);
            if (!sp.ok()) continue;
            if (best < 0) {
                best = i;
                continue;
            }
            Speed b = list.get(best);
            if (sp.kbps > 0 && b.kbps > 0 && sp.kbps > b.kbps * 1.15) best = i;
        }
        return best;
    }

    /**
     * 三条实测数字 → 走哪条（纯函数，便于自测）。
     *
     * @return "direct" / "tunnel" / "web"
     */
    static String chooseBySpeeds(double directKbps, double tunnelKbps, double webKbps,
                                 int mobileFloorKbps, boolean rttMobile) {
        double bestSsh = Math.max(directKbps, tunnelKbps);
        // 高 RTT（移动网络/热点）= SSH 的「每请求一连接 × 一个 RTT」代价主导 → 网页那条只要可达就用它
        // （手机端 2026-10-13 实测 + 用户实测：浏览器那条更稳）
        if (rttMobile && webKbps > 0) return "web";
        boolean mobileLike = bestSsh >= 0 && mobileFloorKbps > 0 && bestSsh < mobileFloorKbps;
        if (mobileLike && webKbps > 0 && (bestSsh <= 0 || webKbps >= bestSsh * 1.05)) return "web";
        // 两条 SSH 比：域名要快 15% 以上才顶掉直连
        if (directKbps > 0 && tunnelKbps > 0) return tunnelKbps > directKbps * 1.15 ? "tunnel" : "direct";
        if (directKbps > 0) return "direct";
        if (tunnelKbps > 0) return "tunnel";
        return webKbps > 0 ? "web" : "direct";
    }

    /**
     * 量一条 SSH 路线的速度（1.16.0）：经它的转发端口跑一遍 DSH 的 HTTP 流程（与网页那条同法，
     * 都用同一个 633 KB bundle），量不出来再退回 SFTP（注意 SFTP 在高 RTT 下天花板低，
     * 不过现在已按传输助手加了 setBulkRequests(64)）。
     */
    private static double measureSshRoute(Config cfg, SshTunnel t,
                                          java.util.function.Consumer<String> log, String name) {
        double k = measureDshViaForwardKbps(cfg, t.boundPort() > 0 ? t.boundPort() : t.localPort(),
                cfg.speedBytes, (int) Math.max(3500, cfg.speedMaxMs));
        if (k > 0) return k;
        double sftp = t.measureSpeedKbps(cfg.speedBytes, cfg.speedMaxMs);
        if (sftp > 0) log.accept("　（" + name + " 转发端口上的 HTTP 没量出来，退回 SFTP）");
        return sftp;
    }

    /** WorkBuddy 一条路径的实测（先试 /core/rfb.js，不行退回 /vnc.html）。 */
    static Speed wbPathSpeed(String host, int port, boolean tls, int maxBytes, int timeoutMs) {
        Speed sp = httpSpeed(host, port, WB_SPEED_PATH, tls, maxBytes, timeoutMs);
        if (sp.ok()) return sp;
        Speed sp2 = httpSpeed(host, port, "/vnc.html", tls, maxBytes, timeoutMs);
        if (sp2.ok()) return sp2;
        return sp;
    }

    /** 域名入口的 host 与 port（给测速用）。 */
    static String wbWebHost(Config cfg) {
        String base = wbWebBase(cfg);
        if (base.isEmpty()) return "";
        try {
            java.net.URI u = java.net.URI.create(base);
            return u.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * `--linktest`：**裸测这条网络**（不建 SSH、不经任何转发），用来分清
     * "链路本身就这么慢" 还是 "客户端的某条路被量低了"（1.17.0）。
     *
     * 判读：如果这里也只有几十 KB/s，那换哪个客户端都没用（瓶颈在 PC↔手机 或手机的信号/分享模式）；
     * 如果这里有几 MB/s，那就是客户端侧的测速方式/路线问题，再往客户端查。
     */
    static void linkTest(Config cfg) {
        System.out.println("=== DSH 客户端 " + VERSION + " · 链路裸测（不经 SSH、不经转发；每条约读 768 KB / 最多 8 秒）===");
        String[][] targets = {
                {"https://mirrors.aliyun.com/ubuntu/ls-lR.gz", "阿里云（国内 CDN）"},
                {"https://speed.cloudflare.com/__down?bytes=2000000", "Cloudflare 边缘"},
        };
        for (String[] t : targets) {
            long t0 = System.nanoTime();
            Http h = fetchHttp(t[0], "", 768 * 1024, 8000);
            long total = (System.nanoTime() - t0) / 1_000_000L;
            if (!h.err.isEmpty()) {
                System.out.println("  " + t[1] + " → 失败：" + h.err + "（" + total + " ms）");
                continue;
            }
            double k = streamKbps(h);
            System.out.printf("  %-20s HTTP %d  %d KB / 总 %d ms（首字节 %d ms）→ 流式 %s%n", t[1], h.status,
                    h.bodyBytes / 1024, h.ms, h.ms - Math.max(1, h.bodyMs),
                    k < 0 ? "样本太小" : String.format("%.0f KB/s", k));
        }
        System.out.println();
        System.out.println("参考：服务器侧同一测法 3,100–3,300 KB/s（清华/阿里/腾讯）、2,500 KB/s（Cloudflare）。");
        System.out.println("     手机上（不经热点）应该也在这个量级；PC 经过热点若只有几十 KB/s，瓶颈就在 PC↔手机 这一段：");
        System.out.println("     · 换 USB 共享网络（通常立刻从几十 KB/s 变几 MB/s）");
        System.out.println("     · 或看 netsh wlan show interfaces 的接收/传输速率与信号，把手机靠近、切 5GHz/性能模式");
    }

    /**
     * `--speedtest`：把每条连接都真连/真取一遍，各测一次速度，打完表退出。
     * 这是"到底该走哪条"的现场依据，也是排错第一站。
     */
    static void speedTest(Config cfg) throws Exception {
        int kb = cfg.speedBytes / 1024;
        System.out.println("=== DSH 客户端 " + VERSION + " · 每条连接的实测速度（每条约读 " + kb + " KB / 最多 "
                + (cfg.speedMaxMs / 1000) + " 秒）===");
        System.out.println("目标服务器：" + cfg.hostResolved + ":" + cfg.port
                + "｜WB 容器端口 " + cfg.wbDirectPort + "｜域名入口 " + wbWebBase(cfg) + "｜门槛 "
                + (cfg.speedMinKbps <= 0 ? "已关（不比速度）" : cfg.speedMinKbps + " KB/s"));
        System.out.println();
        System.out.println("[DSH 界面 · 两条 SSH 路线]");
        RouteSpeed direct = null;
        RouteSpeed tunnel = null;
        try {
            SshTunnel sd = new SshTunnel(cfg.hostResolved, cfg.port, cfg.user, cfg.key,
                    cfg.localPort, cfg.remotePort, 0, 0, null);
            sd.start();
            direct = new RouteSpeed(RouteProbe.Route.DIRECT, sd);
            direct.kbps = sd.measureSpeedKbps(cfg.speedBytes, cfg.speedMaxMs);
            System.out.println("  ① " + routeName(RouteProbe.Route.DIRECT) + " → " + direct.text());
        } catch (Exception e) {
            System.out.println("  ① " + routeName(RouteProbe.Route.DIRECT) + " → 连不上：" + oneLineMsg(e));
        }
        try {
            SshTunnel st = new SshTunnel(cfg.tunnelHost, cfg.port, cfg.user, cfg.key,
                    cfg.localPort + 1, cfg.remotePort, cfg.wbLocalPort, cfg.wbRemotePort, cfg.tunnelWsUrl);
            st.start();
            tunnel = new RouteSpeed(RouteProbe.Route.TUNNEL, st);
            tunnel.kbps = st.measureSpeedKbps(cfg.speedBytes, cfg.speedMaxMs);
            System.out.println("  ② " + routeName(RouteProbe.Route.TUNNEL) + " → " + tunnel.text());
        } catch (Exception e) {
            System.out.println("  ② " + routeName(RouteProbe.Route.TUNNEL) + " → 连不上：" + oneLineMsg(e));
        }
        System.out.println();
        System.out.println("[WorkBuddy 桌面 · 三条路径]（都取同一个静态资源，才可比）");
        Speed wbDirect = wbPathSpeed(cfg.hostResolved, cfg.wbDirectPort, false, cfg.speedBytes, 6000);
        System.out.println("  ① 直连 [" + cfg.hostResolved + "]:" + cfg.wbDirectPort + " → " + wbDirect.text());
        Speed wbDomain = new Speed();
        String wh = wbWebHost(cfg);
        if (wh.isEmpty()) {
            wbDomain.err = "没配 WB 域名入口";
        } else {
            wbDomain = wbPathSpeed(wh, 443, true, cfg.speedBytes, 6000);
        }
        System.out.println("  ② 域名 " + wbWebBase(cfg) + " → " + wbDomain.text());
        Speed wbSsh;
        if (tunnel != null && tunnel.tunnel.boundWbPort() > 0) {
            wbSsh = wbPathSpeed("127.0.0.1", tunnel.tunnel.boundWbPort(), false, cfg.speedBytes, 6000);
            System.out.println("  ③ SSH 转发 127.0.0.1:" + tunnel.tunnel.boundWbPort() + " → " + wbSsh.text());
        } else {
            wbSsh = new Speed();
            wbSsh.err = "没有可用的 SSH 转发（域名那条没连上）";
            System.out.println("  ③ SSH 转发 → " + wbSsh.text());
        }
        System.out.println();
        System.out.println("[按实测会怎么选]");
        System.out.println("  DSH：" + speedAdvice(cfg, direct, tunnel));
        System.out.println("  WB ：" + wbSpeedAdvice(cfg, wbDirect, wbDomain, wbSsh));
        if (direct != null) direct.tunnel.close();
        if (tunnel != null) tunnel.tunnel.close();
    }

    private static String speedAdvice(Config cfg, RouteSpeed direct, RouteSpeed tunnel) {
        if (cfg.speedMinKbps <= 0) return "速度比较已关（--speed-min 0）→ 按判读走";
        if (direct == null || direct.kbps < 0) return "直连量不出速度 → 域名";
        if (direct.kbps >= cfg.speedMinKbps) return "直连（" + direct.text() + " ≥ 门槛）";
        if (tunnel != null && tunnel.kbps > direct.kbps * 1.15) {
            return "域名（" + tunnel.text() + " 比直连 " + direct.text() + " 快 15% 以上）";
        }
        return "直连（域名没快到值得换：" + (tunnel == null ? "测不到" : tunnel.text()) + "）";
    }

    private static String wbSpeedAdvice(Config cfg, Speed d, Speed dom, Speed ssh) {
        Speed best = null;
        String name = "";
        Speed[] arr = {d, dom, ssh};
        String[] names = {"① 直连协议", "② 域名", "③ SSH 转发"};
        for (int i = 0; i < arr.length; i++) {
            if (!arr[i].ok()) continue;
            if (best == null) {
                best = arr[i];
                name = names[i];
                continue;
            }
            if (arr[i].kbps > 0 && best.kbps > 0 && arr[i].kbps > best.kbps * 1.15) {
                best = arr[i];
                name = names[i];
            }
        }
        return best == null ? "三条都不通" : name + "（" + best.text() + "）";
    }

    /**
     * WorkBuddy 走哪条（**2.0.0：跟随 DSH 那条路**）。
     *
     * 映射按「网络路径」而不是按名字：
     *   DSH = direct / tunnel（都建了 SSH 会话）→ WB ③ SSH 转发（同一条会话、同一条网络路径）
     *   DSH = web（浏览器直连 CF，没有 SSH）    → WB ② 域名 wb.example.com（同样经 CF、同样不经 SSH）
     *
     * 返回的第一个就是**绑定**那条；它不通才依次回落后面的。
     * 显式 `--wb-route direct|domain|ssh` = 只试指定那一条。
     */
    static java.util.List<String> wbCandidates(Config cfg, RouteProbe.Route dshRoute) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String raw = cfg.wbRoute == null ? "auto" : cfg.wbRoute.trim().toLowerCase();
        if (!(raw.isEmpty() || AUTO.equals(raw))) {
            out.add(wbRouteKind(cfg, RouteProbe.Route.TUNNEL));    // 显式：只这一条
            return out;
        }
        String bound = (dshRoute == RouteProbe.Route.WEB) ? "domain" : "ssh";
        if ("domain".equals(bound) && wbWebBase(cfg).isEmpty()) bound = "ssh";   // 域名入口关掉 → 回 SSH
        out.add(bound);
        if (!"domain".equals(bound) && !wbWebBase(cfg).isEmpty()) out.add("domain");
        if (!"ssh".equals(bound)) out.add("ssh");
        if (!"direct".equals(bound)) out.add("direct");            // 最后兜底：HTTP over IPv6 到 6080
        return out;
    }

    /** 绑定关系的人话说明（日志里打出来，避免以后又对不上）。 */
    static String wbBindNote(RouteProbe.Route dshRoute) {
        return dshRoute == RouteProbe.Route.WEB
                ? "WorkBuddy 跟随 DSH：DSH=网页隧道（经 CF、不经 SSH）→ WB 绑定 ② 域名 wb.example.com"
                : "WorkBuddy 跟随 DSH：DSH=" + routeName(dshRoute)
                  + "（有 SSH 会话）→ WB 绑定 ③ SSH 转发 127.0.0.1:6080";
    }

    /** WB 域名入口的基址（空 = 这条路被关掉）。 */
    static String wbWebBase(Config cfg) {
        String v = cfg.wbWebUrl == null ? "" : cfg.wbWebUrl.trim();
        if (v.isEmpty() || "-".equals(v) || "off".equalsIgnoreCase(v)) return "";
        if (!v.startsWith("http")) v = "https://" + v;
        if (!v.endsWith("/")) v = v + "/";
        return v;
    }

    /** WB 域名那条的完整地址（带 autoconnect 与密码）。 */
    static String wbWebFullUrl(Config cfg) {
        String base = wbWebBase(cfg);
        if (base.isEmpty()) return "";
        return base + "vnc.html?autoconnect=1&resize=scale"
                + (cfg.wbPassword != null && !cfg.wbPassword.trim().isEmpty()
                ? "&password=" + java.net.URLEncoder.encode(cfg.wbPassword.trim(), StandardCharsets.UTF_8) : "");
    }

    /**
     * HTTPS 自检（域名那条用）：通 → null，不通 → 人话原因。
     *
     * 为什么不用 `java.net.http.HttpClient`：它只会试解析出来的**第一个**地址。实测本机到某个
     * Cloudflare 边缘 IP 的 TLS 握手会被黑洞（TCP 连得上、ClientHello 没有响应），HttpClient 于是
     * 直接报 connect timed out，而同一域名换另一个边缘 IP 0.1 秒就通 —— 会把"域名可用"误判成不通。
     * 所以这里**逐个地址**试：每个地址 3 秒连接 + 3 秒读，TLS 照常校验证书链与主机名（SNI + HTTPS）。
     */
    static String webProbeError(String url, int perAddrMs) {
        if (url == null || url.isEmpty()) return "没配置域名地址";
        String host = null;
        try {
            java.net.URI u = java.net.URI.create(url);
            host = u.getHost();
            int port = u.getPort() > 0 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(host);
            String firstErr = null;
            for (java.net.InetAddress addr : addrs) {
                try {
                    return probeOneAddress(addr, host, port, path, perAddrMs);
                } catch (Exception e) {
                    String m = (e.getMessage() == null || e.getMessage().isBlank()) ? e.toString() : e.getMessage();
                    m = m.replace('\n', ' ');
                    if (firstErr == null) firstErr = addr.getHostAddress() + " → " + m;
                    else firstErr = firstErr + "；" + addr.getHostAddress() + " → " + m;
                }
            }
            return firstErr == null ? "解析不到地址" : firstErr;
        } catch (Exception e) {
            String m = (e.getMessage() == null || e.getMessage().isBlank()) ? e.toString() : e.getMessage();
            return (host == null ? "" : host + "：") + m.replace('\n', ' ');
        }
    }

    /** 试一个具体地址：成功返回 null，失败抛异常（消息给人看）。 */
    private static String probeOneAddress(java.net.InetAddress addr, String host, int port, String path,
                                          int perAddrMs) throws Exception {
        javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
        java.net.Socket raw = new java.net.Socket();
        try {
            raw.connect(new java.net.InetSocketAddress(addr, port), perAddrMs);
            try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) f.createSocket(raw, host, port, true)) {
                javax.net.ssl.SSLParameters p = s.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");          // 证书链 + 主机名都校验
                p.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(host)));
                s.setSSLParameters(p);
                s.setSoTimeout(perAddrMs);
                s.startHandshake();
                java.io.OutputStream out = s.getOutputStream();
                out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                        + "\r\nUser-Agent: dsh-client-probe\r\nAccept: */*\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                byte[] buf = new byte[64];
                int n = s.getInputStream().read(buf);
                if (n <= 0) throw new java.io.IOException("握手后没有任何响应");
                String line = new String(buf, 0, n, StandardCharsets.ISO_8859_1).split("\r\n")[0];
                if (!line.startsWith("HTTP/")) throw new java.io.IOException("不是 HTTP 响应：" + line);
                int code = Integer.parseInt(line.split(" ")[1]);
                // 2xx/3xx 都算"能到"：3xx 可能是 Cloudflare Access 的登录跳转
                if (code >= 200 && code < 400) return null;
                return "HTTP " + code;
            }
        } finally {
            try {
                raw.close();
            } catch (Exception ignore) {
                // 已经关了
            }
        }
    }

    /** 一条路径的实测结果（1.12.0：既要"通不通"，也要"多快"）。 */
    static final class Speed {
        String err = "";
        double kbps = -1;
        long bytes;
        long ms;

        boolean ok() {
            return err.isEmpty();
        }

        /** 给人看的一行：`820 KB/s（105 KB / 128 ms）` 或 `不通：…`。 */
        String text() {
            if (!ok()) return "不通：" + err;
            if (kbps < 0) return "通（样本太小，没算出速度）";
            return String.format("%.0f KB/s（%d KB / %d ms）", kbps, bytes / 1024, ms);
        }
    }

    /**
     * 实测一条 HTTP(S) 路径的速度。
     *
     * @param tls      true = 逐个解析出来的地址试 TLS（见 webProbeError 里为什么）
     * @param maxBytes 最多读多少响应体（内存/流量上限）
     * @return Speed（err 非空 = 这条不通）
     */
    static Speed httpSpeed(String host, int port, String path, boolean tls, int maxBytes, int timeoutMs) {
        Speed sp = new Speed();
        java.util.List<java.net.InetAddress> addrs = new java.util.ArrayList<>();
        try {
            if (tls) addrs.addAll(java.util.Arrays.asList(java.net.InetAddress.getAllByName(host)));
            else addrs.add(java.net.InetAddress.getByName(host));
        } catch (Exception e) {
            sp.err = "解析失败：" + oneLineMsg(e);
            return sp;
        }
        String firstErr = null;
        for (java.net.InetAddress a : addrs) {
            try {
                fetchInto(sp, a, host, port, path, tls, maxBytes, timeoutMs);
                return sp;
            } catch (Exception e) {
                String m = a.getHostAddress() + " → " + oneLineMsg(e);
                firstErr = (firstErr == null) ? m : firstErr + "；" + m;
            }
        }
        sp.err = (firstErr == null) ? "没有可用地址" : firstErr;
        return sp;
    }

    private static String oneLineMsg(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isBlank()) ? e.toString() : m.replace('\n', ' ');
    }

    /** 真正抓一次：连 addr:port，发一条 GET，读响应体（最多 maxBytes / timeoutMs）。 */
    private static void fetchInto(Speed sp, java.net.InetAddress addr, String host, int port, String path,
                                  boolean tls, int maxBytes, int timeoutMs) throws Exception {
        java.net.Socket raw = new java.net.Socket();
        java.net.Socket sock = raw;
        try {
            raw.connect(new java.net.InetSocketAddress(addr, port), timeoutMs);
            if (tls) {
                javax.net.ssl.SSLSocketFactory f =
                        (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
                javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket) f.createSocket(raw, host, port, true);
                javax.net.ssl.SSLParameters p = ss.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                p.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(host)));
                ss.setSSLParameters(p);
                ss.startHandshake();
                sock = ss;
            }
            sock.setSoTimeout(timeoutMs);
            java.io.OutputStream out = sock.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: " + host
                    + "\r\nUser-Agent: dsh-client-speed\r\nAccept: */*\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            java.io.InputStream in = sock.getInputStream();
            byte[] buf = new byte[16 * 1024];
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            long t0 = System.nanoTime();
            int cap = maxBytes + 64 * 1024;          // 留出响应头的余量
            int n;
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
                if (all.size() >= cap) break;
                if ((System.nanoTime() - t0) / 1_000_000L >= timeoutMs) break;
            }
            long ms = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            byte[] data = all.toByteArray();
            int idx = indexOfCrlfCrlf(data);
            if (idx < 0) throw new java.io.IOException("没读到完整的 HTTP 响应头（拿到 " + data.length + " B）");
            String status = new String(data, 0, Math.min(idx, 80), StandardCharsets.ISO_8859_1)
                    .split("\r\n")[0];
            if (!status.startsWith("HTTP/")) throw new java.io.IOException("不是 HTTP 响应：" + status);
            int code = Integer.parseInt(status.split(" ")[1]);
            if (code < 200 || code >= 400) throw new java.io.IOException("HTTP " + code);
            sp.bytes = data.length - (idx + 4);
            sp.ms = ms;
            // 样本太小就别报速度（误差太大），但仍算"通"
            sp.kbps = (sp.bytes >= 16 * 1024) ? (sp.bytes / 1024.0) / (ms / 1000.0) : -1;
        } finally {
            try {
                if (sock != raw) raw.close();
            } catch (Exception ignore) {
                // 忽略
            }
            try {
                sock.close();
            } catch (Exception ignore) {
                // 忽略
            }
        }
    }

    private static int indexOfCrlfCrlf(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        }
        return -1;
    }

    /** 域名那条路的自检（不带密码，避免把密码写进日志/异常）。 */
    static String probeWebWb(Config cfg) {
        String base = wbWebBase(cfg);
        if (base.isEmpty()) return "没配置 WB 域名";
        return webProbeError(base + "vnc.html", 3_000);
    }

    /** SSH 转发后的本机地址。 */
    static String wbLocalUrl(int boundPort, String password) {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(boundPort)
                .append("/vnc.html?autoconnect=1&resize=scale");
        if (password != null && !password.trim().isEmpty()) {
            sb.append("&password=").append(java.net.URLEncoder.encode(password.trim(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 按路线选 WorkBuddy 的打开地址（1.10.0：auto = 域名优先）。 */
    static String wbUrl(Config cfg, RouteProbe.Route route) {
        switch (wbRouteKind(cfg, route)) {
            case "domain":
                return wbWebFullUrl(cfg);
            case "direct":
                return wbDirectUrl(cfg);
            default:
                return wbLocalUrl(cfg.wbLocalPort, cfg.wbPassword);
        }
    }

    /** 无界面模式：保持隧道活着，断了自动重连（我的验证脚本也用它）。 */
    private static void serveForever(Config cfg, RouteProbe.Route route) throws Exception {
        if (route == RouteProbe.Route.WEB) {
            // 网页隧道：本进程没有 SSH 要守，浏览器直接开 CF 地址即可
            System.out.println("route: WEB tunnel (no SSH)");
            System.out.println("open: " + webUrl(cfg));
            System.out.flush();
            if (cfg.openBrowser) ClientFrame.openInAppWindow(webUrl(cfg), true);
            while (true) {
                Thread.sleep(60_000);   // 保持进程（脚本里方便 ^C 结束）
            }
        }
        boolean autoRoute = RouteProbe.Route.of(cfg.route) == null;
        RouteProbe.Route cur = route;
        SshTunnel t = null;
        System.out.println("route: " + cur + (cur == RouteProbe.Route.TUNNEL ? " (SSH over WebSocket)" : " (direct IPv6)"));
        while (true) {
            try {
                // 1.12.0：auto 下**每条连接都实测一次速度**，直连偏慢就把域名也量了再取快的
                if (autoRoute && cfg.speedMinKbps > 0) {
                    final RouteProbe.Route want = cur;
                    RouteSpeed rs = pickBySpeed(cfg, want, r -> {
                        SshTunnel x = newTunnel(cfg, r);
                        x.start();
                        return x;
                    }, System.out::println);
                    cur = rs.route;
                    t = rs.tunnel;
                    if (cur == RouteProbe.Route.WEB) {          // 实测说 SSH 不行、网页那条行
                        System.out.println("route: WEB（网页隧道，不经 SSH）");
                        System.out.println("open: " + webUrl(cfg));
                        System.out.flush();
                        if (cfg.openBrowser) ClientFrame.openInAppWindow(webUrl(cfg), true);
                        while (true) Thread.sleep(60_000);
                    }
                } else {
                    t = newTunnel(cfg, cur);
                    t.start();
                }
                if (cfg.localPort > 0) {
                    System.out.println("隧道就绪 → " + localUrl(cfg) + "（自检：" + t.lastSelfCheck() + "）");
                } else {
                    System.out.println("（本次只建 WorkBuddy 那条转发，没有 DSH 转发）");
                }
                if (wbEnabled(cfg)) {
                    if (t.boundWbPort() > 0) {
                        boolean ok = t.lastWbSelfCheck() != null && t.lastWbSelfCheck().startsWith("HTTP/");
                        System.out.println((ok ? "workbuddy 转发就绪 → "
                                : "workbuddy 转发不可用（本机端口已绑，但连过去没数据）→ ")
                                + t.workbuddyUrl(cfg.wbPassword) + "（自检：" + t.lastWbSelfCheck() + "）");
                    }
                    if (!t.wbForwardError().isEmpty()) {
                        System.out.println("⚠️ " + t.wbForwardError());
                    }
                }
                System.out.flush();
                if (cfg.simulateDropSeconds > 0) {
                    System.out.println("（自测：${cfg.simulateDropSeconds}s 后主动断开一次）".replace("${cfg.simulateDropSeconds}", String.valueOf(cfg.simulateDropSeconds)));
                    System.out.flush();
                    Thread.sleep(cfg.simulateDropSeconds * 1000L);
                    t.close();
                    System.out.println("（自测：已主动断开，等看门狗重连）");
                    System.out.flush();
                }
            } catch (Exception e) {
                System.out.println("连接失败：" + e.getMessage());
                if (cur == RouteProbe.Route.TUNNEL) {
                    System.out.println("（CF 隧道这条路的两个转发都在，值得重试；"
                            + "真要用浏览器那条就加 --route web，代价是整条进程没有 SSH 通道，"
                            + "WorkBuddy 窗口与容器启停都用不了）");
                }
                System.out.println("（5 秒后重试；^C 退出）");
                System.out.flush();
                Thread.sleep(5_000);
                continue;
            }
            while (t.isAlive()) {
                Thread.sleep(2_000);
            }
            System.out.println("隧道断开，正在重连…");
            System.out.flush();
        }
    }

    /** 私钥探测：按 OpenSSH 的默认顺序试（Windows 上很常见的是 id_rsa，不是 id_ed25519）。 */
    static String detectKeyPath() {
        for (String name : new String[]{"id_ed25519", "id_ecdsa", "id_rsa", "id_dsa"}) {
            File f = new File(sshDir(), name);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return new File(sshDir(), "id_ed25519").getAbsolutePath();
    }

    static String sshDir() {
        String win = System.getenv("USERPROFILE");
        String home = (win != null && !win.isEmpty()) ? win : System.getProperty("user.home");
        return new File(home, ".ssh").getAbsolutePath();
    }

    static File settingsFile() {
        return new File(System.getProperty("user.home"), ".dsh-client.properties");
    }

    /** 浏览器候选：优先 Edge/Chrome 的 `--app=` 模式（无地址栏）。 */
    static String[] browserCandidates() {
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        return new String[]{
                join(pf86, "Microsoft\\Edge\\Application\\msedge.exe"),
                join(pf, "Microsoft\\Edge\\Application\\msedge.exe"),
                join(pf, "Google\\Chrome\\Application\\chrome.exe"),
                join(pf86, "Google\\Chrome\\Application\\chrome.exe"),
                join(local, "Google\\Chrome\\Application\\chrome.exe"),
                // 便于在 Linux 上自测
                "/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome",
        };
    }

    private static String join(String dir, String tail) {
        return dir == null || dir.isEmpty() ? null : new File(dir, tail).getAbsolutePath();
    }

    /**
     * 把设置里的 `auto` / `log[:路径]` / 字面值解析成这一次真正要用的地址与 token。
     *
     * 需要问服务器时**只建一条隧道会话**，一次读回当前 IPv6 与最新 token ——
     * 这两个恰好就是"每次重启都会变"的那两个值。问不到就回落到内置兜底值，
     * 绝不因为发现失败而拒绝启动。
     */
    static void resolveEndpoints(Config cfg) {
        boolean hostAuto = isAuto(cfg.host);
        boolean tokenAuto = isAuto(cfg.token);
        boolean tokenFromLog = cfg.token != null
                && (cfg.token.trim().equals(TOKEN_FROM_LOG) || cfg.token.trim().startsWith(TOKEN_FROM_LOG + ":"));

        StringBuilder note = new StringBuilder();
        String localLogToken = null;
        if (tokenFromLog) {
            String trimmed = cfg.token.trim();
            String path = trimmed.equals(TOKEN_FROM_LOG) ? null : trimmed.substring(TOKEN_FROM_LOG.length() + 1).trim();
            localLogToken = tokenFromLogFile(path);
            if (localLogToken != null) {
                note.append("token：取自本机 ").append(logPath(path)).append("（").append(localLogToken.length()).append(" 字符）");
            } else {
                note.append("token：本机读不到 ").append(logPath(path)).append("，改经隧道读服务器的同一路径");
            }
        }

        boolean needRemote = cfg.discover && (hostAuto || tokenAuto || (tokenFromLog && localLogToken == null));
        RemoteInfo.Fetched fetched = null;
        if (needRemote) {
            try {
                fetched = RemoteInfo.fetch(cfg.tunnelHost, cfg.port, cfg.user, cfg.key, cfg.tunnelWsUrl, 15_000);
                if (fetched.ipv6 != null) {
                    note.append(sep(note)).append("地址：经隧道发现 ").append(fetched.ipv6);
                }
                if (fetched.token != null) {
                    note.append(sep(note)).append("token：经隧道取到（").append(fetched.token.length()).append(" 字符）");
                }
                if (!fetched.note.isEmpty()) {
                    note.append(fetched.note);
                }
            } catch (Exception e) {
                note.append(sep(note)).append("经隧道发现失败（")
                        .append(String.valueOf(e.getMessage()).replace('\n', ' '))
                        .append("），本次用内置兜底值");
            }
        }

        if (!hostAuto) {
            cfg.hostResolved = cfg.host.trim();
        } else if (fetched != null && fetched.ipv6 != null) {
            cfg.hostResolved = fetched.ipv6;
        } else {
            cfg.hostResolved = DEFAULT_HOST;
        }

        if (!tokenAuto && !tokenFromLog) {
            cfg.tokenResolved = cfg.token == null ? "" : cfg.token.trim();
        } else if (localLogToken != null) {
            cfg.tokenResolved = localLogToken;
        } else if (fetched != null && fetched.token != null) {
            cfg.tokenResolved = fetched.token;
        } else {
            cfg.tokenResolved = DEFAULT_TOKEN;
        }

        cfg.resolveNote = note.toString();
    }

    /** `null` / 空 / `auto` 都算"自动发现"。 */
    static boolean isAuto(String v) {
        return v == null || v.trim().isEmpty() || AUTO.equalsIgnoreCase(v.trim());
    }

    private static String sep(StringBuilder sb) {
        return sb.length() == 0 ? "" : "｜";
    }

    private static File logPath(String path) {
        return (path == null || path.isEmpty())
                ? new File(System.getProperty("user.home", "."), ".dsh/web.log")
                : new File(path);
    }

    /**
     * 读**本机** dsh-web 日志里最新那行 token；读不到返回 null（调用方会改经隧道去读服务器那份）。
     * 保留这个方法是为了"在服务器本机跑客户端"这条路径 —— 那时不需要隧道。
     */
    static String tokenFromLogFile(String path) {
        File log = logPath(path);
        if (!log.isFile()) return null;
        String found = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(log), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                Matcher m = Pattern.compile("token=([A-Za-z0-9_-]+)").matcher(line);
                if (m.find()) found = m.group(1);   // 保留最后一处 = 最新一次启动
            }
        } catch (Exception e) {
            return null;
        }
        return found.isEmpty() ? null : found;
    }

    /** 老配置里存的历史内置默认值是"过期的写死值"，不是用户的选择 → 迁移成 auto。 */
    static String migrateRaw(String stored, String legacy, String fallback) {
        if (stored == null || stored.trim().isEmpty()) return fallback;
        String v = stored.trim();
        if (legacy == null || legacy.isEmpty()) return v;
        return v.equals(legacy) ? fallback : v;
    }

    private static Config loadConfig() {
        Config c = new Config();
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(settingsFile())) {
            p.load(in);
        } catch (Exception e) {
            return c; // 首次运行：没有配置文件
        }
        c.host = migrateRaw(p.getProperty("host"), LEGACY_HOST, c.host);
        c.port = parseInt(p.getProperty("port"), c.port);
        c.user = p.getProperty("user", c.user);
        c.key = p.getProperty("key", c.key);
        c.localPort = parseInt(p.getProperty("localPort"), c.localPort);
        c.remotePort = parseInt(p.getProperty("remotePort"), c.remotePort);
        c.token = migrateRaw(p.getProperty("token"), LEGACY_TOKEN, c.token);
        c.route = p.getProperty("route", c.route);
        c.webUrl = p.getProperty("webUrl", c.webUrl);
        c.tunnelHost = p.getProperty("tunnelHost", c.tunnelHost);
        c.tunnelWsUrl = p.getProperty("tunnelWsUrl", c.tunnelWsUrl);
        c.openBrowser = Boolean.parseBoolean(p.getProperty("openBrowser", String.valueOf(c.openBrowser)));
        c.wbEnabled = Boolean.parseBoolean(p.getProperty("wbEnabled", String.valueOf(c.wbEnabled)));
        c.wbAutoOpen = Boolean.parseBoolean(p.getProperty("wbAutoOpen", String.valueOf(c.wbAutoOpen)));
        c.wbRoute = p.getProperty("wbRoute", c.wbRoute);
        c.wbWebUrl = p.getProperty("wbWebUrl", c.wbWebUrl);
        c.speedMinKbps = parseInt(p.getProperty("speedMinKbps"), c.speedMinKbps);
        c.wbLocalPort = parseInt(p.getProperty("wbLocalPort"), c.wbLocalPort);
        c.wbRemotePort = parseInt(p.getProperty("wbRemotePort"), c.wbRemotePort);
        c.wbDirectPort = parseInt(p.getProperty("wbDirectPort"), c.wbDirectPort);
        c.wbPassword = p.getProperty("wbPassword", c.wbPassword);
        c.wbContainer = p.getProperty("wbContainer", c.wbContainer);
        c.wbSshExe = p.getProperty("wbSshExe", c.wbSshExe);
        c.wbSshKey = p.getProperty("wbSshKey", c.wbSshKey);
        c.wbDirectHost = p.getProperty("wbDirectHost", c.wbDirectHost);
        return c;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) continue;
            String k = a.substring(2);
            if (k.equals("help") || k.equals("serve") || k.equals("print-url") || k.equals("no-open")
                    || k.equals("no-discover") || k.equals("workbuddy") || k.equals("no-workbuddy")
                    || k.equals("wb-open") || k.equals("wb-status") || k.equals("wb-start") || k.equals("wb-stop")) {
                out.put(k, "");
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(k, args[++i]);
            } else {
                out.put(k, "");
            }
        }
        return out;
    }

    private static void usage() {
        System.out.println("DSH 客户端（电脑版）" + VERSION + " · " + jarPathHint());
        System.out.println("""
                —— 用 IPv6/SSH 把服务器上的 DSH 界面搬到本机打开

                  java -jar DshClient.jar                  图形界面（默认，自动开独立窗口）
                  java -jar DshClient.jar --serve          只起隧道并打印 URL（脚本/排错）
                  java -jar DshClient.jar --print-url      只打印 URL
                  java -jar DshClient.jar --no-open        起隧道但不开浏览器
                  java -jar DshClient.jar --probe          只探测+判读走哪条路，不起通道

                地址与 token（两个"每次重启都可能变"的值）：
                  默认都是 auto —— 启动时先经 Cloudflare 隧道连一次服务器，读回
                  /proc/net/if_inet6（当前全局 IPv6；校园网 /64 前缀会轮换）与
                  ~/.dsh/web.log（dsh web 最新 token）。隧道问不到就用内置兜底值，不会拒绝启动。
                  --host <IPv6 或域名>    填了就按填的用，不再发现
                  --token <token|auto|log[:路径]>
                                        auto = 经隧道读服务器日志；log = 先试本机日志，读不到再走隧道
                  --no-discover           完全不连隧道做发现（离线/排错用）

                走哪条路（默认 auto；三条候选：direct / tunnel / web）：
                --route auto|probe|direct|tunnel|web
                  auto    **默认**。判读只看两件事：本机有没有全局 IPv6、握手 RTT 多高。
                          · 没有全局 IPv6 → 域名（tunnel）
                          · 握手 RTT ≥ --mobile-rtt（默认 60ms，热点/移动网络）→ **先试网页（web）**，
                            不通再退域名（tunnel）—— 高 RTT 下 SSH 的瓶颈是「每请求一连接 × 一个 RTT」
                          · 其余（校园网/宽带）→ 直连达标就走 direct，不达标走 tunnel；
                            然后把三条都实测一遍吞吐，谁快用谁（域名要快 15%% 才顶掉直连）
                          · 运行期：direct 掉线 2 次（或刚连上就断）→ 切域名；域名不通 → 网页
                  probe   同 auto（显式写出来而已）
                  direct  SSH 直连服务器 IPv6:22 + 本机端口转发（不经 Cloudflare）
                  tunnel  SSH 装进 WebSocket 走 Cloudflare（wss://ssh.example.com）
                  web     浏览器直接开 https://dsh.example.com/（完全不经 SSH；CF 会终止 TLS）
                  ⚠️ direct 指的是「SSH 直连」；WorkBuddy 的「① 直连 [服务器IPv6]:6080」是 HTTP over IPv6，
                     和这条无关，见下面 --wb-route

              实测速度（1.12.0；为什么需要它：丢包 0%% + 握手 3ms 也可能"握手快、传数据慢"）：
                  --speedtest            把每条连接都真连一遍、各测一次吞吐，打完表退出
                  --speed-min <KB/s>     判"够快"的门槛，默认 1000。直连低于它时把域名也连上量一遍，
                                         取快的（域名要快 15%% 以上才换）。0 = 关掉速度比较
                  --speed-bytes <字节>   每条路测速最多读多少，默认 192 KB
                  --mobile-rtt <ms>      握手 RTT ≥ 它（默认 60）→ 先试网页隧道（高 RTT 下
                                         SSH 是「每请求一连接 × 一个 RTT」）。0 = 关掉这条判据
                  --mobile-floor <KB/s>  "移动网络特征"下限，默认 150：SSH 实测低于它就先把
                                         网页那条量出来比一比
                  --web-url   <网页隧道地址>   默认 %s
                  --tunnel-ws <wss 地址>       默认 %s

                WorkBuddy 桌面（1.4.0：同一个 jar 里再开一个桌面窗口）：
                  窗口**默认不自动打开**（点界面上的按钮即可；想要打开就开，用 --wb-open）。
  一条 SSH 会话带两个转发：3080 → DSH 界面，6080 → WorkBuddy 桌面（noVNC）。
                  --wb-open              打开 jar 时顺带把 WorkBuddy 窗口也开出来
                                         （**默认关**：只开 DSH 窗口，WB 用按钮开）
                  --no-workbuddy          连按钮都禁用
                  --wb-route auto|domain|ssh|direct|off
                                          auto（默认，2.0.0）**跟随 DSH 那条路**：
                                          · DSH 有 SSH 会话（direct / tunnel）→ ③ SSH 转发 127.0.0.1:6080
                                            （同一条会话、同一条网络路径）
                                          · DSH 走 web（浏览器直连 CF）→ ② 域名 wb.example.com
                                            （同样经 CF、同样不经 SSH）
                                          绑定那条不通才依次回落后面的；显式写 direct/domain/ssh 就只试那一条
                  --wb-web-url <域名>     WorkBuddy 的域名入口，默认 %s（空/off = 关掉这条路）
                  --wb-local-port <端口>  本机转发端口，默认 6080
                  --wb-password <密码>    noVNC 的 VNC 密码（服务器上 x11vnc -rfbauth 设的那个）
                  --wb-container <名字>   容器名，默认 workbuddy-test
                  --wb-status            打印容器在不在跑（连上→打印→退出）
                  --wb-ssh <ssh路径>     启停容器时先用**系统 ssh**；默认 auto = 自动探测
                                         （Windows 找 ssh.exe，其他系统找 /usr/bin/ssh 或 PATH）。
                                         要关掉这条路就写 --wb-ssh off（只走 SFTP 代理）。
                                         ⚠️ 要求那把 key **允许 exec** —— 服务端
                                         command="internal-sftp" 的限制换客户端也绕不过
                  --wb-ssh-key <私钥>     给系统 ssh 用的 -i 私钥（留空则用它自己的默认/agent）
                  --wb-start / --wb-stop 启停容器。**默认经 SFTP 代理**，所以
                                         command="internal-sftp" 的受限 key 也能用；
                                         代理是服务端 systemd 用户单元 workbuddy-switch

                其它：
                  --port <SSH 端口>      默认 22
                  --user <用户名>        默认 user
                  --key <私钥路径>       默认 %s
                  --local-port <端口>    本机监听端口，默认 3080（填 0 = 只建 WB 那条转发，排错用）

                设置保存在 %s
                """.formatted(DEFAULT_WEB_URL, DEFAULT_TUNNEL_WS, DEFAULT_WB_WEB_URL, detectKeyPath(),
                settingsFile().getAbsolutePath()));
    }

    private DshClient() {
    }
}
