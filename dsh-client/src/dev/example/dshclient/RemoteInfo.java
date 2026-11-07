package dev.example.dshclient;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 经 Cloudflare 隧道（SSH over WebSocket）读服务器上两样「每次重启都可能变」的东西：
 *
 *   1. **当前全局 IPv6** —— 校园网给这台机器的 /64 前缀是随认证/重连重新分配的。
 *      存档实测：`2001:db8::2:…`（稳定 2.5 天）→ 重启后变成 `…:fdf4:…`。
 *      接口 ID 是 EUI-64（不变），只有第 4 段前缀会换，所以**客户端不能把地址写死**。
 *   2. **dsh web 最新 token** —— 进程级随机（`randomBytes`），`dsh web` 一重启就换。
 *
 * ## 为什么走 SFTP 而不是 exec
 *
 * 客户端在 `authorized_keys` 里那行常被收窄成 `command="internal-sftp"`（禁 shell）
 * —— 这时 exec 通道拿不到任何东西，而 SFTP 两种情况都能用（`sshd_config` 有
 * `Subsystem sftp`）。手机端「传输助手」的公网地址发现（`ServerAddressDiscovery`）
 * 就是这么做的，这条路已经真机验证过；本类是把同一做法搬到电脑端。
 *
 * ## 为什么不是 DDNS
 *
 * 2026-10-13 那次排查试过：`~/.cloudflared/cert.pem` 是 ARGO TUNNEL TOKEN，
 * 不能当 Cloudflare API Token 用（HTTP 400 / errors 6003），要走 CF API 更新 AAAA
 * 得另外落一个能改 DNS 的密钥。而「先经隧道连上、读一次 /proc/net/if_inet6」不需要
 * 任何新凭证 —— 隧道本来就是任何网络都能通的那条路。
 */
public final class RemoteInfo {

    /** 服务器上 dsh-web 的启动日志（systemd 用户单元 append 写，权限 600）。 */
    private static final String WEB_LOG_REL = ".dsh/web.log";
    /** 内核暴露的接口地址表：0444 全局可读，收窄到只能 SFTP 的 key 也能读。 */
    static final String PROC_INET6 = "/proc/net/if_inet6";

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** 明显不是"服务器自己的公网地址"的虚拟接口前缀，避免发现到 docker 网桥上。 */
    private static final String[] VIRTUAL_IF_PREFIXES = {"docker", "br-", "veth", "virbr", "tun", "tap", "wg"};

    /** 一次读取的结果；两个字段都可能为 null（没读到，调用方回落到内置默认值）。 */
    public static final class Fetched {
        public String ipv6;
        public String token;
        /** 人话说明（打印用，不含 token 本身）。 */
        public String note = "";
    }

    /**
     * 建一条隧道会话，读上面两样，然后立刻断开。
     *
     * @param host     隧道入口主机名（只作 JSch 记账，真正连的是 wsUrl）
     * @param wsUrl    非空 = 走 Cloudflare WebSocket；空 = 直接 TCP 连 host:port（自测用）
     * @throws Exception 连不上/认证失败等；调用方应捕获并回落
     */
    public static Fetched fetch(String host, int port, String user, String keyPath,
                                String wsUrl, int timeoutMs) throws Exception {
        File k = new File(keyPath == null ? "" : keyPath);
        if (!k.isFile()) throw new IllegalArgumentException("私钥文件不存在：" + keyPath);

        Fetched out = new Fetched();
        JSch jsch = new JSch();
        jsch.addIdentity(k.getAbsolutePath());
        Session s = jsch.getSession(user, host, port);
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "publickey");
        s.setTimeout(timeoutMs);

        WsSshSocketFactory ws = null;
        if (wsUrl != null && !wsUrl.isEmpty()) {
            ws = new WsSshSocketFactory(wsUrl, timeoutMs);
            s.setSocketFactory(ws);
        }
        ChannelSftp sftp = null;
        try {
            s.connect(timeoutMs);
            sftp = (ChannelSftp) s.openChannel("sftp");
            sftp.connect(timeoutMs);

            try {
                out.ipv6 = firstGlobalIpv6(readText(sftp, PROC_INET6));
            } catch (Exception e) {
                out.note += "（地址：读 " + PROC_INET6 + " 失败：" + oneLine(e) + "）";
            }
            try {
                String home = sftp.realpath(".");
                out.token = lastToken(readText(sftp, home + "/" + WEB_LOG_REL));
            } catch (Exception e) {
                out.note += "（token：读 dsh-web 日志失败：" + oneLine(e) + "）";
            }
            return out;
        } catch (Exception e) {
            String diag = ws == null ? "" : "｜WS 诊断：" + ws.diagnostics();
            throw new Exception("经隧道读服务器信息失败：" + oneLine(e) + diag, e);
        } finally {
            if (sftp != null) {
                try {
                    sftp.disconnect();
                } catch (Exception ignore) {
                    // 会话都没了，忽略
                }
            }
            try {
                s.disconnect();
            } catch (Exception ignore) {
                // 同上
            }
        }
    }

    private static String readText(ChannelSftp sftp, String path) throws Exception {
        try (InputStream in = sftp.get(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 从 `/proc/net/if_inet6` 里挑一个**全局** IPv6。
     *
     * 每行格式：32 位十六进制地址 · ifindex · prefixlen · scope · flags · 网卡名。
     * `scope`：`00` = global，`10` = loopback，`20` = link-local，`40` = site-local。
     * 只认 `00`，并跳过 lo 与常见的虚拟接口（docker/br-/veth/…）。
     */
    static String firstGlobalIpv6(String procText) {
        if (procText == null || procText.isEmpty()) return null;
        Set<String> found = new LinkedHashSet<>();
        for (String line : procText.split("\n")) {
            String[] f = WHITESPACE.split(line.trim());
            if (f.length < 6 || f[0].length() != 32) continue;
            if (!"00".equals(f[3])) continue;
            String ifName = f[5];
            if ("lo".equals(ifName) || isVirtual(ifName)) continue;
            try {
                byte[] b = new byte[16];
                for (int i = 0; i < 16; i++) {
                    b[i] = (byte) Integer.parseInt(f[0].substring(i * 2, i * 2 + 2), 16);
                }
                found.add(InetAddress.getByAddress(b).getHostAddress());
            } catch (Exception ignore) {
                // 这一行不合法，跳过
            }
        }
        return found.isEmpty() ? null : found.iterator().next();
    }

    private static boolean isVirtual(String ifName) {
        for (String p : VIRTUAL_IF_PREFIXES) {
            if (ifName.startsWith(p)) return true;
        }
        return false;
    }

    /** 取日志里**最后一处** `token=…` —— 每次启动 append 一行，最后一行才是当前生效的。 */
    static String lastToken(String logText) {
        if (logText == null) return null;
        Matcher m = TOKEN.matcher(logText);
        String last = null;
        while (m.find()) last = m.group(1);
        return last;
    }

    private static String oneLine(Exception e) {
        String s = e.getMessage();
        if (s == null || s.isEmpty()) s = e.toString();
        return s.replace('\n', ' ');
    }

    private RemoteInfo() {
    }
}
