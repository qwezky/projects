package dev.example.dshclient;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 「走哪条路」的探测与判读 —— 和手机 App / PowerShell 脚本同一套判据。
 *
 * 三条路：
 *   DIRECT  直连 IPv6 + SSH 本地端口转发（最快；校园网实测 0% 丢包、RTT 30ms、cwnd 38）
 *   TUNNEL  SSH 装进 WebSocket 走 Cloudflare（SSH 端到端加密；任何网络都能用）
 *   WEB     浏览器直接开 https://dsh.example.com/（不经 SSH，最快；代价是 CF 终止 TLS）
 *
 * **判据用丢包，不用"连不连得上"**：坏路上直连照样能建立 SSH 会话，只是慢
 * （广电移动数据实测 10–35% 丢包 / RTT 67–371ms / cwnd 1–3），
 * 所以按成败或时长探测会把最差的那条路当成"能用"而选中。
 *
 * Java 里没有免提权的 ICMP（要 raw socket），所以用**多次 TCP 握手**当丢包代理：
 *   - 有一次连不上         → 判为坏路
 *   - 全连上但最好一次也慢 → 判为坏路（阈值默认 300ms）
 * 实测这个代理够用：校园网 10 次全中、最好 8ms；广电那台电脑 10 次里明显有超时。
 *
 * ## 1.9.0：判读不好时退 **TUNNEL**，不再退 WEB
 *
 * 老口径是"直连不好 → WEB（浏览器直接开 CF）"，代价是 **WEB 路线整条进程没有 SSH 通道**：
 * WorkBuddy 桌面（6080 转发）和容器启停（SFTP 代理）全都做不了 —— 实测就是这样：
 * 机器没有 IPv6 时判成 WEB，点「打开 WorkBuddy 桌面」只能去开注定连不上的直连地址。
 * 现在改成：直连判读不过关 → **TUNNEL**（SSH over WebSocket，一样不依赖 IPv6，
 * 但两个转发都在），WEB 只保留给显式 `--route web`（临时排错、或本机没有可用私钥时）。
 */
public final class RouteProbe {

    public enum Route {
        DIRECT, TUNNEL, WEB;

        public static Route of(String s) {
            if (s == null) return null;
            switch (s.trim().toLowerCase()) {
                case "direct": return DIRECT;
                case "tunnel": return TUNNEL;
                case "web": return WEB;
                default: return null;   // auto / 未知 → 交给探测
            }
        }
    }

    public static final class Result {
        public final Route route;
        public final int attempts;
        public final int failures;
        public final long bestMs;
        public final String detail;

        Result(Route route, int attempts, int failures, long bestMs, String detail) {
            this.route = route;
            this.attempts = attempts;
            this.failures = failures;
            this.bestMs = bestMs;
            this.detail = detail;
        }

        public String summary() {
            return "丢包 " + (attempts == 0 ? "n/a" : (failures * 100 / attempts) + "%")
                    + "（" + failures + "/" + attempts + " 次 TCP 握手失败）"
                    + " / 最好一次 " + (bestMs < 0 ? "n/a" : bestMs + "ms")
                    + " → 选 " + route;
        }
    }

    public static Result probe(String host, int port, int attempts, int lossLimitPct,
                               int handshakeLimitMs, int connectTimeoutMs) {
        int failures = 0;
        int done = 0;
        long best = -1;
        for (int i = 0; i < attempts; i++) {
            long t0 = System.nanoTime();
            done++;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (best < 0 || ms < best) best = ms;
            } catch (Exception e) {
                failures++;
                // 1.9.0 早停：已经错到 3 次（≥30%，远大于 5% 的阈值）时，剩下几次改不了判读，
                // 只会让"本机根本没有 IPv6"的机器每次启动白等 3 秒 × 7。
                if (failures >= 3) break;
            }
        }
        int lossPct = done == 0 ? 0 : failures * 100 / done;
        Route route;
        // 判读不过关 → TUNNEL（有 SSH 通道：WorkBuddy 与容器启停都能用），不再退 WEB。
        if (failures > 0 && lossPct > lossLimitPct) route = Route.TUNNEL;
        else if (best > handshakeLimitMs) route = Route.TUNNEL;
        else route = Route.DIRECT;
        return new Result(route, done, failures, best, "");
    }

    /**
     * 本机有没有**可以用来连别人的全局 IPv6 地址** —— 没有的话直连那条路根本不存在，
     * 连探测都省了（探测在这台机器上只会白白等超时）。
     *
     * 只认"网卡 UP 且不是回环/链路本地/组播"的 IPv6，并跳过常见虚拟接口
     * （docker/veth/br-/virbr/tun/tap/wg/vEthernet）。枚举失败时**按"有"处理** ——
     * 宁可多探一次，也不要因为读不到网卡信息就把人钉死在隧道上。
     */
    public static boolean hasGlobalIpv6() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (n.startsWith("docker") || n.startsWith("veth") || n.startsWith("br-")
                        || n.startsWith("virbr") || n.startsWith("tun") || n.startsWith("tap")
                        || n.startsWith("wg") || n.startsWith("vethernet")) continue;
                for (java.net.InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    java.net.InetAddress a = ia.getAddress();
                    if (a instanceof java.net.Inet6Address
                            && !a.isLoopbackAddress() && !a.isLinkLocalAddress()
                            && !a.isMulticastAddress() && !a.isSiteLocalAddress()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignore) {
            return true;
        }
        return false;
    }

    private RouteProbe() {
    }
}
