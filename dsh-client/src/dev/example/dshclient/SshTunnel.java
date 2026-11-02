package dev.example.dshclient;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 一条 SSH 会话 + 本地端口转发（可带第二个转发）：`127.0.0.1:<本地端口>` → （服务器侧）`127.0.0.1:<远端端口>`。
 *
 * 这就是"电脑版 DSH 客户端"的传输层：把服务器上只监听 loopback 的东西，
 * 通过 **IPv6 + 22 端口 + 同一把 key**（和手机 App 的文件传输完全同一条通道）搬到本机来。
 *
 * 1.4.0 起**一条会话带两个转发**：
 *   - 主转发：3080 → DSH 界面（沿用旧行为）
 *   - 副转发：6080 → WorkBuddy 桌面（容器里的 noVNC）。副转发**建不起来不算致命**：
 *     服务器的 authorized_keys 很可能写着 `permitopen="127.0.0.1:3080"`，那样 6080 会被拒；
 *     这时客户端会记下原因，改用「直连 IPv6 的 6080」那条路（见 DshClient.wbUrl）。
 *
 * 相比让服务器直接监听 IPv6 或用 Cloudflare 隧道的好处（主转发这条）：
 *  - 全程 SSH 加密：DSH 的会话 cookie 不会在链路上明文裸奔
 *  - 浏览器看到的是 loopback 来源 ⇒ DSH 的信任围栏天然放行，不用加 trustedHosts
 *  - 页面与页面内下载同源、同为 http ⇒ 没有"HTTPS 页面下载 http 资源"的混合内容拦截
 *
 * 1.9.0 起 `localPort &lt;= 0` 表示**不建主转发**：网页隧道（WEB 路线）下点「打开 WorkBuddy 桌面」时，
 * 会临时经 CF 单开一条"只做 6080"的会话 —— 修掉"WEB 路线没有 SSH 通道、WB 只能去开直连地址"那个坑。
 */
public final class SshTunnel implements Closeable {

    private final String host;
    private final int port;
    private final String user;
    private final String keyPath;
    /** 主转发（DSH 界面）；<b>&lt;= 0 表示不建</b>（1.9.0：只做 WorkBuddy 那一条转发时用）。 */
    private final int localPort;
    private final int remotePort;
    /** 副转发（WorkBuddy 桌面 noVNC）；wbLocalPort <= 0 表示不建。 */
    private final int wbLocalPort;
    private final int wbRemotePort;
    /** 非空 = 走 Cloudflare 的 WebSocket 隧道（SSH over WS），此时 host/port 只作 JSch 记账用。 */
    private final String wsUrl;

    private Session session;
    private int boundPort = -1;
    private int boundWbPort = -1;
    private String selfCheck = "";
    private String wbSelfCheck = "";
    /** 副转发失败的原因（非空 = 没建起来，调用方据此回落到直连）。 */
    private String wbForwardError = "";
    /** 这条会话最近一次实测吞吐（KB/s；-1 = 没测过/测不出来）。1.12.0 */
    private double speedKbps = -1;

    public SshTunnel(String host, int port, String user, String keyPath, int localPort, int remotePort) {
        this(host, port, user, keyPath, localPort, remotePort, 0, 0, null);
    }

    public SshTunnel(String host, int port, String user, String keyPath, int localPort, int remotePort,
                     String wsUrl) {
        this(host, port, user, keyPath, localPort, remotePort, 0, 0, wsUrl);
    }

