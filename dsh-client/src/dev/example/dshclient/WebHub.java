package dev.example.dshclient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * 把「DSH 界面」和「WorkBuddy 桌面」塞进同一个浏览器窗口的本地中转页。
 *
 * <p>为什么要它：客户端原先要开两个 Edge `--app=` 窗口（DSH 一个、WorkBuddy 一个），
 * 任务栏就多出两个图标。这里用一个本地页 + 两个 iframe + 顶部标签栏，只开**一个**窗口。
 *
 * <p>可行性依据（实测）：DSH 的响应没有 `X-Frame-Options`，也没有 CSP `frame-ancestors`；
 * noVNC（websockify）同样没有。两个页面都只监听 loopback，iframe 不会外泄。
 * 另外 DSH 的会话 cookie 是 `SameSite=Strict`，但 127.0.0.1 的不同端口算**同一站点**
 * （SameSite 不看端口），所以 iframe 里带 token 首次加载后能正常保持登录。
 *
 * <p>页面本身不存任何状态：两个地址通过查询参数传进来，服务端只做静态壳子，
 * 所以 token / VNC 密码换了一轮也不会出现"页面还在用旧地址"的问题。
 */
final class WebHub {

    private static final int PREFERRED_PORT = 3130;

    private HttpServer server;
    private int port;

    /** 起服务（幂等）。端口被占就换一个随机端口。 */
    synchronized String start() throws IOException {
        if (server != null) {
            return baseUrl();
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PREFERRED_PORT), 0);
        } catch (IOException e) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        port = server.getAddress().getPort();
        server.createContext("/", this::handleIndex);
        server.setExecutor(null);
        server.start();
        return baseUrl();
    }

    synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    String baseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    /**
     * @param dsh DSH 界面地址（已带 token）
     * @param wb  WorkBuddy noVNC 地址（已带自动连接/缩放参数）
     * @param dark 当前是否深色主题，决定中转页自己的底色
     */
    String urlFor(String dsh, String wb, boolean dark) {
        return baseUrl() + "?dsh=" + enc(dsh) + "&wb=" + enc(wb) + "&theme=" + (dark ? "dark" : "light");
    }

    private void handleIndex(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getRawQuery();
        String dsh = param(query, "dsh");
        String wb = param(query, "wb");
        String theme = param(query, "theme");
        byte[] body = page(dsh, wb, "light".equals(theme)).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** 极简页面：一个标签栏 + 两个 iframe。不引任何外部资源（离线也能开）。 */
    private static String page(String dsh, String wb, boolean dark) {
        String bg = dark ? "#141414" : "#FBFCFD";
        String bar = dark ? "#1E1E1E" : "#FFFFFF";
        String border = dark ? "#383838" : "#E1E4E8";
        String fg = dark ? "#F2F2F2" : "#202124";
        String muted = dark ? "#BDBDBD" : "#5C6370";
        String accent = dark ? "#2C68AA" : "#1F5FAF";
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<title>DSH 客户端</title>"
                + "<style>"
                + "*{box-sizing:border-box}"
                + "html,body{margin:0;height:100%;background:" + bg + ";color:" + fg + ";"
                + "font-family:'Segoe UI','Microsoft YaHei UI','Microsoft YaHei',system-ui,sans-serif}"
                + "#bar{height:40px;display:flex;align-items:center;gap:4px;padding:0 10px;"
                + "background:" + bar + ";border-bottom:1px solid " + border + "}"
                + ".tab{appearance:none;border:0;background:transparent;color:" + muted + ";"
                + "font:inherit;font-size:13px;padding:8px 14px;border-radius:8px;cursor:pointer}"
                + ".tab:hover{background:rgba(128,128,128,.14)}"
                + ".tab.on{color:" + fg + ";background:rgba(128,128,128,.18);font-weight:600}"
                + ".dot{width:8px;height:8px;border-radius:50%;background:" + accent + ";margin-left:auto;margin-right:8px}"
                + ".hint{color:" + muted + ";font-size:12px;margin-right:10px}"
                + "iframe{position:absolute;top:41px;left:0;width:100%;height:calc(100% - 41px);border:0;background:" + bg + "}"
                + "iframe.hide{display:none}"
                + "</style></head><body>"
                + "<div id=\"bar\">"
                + "<button class=\"tab on\" data-i=\"0\">DSH 界面</button>"
                + "<button class=\"tab\" data-i=\"1\">WorkBuddy 桌面</button>"
                + "<span class=\"hint\">按 Ctrl+Tab 也可以切换</span><span class=\"dot\"></span>"
                + "</div>"
                + "<iframe id=\"f0\" src=\"" + esc(dsh) + "\" allow=\"clipboard-read; clipboard-write\"></iframe>"
                + "<iframe id=\"f1\" class=\"hide\" src=\"" + esc(wb) + "\" allow=\"clipboard-read; clipboard-write\"></iframe>"
                + "<script>"
                + "var fs=[document.getElementById('f0'),document.getElementById('f1')];"
                + "var ts=[].slice.call(document.querySelectorAll('.tab'));var cur=0;"
                + "function sel(i){cur=i;fs.forEach(function(f,k){f.classList.toggle('hide',k!==i)});"
                + "ts.forEach(function(t,k){t.classList.toggle('on',k===i)})}"
                + "ts.forEach(function(t){t.addEventListener('click',function(){sel(+t.dataset.i)})});"
                + "document.addEventListener('keydown',function(e){"
                + "if(e.ctrlKey&&e.key==='Tab'){e.preventDefault();sel((cur+1)%2)}});"
                + "</script></body></html>";
    }

    private static String param(String query, String key) {
        if (query == null) {
            return "";
        }
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 只用于往 HTML 属性里放地址：转义引号与尖括号。 */
    private static String esc(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