    /** 1.4.0：一条会话带两个转发（主 = DSH，副 = WorkBuddy）。 */
    public SshTunnel(String host, int port, String user, String keyPath,
                     int localPort, int remotePort, int wbLocalPort, int wbRemotePort, String wsUrl) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.keyPath = keyPath;
        this.localPort = localPort;
        this.remotePort = remotePort;
        this.wbLocalPort = wbLocalPort;
        this.wbRemotePort = wbRemotePort;
        this.wsUrl = wsUrl;
    }

    /** 建会话 + 建转发（阻塞，调用方负责放到后台线程）。 */
    public synchronized void start() throws Exception {
        close();
        File k = new File(keyPath == null ? "" : keyPath);
        if (!k.isFile()) {
            throw new IllegalArgumentException("私钥文件不存在：" + keyPath
                    + "\n（用界面上的「选择私钥…」指定；还没有的话先在 PowerShell 里 ssh-keygen -t ed25519，"
                    + "再把 .pub 追加到服务器 ~/.ssh/authorized_keys）");
        }
        JSch jsch = new JSch();
        jsch.addIdentity(k.getAbsolutePath());
        Session s = jsch.getSession(user, host, port);
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "publickey");
        s.setServerAliveInterval(20_000);
        s.setServerAliveCountMax(3);
        // 走隧道（SSH over WebSocket）：不建 TCP，把 SSH 的字节流塞进一条 WebSocket。
        // 用于"直连那条路很烂"时绕开（实测广电移动数据直连 10–35% 丢包）。
        if (wsUrl != null && !wsUrl.isEmpty()) {
            s.setSocketFactory(new WsSshSocketFactory(wsUrl, 15_000));
        }
        try {
            s.connect(15_000);
        } catch (Exception e) {
            throw new Exception("连接/认证失败：" + e.getMessage() + "\n  用的私钥：" + keyPath, e);
        }
        // 1.9.0：localPort <= 0 = 不建主转发（网页隧道下临时给 WorkBuddy 单开一条会话时用）
        if (localPort > 0) {
            try {
                boundPort = s.setPortForwardingL(localPort, "127.0.0.1", remotePort);
            } catch (Exception e) {
                try {
                    s.disconnect();
                } catch (Exception ignore) {
                    // 已经断了就算了
                }
                throw new Exception("本地端口 " + localPort + " 建立转发失败：" + e.getMessage()
                        + "\n（多半是该端口已被占用：上一次的客户端还在跑，或者换一个本地端口）", e);
            }
        }
        // 副转发：WorkBuddy 桌面。**失败不致命** —— 服务器那把 key 可能只 permitopen 了 3080，
        // 这时 sshd 会拒绝 6080；客户端会记下原因并改用直连 IPv6 的 6080。
        if (wbLocalPort > 0) {
            try {
                boundWbPort = s.setPortForwardingL(wbLocalPort, "127.0.0.1", wbRemotePort);
            } catch (Exception e) {
                boundWbPort = -1;
                wbForwardError = "WorkBuddy 的 6080 转发没建起来：" + e.getMessage()
                        + "\n  最可能的原因：服务器 authorized_keys 那把 key 只 permitopen 了 3080。"
                        + "\n  改法（把 6080 加进去，其它选项不动）："
                        + "\n    permitopen=\"127.0.0.1:3080\",permitopen=\"127.0.0.1:6080\""
                        + "\n  （改完不用重启 sshd；客户端这次会先用「直连 IPv6 的 6080」打开）";
            }
        }
        this.session = s;
        // 关键：转发"建立成功"不等于"能用"。authorized_keys 里的 `restrict` 隐含
        // `no-port-forwarding`，这时本地端口照样会绑上，但每次连接都被服务器拒绝、
        // 拿不到任何数据 —— 看起来像客户端挂了，其实是 key 的权限问题。
        if (localPort > 0) {
            try {
                verifyForward();
            } catch (Exception e) {
                close();
                throw e;
            }
        }
        if (boundWbPort > 0) {
            try {
                wbSelfCheck = verifyHttp(boundWbPort, "/vnc.html",
                        "本地端口 " + boundWbPort + " 能连上，但服务器没回数据 —— 多半是 key 的 permitopen 没放 6080。");
            } catch (Exception e) {
                wbSelfCheck = "自检失败：" + e.getMessage();
                // 重要：permitopen 被拒时，本地端口照样会绑上，只有真正连过去才被服务器关掉。
                // 所以自检失败也要记进 wbForwardError，GUI 才会回落到直连而不是打开一个坏地址。
                if (wbForwardError.isEmpty()) {
                    wbForwardError = "WorkBuddy 的 6080 转发连不通（自检失败）。最可能的原因："
                            + "\n  服务器 authorized_keys 那把 key 只 permitopen 了 3080。改法："
                            + "\n    permitopen=\"127.0.0.1:3080\",permitopen=\"127.0.0.1:6080\"";
                }
            }
        }
    }

    /**
     * 自检：从本地端口发一个最小 HTTP 请求，能读到响应行就算转发真的通了。
     * 失败时给出"怎么办"，而不是让人对着一个连不上的端口发呆。
     */
    public String verifyForward() throws Exception {
        int p = boundPort > 0 ? boundPort : localPort;
        if (p <= 0) throw new IllegalStateException("这条会话没有建主转发（DSH 界面）");
        String why = "本地端口 " + p + " 能连上，但服务器没有回数据 —— 服务器上这把 key 不允许端口转发。"
                + "\n  authorized_keys 里的 `restrict` 隐含 `no-port-forwarding`。两种改法："
                + "\n   ① 换一把允许转发的 key（比如你 VS Code 用的那把）；"
                + "\n   ② 或者专门加一行，允许转发但只许转发到 DSH 与 WorkBuddy，且不给 shell："
                + "\n      no-pty,no-agent-forwarding,no-X11-forwarding,no-user-rc,permitopen=\"127.0.0.1:3080\",permitopen=\"127.0.0.1:6080\",command=\"internal-sftp\" ssh-ed25519 AAAA… dsh-client";
        selfCheck = verifyHttp(p, "/", why);
        return selfCheck;
    }

    /** WorkBuddy 桌面（副转发）的自检：GET /vnc.html 应该拿到 200。 */
    public String verifyWorkbuddy() throws Exception {
        if (boundWbPort <= 0) {
            throw new IllegalStateException(wbForwardError.isEmpty()
                    ? "没有建 WorkBuddy 的转发（未启用）" : wbForwardError);
        }
        wbSelfCheck = verifyHttp(boundWbPort, "/vnc.html",
                "本地端口 " + boundWbPort + " 能连上，但服务器没回数据 —— 多半是 key 的 permitopen 没放 6080。");
        return wbSelfCheck;
    }

    /**
     * **实测这条会话的吞吐**（KB/s，1.12.0）。
     *
     * 为什么必须真量：丢包 0% + 握手 3ms 也可能"握手很快、传数据很慢"（手机热点就是典型），
     * 所以判据里除了丢包还要有速度。
     *
     * 为什么走 SFTP：它就是这条 SSH 会话的数据通道本身，而且受限 key
     * （`command="internal-sftp"`）也能用 —— 不需要 exec 权限。
     * 读的是服务器上现成的大文件（/bin/bash 之类），不在服务器上留任何东西。
     *
     * @param maxBytes 最多读多少（内存与流量的上限）
     * @param maxMs    最多花多久
     * @return KB/s；-1 = 没测出来（读不到文件/时间太短）
     */
    public synchronized double measureSpeedKbps(int maxBytes, long maxMs) {
        speedKbps = -1;
        if (session == null || !session.isConnected()) return -1;
        String[] cands = {"/bin/bash", "/usr/lib/x86_64-linux-gnu/libc.so.6", "/usr/bin/python3"};
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
            // 1.19.0：**流水线发多个请求**（照搬"文件传输助手"的 setBulkRequests=64）——
            // 不设它的话每次 32KB 都要等一个 RTT，200ms 链路上天花板只有 ~150 KB/s。
            try {
                sftp.setBulkRequests(64);
            } catch (Exception ignore) {
                // 老版本 JSch 没这个 API 就算了
            }
            for (String path : cands) {
                try (InputStream in = sftp.get(path)) {
                    byte[] buf = new byte[32 * 1024];
                    long t0 = System.nanoTime();
                    long total = 0;
                    int n;
                    while (total < maxBytes && (n = in.read(buf)) > 0) {
                        total += n;
                        if ((System.nanoTime() - t0) / 1_000_000L >= maxMs) break;
                    }
                    long ms = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
                    if (total < 32 * 1024) continue;              // 读到的太少，换下一个候选
                    speedKbps = (total / 1024.0) / (ms / 1000.0);
                    return speedKbps;
                } catch (Exception ignore) {
                    // 这个文件读不到（权限/不存在），试下一个
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    /** 这条会话最近一次实测吞吐（KB/s，-1 = 没测出来）。 */
    public double lastSpeedKbps() {
        return speedKbps;
    }

    /** 通用 HTTP 自检：连本地转发端口，读第一行状态。 */
    private String verifyHttp(int p, String path, String why) throws Exception {
        return httpProbe("127.0.0.1", p, path, 5_000, 6_000, why);
    }

    /**
     * 通用 HTTP 自检（1.9.0 起也用于**直连**那条路）：连 host:port 发一条最小请求，
     * 能读到 `HTTP/` 状态行就算"真的通"，读到别的东西/连不上/没响应都算不通。
     *
     * @param host 裸地址（IPv6 不要带方括号 —— 那是 URL 的写法，不是 socket 的写法）
     * @return 状态行，例如 {@code HTTP/1.1 200 OK}
     * @throws IllegalStateException 不通时抛出，消息里带上 why
     */
    public static String httpProbe(String host, int port, String path,
                                   int connectMs, int readMs, String why) throws Exception {
        try (java.net.Socket sock = new java.net.Socket()) {
            try {
                sock.connect(new java.net.InetSocketAddress(host, port), connectMs);
            } catch (Exception e) {
                throw new IllegalStateException("连不上 " + host + ":" + port + "：" + e.getMessage(), e);
            }
            sock.setSoTimeout(readMs);
            java.io.OutputStream out = sock.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + host + ":" + port + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] buf = new byte[128];
            int n;
            try {
                n = sock.getInputStream().read(buf);
            } catch (java.net.SocketTimeoutException e) {
                throw new IllegalStateException(why + "\n（现象：等了 " + (readMs / 1000) + " 秒没有任何响应）", e);
            }
            if (n <= 0) {
                throw new IllegalStateException(why + "\n（现象：连接被立刻关闭、一个字节都没回）");
            }
            String first = new String(buf, 0, n, StandardCharsets.ISO_8859_1).split("\r\n")[0];
            if (!first.startsWith("HTTP/")) {
                throw new IllegalStateException("连上了什么，但那不是 HTTP 响应：" + first);
            }
            return first;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("自检失败：" + e, e);
        }
    }

    /**
     * 直连自检的"不抛异常"版本：**通 → null；不通 → 一句人话原因**。
     * 用于"打开 WorkBuddy 窗口之前先确认那个直连地址真的能用"，避免又开出一个连不上的空窗口。
     */
    public static String directProbeError(String host, int port, String path, int connectMs, int readMs) {
        try {
            httpProbe(host, port, path, connectMs, readMs, "直连自检失败");
            return null;
        } catch (Exception e) {
            String m = e.getMessage();
            return (m == null || m.isBlank()) ? e.toString() : m.replace('\n', ' ');
        }
    }

    /**
     * 在服务器上跑一条命令（best-effort）。
     *
     * 用途：打开 WorkBuddy 窗口前先 `docker start <容器>`，容器没在跑也能一键拉起来。
     * 如果这把 key 被 `command="internal-sftp"` 钉死（README 里推荐的那种写法），
     * exec 会被服务器拒绝 —— 这时返回错误文字，**不影响其它功能**。
     */
    public synchronized String exec(String command) {
        if (session == null || !session.isConnected()) return "（SSH 会话没连上，跳过）";
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ch.setErrStream(err);
            InputStream in = ch.getInputStream();
            ch.connect(10_000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 20_000;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (ch.isClosed()) break;
                if (System.currentTimeMillis() > deadline) break;
                Thread.sleep(50);
            }
            String so = out.toString(StandardCharsets.UTF_8).trim();
            String se = err.toString(StandardCharsets.UTF_8).trim();
            return se.isEmpty() ? so : (so.isEmpty() ? se : so + " / " + se);
        } catch (Exception e) {
            return "（exec 失败：" + e.getMessage()
                    + " —— 这把 key 可能被 command=\"internal-sftp\" 钉住了，不影响其它功能）";
        } finally {
            if (ch != null) ch.disconnect();
        }
    }

    /** 最近一次转发自检的结果（例如 "HTTP/1.1 401 Unauthorized"）。 */
    public String lastSelfCheck() {
        return selfCheck;
    }

    /** WorkBuddy 桌面（副转发）的最近一次自检结果。 */
    public String lastWbSelfCheck() {
        return wbSelfCheck;
    }

    /** 副转发失败的原因（空 = 建起来了或没启用）。 */
    public String wbForwardError() {
        return wbForwardError;
    }

    public synchronized boolean isAlive() {
        return session != null && session.isConnected();
    }

    /** 实际绑上的本地端口（用于本地端口填 0 让系统挑一个的场景）。 */
    public synchronized int boundPort() {
        return boundPort;
    }

    /** 副转发实际绑上的本地端口；-1 = 没建起来。 */
    public synchronized int boundWbPort() {
        return boundWbPort;
    }

    public int localPort() {
        return localPort;
    }

    public String url(String token) {
        if (boundPort <= 0 && localPort <= 0) return "";   // 这条会话没有主转发（只做 WorkBuddy）
        StringBuilder sb = new StringBuilder("http://127.0.0.1:").append(boundPort > 0 ? boundPort : localPort).append("/");
        if (token != null && !token.isEmpty()) {
            sb.append("?token=").append(token);
        }
        return sb.toString();
    }

    /** WorkBuddy 桌面经过 SSH 转发后的本机地址（副转发没起来时返回 null）。 */
    public String workbuddyUrl(String password) {
        if (boundWbPort <= 0) return null;
        return DshClient.wbLocalUrl(boundWbPort, password);
    }

    // ---------------- WorkBuddy 容器启停（1.5.0） ----------------

    /**
     * 经 SFTP 走服务端代理控制容器（读写 `~/.workbuddy-switch/{request,reply}`）。
     *
     * 为什么不用 exec：客户端那把 key 是 `command="internal-sftp"`，**不给 shell**，
     * `docker start/stop` 会被 sshd 拒掉；但同一把 key 能走 SFTP 读写家目录下的文件。
     * 服务端有个只认 start/stop/status 的代理（systemd 用户单元 `workbuddy-switch`）在轮询。
     * 协议：request 第 1 行命令、第 2 行序号；reply 第 1 行序号、第 2 行命令、第 3 行起结果。
     */
    public synchronized String wbSwitch(String cmd, long timeoutMs) {
        if (session == null || !session.isConnected()) return "（SSH 会话没连上）";
        String seq = String.valueOf(System.currentTimeMillis());
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(10_000);
            byte[] req = (cmd + "\n" + seq + "\n").getBytes(StandardCharsets.UTF_8);
            sftp.put(new java.io.ByteArrayInputStream(req), ".workbuddy-switch/request");
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(400);
                try (java.io.InputStream in = sftp.get(".workbuddy-switch/reply")) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String[] lines = text.split("\n", -1);
                    if (lines.length >= 3 && lines[0].trim().equals(seq)) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 2; i < lines.length; i++) {
                            if (!lines[i].isBlank()) sb.append(lines[i].trim()).append(' ');
                        }
                        return sb.toString().trim();
                    }
                } catch (Exception ignore) {
                    // 回复还没写出来，继续等
                }
            }
            return "（超时：代理没在 " + (timeoutMs / 1000) + " 秒内回复；stop/start 较慢时属正常，可再点『刷新状态』）";
        } catch (Exception e) {
            return "（SFTP 请求失败：" + e.getMessage() + "）";
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    /**
     * 用**系统自带的 ssh**（Windows 的 `ssh.exe` / Linux 的 `ssh`）执行一条远端命令。
     *
     * 什么时候需要它：想让客户端用 **Windows 自己那把 key / ssh-agent**（可能带口令、或只在
     * 代理里），而不把私钥交给 JSch —— 只要那把 key **允许 exec** 就行。
     *
     * ⚠️ 注意：**换客户端不改变服务端的权限**。`authorized_keys` 里的 `command="internal-sftp"`
     * 是 sshd 施加的，用 ssh.exe 发 `docker start` 一样会被忽略并转成 internal-sftp ——
     * 所以这条路的前提是"换一把不带 `command=` 限制的 key"，而不是"换成 ssh.exe"。
     */
    public static String systemSsh(String sshExe, String key, String user, String host, int port,
                                   String cmd, long timeoutMs) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(sshExe);
        if (key != null && !key.isBlank()) {
            args.add("-i");
            args.add(key);
        }
        args.add("-p");
        args.add(String.valueOf(port));
        args.add("-o");
        args.add("BatchMode=yes");
        args.add("-o");
        args.add("StrictHostKeyChecking=no");
        args.add("-o");
        args.add("ConnectTimeout=10");
        args.add(user + "@" + host);
        args.add(cmd);
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return "（系统 ssh 超时：" + timeoutMs + "ms）";
            }
            int code = p.exitValue();
            return out.isEmpty() ? ("（系统 ssh 无输出，exit " + code + "）") : out;
        } catch (Exception e) {
            if (p != null) p.destroyForcibly();
            return "（系统 ssh 调用失败：" + e.getMessage() + "）";
        }
    }

    /**
     * 容器启停统一入口。顺序：
     *   ① 配了 `--wb-ssh <ssh路径>` → 用**系统 ssh** 执行（可用 Windows 自己的 key/agent；要求 key 允许 exec）
     *   ② **SFTP 代理**（服务端只认 start/stop/status 的小代理）—— 受限 key 也能用，**默认走这条**
     *   ③ JSch 的 exec（只有 key 允许 shell 时才可能成功）
     */
    public String workbuddySwitch(String cmd, String container, String sshExe, String sshKey,
                                  String host, int port, String user) {
        String dockerCmd = switch (cmd) {
            case "start" -> "docker start " + container;
            case "stop" -> "docker stop -t 5 " + container;
            default -> "docker inspect -f '{{.State.Running}}' " + container;
        };
        StringBuilder trace = new StringBuilder();
        if (sshExe != null && !sshExe.isBlank()) {
            String viaSystemSsh = systemSsh(sshExe, sshKey, user, host, port, dockerCmd,
                    "status".equals(cmd) ? 20_000 : 60_000);
            if (looksLikeDockerAnswer(viaSystemSsh)) return viaSystemSsh;
            trace.append("系统 ssh：").append(viaSystemSsh).append(" / ");
        }
        String viaAgent = wbSwitch(cmd, "status".equals(cmd) ? 15_000 : 40_000);
        if (looksLikeDockerAnswer(viaAgent)) return viaAgent;
        trace.append("SFTP 代理：").append(viaAgent).append(" / ");
        String viaExec = exec(dockerCmd);
        if (looksLikeDockerAnswer(viaExec)) return viaExec;
        trace.append("JSch exec：").append(viaExec).append(" / ");
        return trace + "（三条路都不通：key 既不允许 exec，服务端 SFTP 代理也没起来？"
                + " 检查 systemctl --user status workbuddy-switch）";
    }

    /** docker 的应答长什么样：`true`/`false`（inspect）或容器名（start/stop）。 */
    private static boolean looksLikeDockerAnswer(String s) {
        if (s == null) return false;
        String t = s.trim();
        return t.equals("true") || t.equals("false") || t.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*");
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            if (localPort > 0) {
                try {
                    session.delPortForwardingL(localPort);
                } catch (Exception ignore) {
                    // 会话已经没了的话这里必然失败，忽略
                }
            }
            if (wbLocalPort > 0) {
                try {
                    session.delPortForwardingL(wbLocalPort);
                } catch (Exception ignore) {
                    // 同上
                }
            }
            try {
                session.disconnect();
            } catch (Exception ignore) {
                // 同上
            }
            session = null;
        }
        boundPort = -1;
        boundWbPort = -1;
    }
}
