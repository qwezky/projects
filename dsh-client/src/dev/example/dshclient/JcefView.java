package dev.example.dshclient;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.IProgressHandler;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandler;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.File;
import java.util.function.Consumer;

/**
 * 把网页**内嵌**到客户端窗口里（JCEF：Chromium 132）。
 *
 * <p>和"再开一个 Edge 窗口"相比，内嵌的好处是：任务栏只有一个图标、没有第二个进程窗口、
 * 切标签就是切页面。代价是首次启动 JCEF 要从 Maven 下载当前平台的 CEF 原生包（Windows x64 ≈138MB）
 * 并解包到 {@code %LOCALAPPDATA%\DshClient\cef-bundle}，之后启动就不再下载。
 *
 * <p>线程约定：{@link CefApp} 的创建很重（下载 + 解包 + 起进程），必须在后台线程做；
 * 浏览器用**窗口模式**（{@code windowless_rendering_enabled=false}），
 * 这样 {@link CefBrowser#getUIComponent()} 返回一个可以直接塞进 {@link JPanel} 的 AWT 组件。
 *
 * <p>失败不要紧：把异常抛给调用方，界面退回"浏览器窗口"那条路。
 */
final class JcefView extends JPanel {

    /** CefApp 是进程级单例，多个页面共用。 */
    private static CefApp cefApp;
    private static String initError = "";
    /** 初始化进度回调（把"下载 42%"显示到页签里的占位文字上）。 */
    private static volatile Consumer<String> progressSink = s -> {
    };

    private final Consumer<String> log;
    private final JLabel status = new JLabel("内嵌浏览器：初始化中…（首次运行要下载 CEF 运行时，约 138MB）");

    private CefBrowser browser;
    /** 浏览器真正的 AWT 重量级组件（windowed 模式下是 Canvas）。
     *  它是**原生子窗口的宿主**：只隐藏外层 JPanel 不会隐藏它，必须直接 setVisible。 */
    private Component browserUi;
    /** 必须强引用：CefClient 被 GC 掉后原生回调会悬空，典型症状就是"点某个按钮必崩"。 */
    private CefClient client;
    private String pendingUrl;
    /** 页面里的链接交给系统默认浏览器打开（由 ClientFrame 注入）。 */
    private java.util.function.Consumer<String> onExternalLink;
    /** 本页签打开时的地址，用来判断"同源 = 页面自己的路由"。 */
    private String homeUrl;
    /** 当前是否处于"活动页签"。CEF 是异步初始化，创建完要按这个状态补一次。 */
    private boolean active = true;

    /** 内嵌失败时的兜底：把地址交给调用方用浏览器窗口打开。 */
    private final Consumer<String> fallback;

    JcefView(Consumer<String> log, Consumer<String> fallback) {
        super(new BorderLayout());
        this.log = log == null ? s -> {
        } : log;
        this.fallback = fallback == null ? s -> {
        } : fallback;
        status.setHorizontalAlignment(JLabel.CENTER);
        add(status, BorderLayout.CENTER);
        progressSink = s -> SwingUtilities.invokeLater(() -> status.setText(s));
    }

    /** 打开/切换地址；首次调用会触发 CEF 初始化（后台线程）。 */
    /** 注入"用默认浏览器打开"的动作；不设就退回当前页签内导航。 */
    void setOnExternalLink(java.util.function.Consumer<String> onExternalLink) {
        this.onExternalLink = onExternalLink;
    }

    void open(String url) {
        pendingUrl = url;
        if (homeUrl == null) {
            homeUrl = url;
        }
        if (browser != null) {
            browser.loadURL(url);
            return;
        }
        new Thread(this::initSafely, "dsh-jcef-init").start();
    }

    private void initSafely() {
        try {
            CefApp app = sharedApp();
            CefClient client = app.createClient();
            this.client = client;

            // 悬浮球/下载按钮经常走 window.open(url) 或生成一个弹窗再下载。
            // JCEF 默认会为弹窗新建 AWT 窗口，在 windowed 模式下这条路很容易把进程带崩。
            // 这里统一拦掉：同一页签内导航；需要下载的交给下面的 CefDownloadHandler。
            client.addLifeSpanHandler(new org.cef.handler.CefLifeSpanHandlerAdapter() {
                @Override
                public boolean onBeforePopup(CefBrowser browser, org.cef.browser.CefFrame frame,
                                             String targetUrl, String targetFrameName) {
                    log.accept("弹窗链接 → 默认浏览器：" + targetUrl);
                    handOff(targetUrl);
                    return true;   // true = 取消新建窗口
                }
            });

            // 页面里点普通链接（没有 target=_blank）本来会在**当前页签**里跳走，
            // 一跳 DSH 就回不去了。这里把"跟本页不同源"的跳转一律转给系统默认浏览器，
            // 当前页保持不动；同源的（DSH 自己的路由）照常放行。
            client.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
                @Override
                public boolean onBeforeBrowse(CefBrowser browser, org.cef.browser.CefFrame frame,
                                              org.cef.network.CefRequest request,
                                              boolean isRedirect, boolean isMainFrame) {
                    if (!isMainFrame || request == null) {
                        return false;
                    }
                    String url = request.getURL();
                    if (url == null || url.isEmpty() || url.startsWith("about:")
                            || url.startsWith("chrome") || url.startsWith("devtools")) {
                        return false;
                    }
                    if (sameOrigin(homeUrl, url)) {
                        return false;      // 同源：DSH 自己的路由
                    }
                    log.accept("外链 → 默认浏览器：" + url);
                    handOff(url);
                    return true;           // 取消本次导航
                }

                @Override
                public boolean onOpenURLFromTab(CefBrowser browser, org.cef.browser.CefFrame frame,
                                                String targetUrl, boolean userGesture) {
                    handOff(targetUrl);
                    return true;
                }
            });

            // 页面里的 alert/confirm/beforeunload 不弹原生框（避免模态卡死），一律按"确认"继续
            client.addJSDialogHandler(new org.cef.handler.CefJSDialogHandlerAdapter() {
                @Override
                public boolean onJSDialog(CefBrowser browser, String originUrl,
                                          org.cef.handler.CefJSDialogHandler.JSDialogType type,
                                          String messageText, String defaultPromptText,
                                          org.cef.callback.CefJSDialogCallback callback,
                                          org.cef.misc.BoolRef suppressMessage) {
                    log.accept("页面对话框（自动确认）：" + messageText);
                    if (callback != null) {
                        callback.Continue(true, defaultPromptText == null ? "" : defaultPromptText);
                    }
                    return true;
                }
            });
            // 关键：不装这个处理器的话，点击下载会走 CEF 默认的"原生另存为对话框"，
            // 在 JCEF 里没有父窗口/回调不完整，直接把进程带崩（用户实测）。
            // 这里改成：不弹框、直接落盘到固定目录，重名自动加 (1)(2)。
            client.addDownloadHandler(new CefDownloadHandler() {
                /** 每个下载的重试次数（网页隧道经 Cloudflare 时容易半路断，靠 Range 续传救）。 */
                private final java.util.Map<Integer, Integer> retries =
                        new java.util.concurrent.ConcurrentHashMap<>();

                @Override
                public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item,
                                                String suggestedName, CefBeforeDownloadCallback callback) {
                    File target = uniqueFile(downloadDir(), suggestedName);
                    callback.Continue(target.getAbsolutePath(), false);   // false = 不弹保存框
                    downloads.put(item.getId(), target);
                    retries.put(item.getId(), 0);
                    DownloadCenter.get().start(item.getId(), target.getName(), target);
                    log.accept("开始下载：" + target.getAbsolutePath());
                    return true;
                }

                @Override
                public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item,
                                              CefDownloadItemCallback callback) {
                    int id = item.getId();
                    File target = downloads.get(id);

                    if (item.isComplete()) {
                        finish(id, target);
                        downloads.remove(id);
                        retries.remove(id);
                        return;
                    }
                    if (item.isCanceled()) {
                        DownloadCenter.get().cancel(id);
                        log.accept("⚠️ 下载被取消：" + (target != null ? target.getName() : ""));
                        downloads.remove(id);
                        retries.remove(id);
                        return;
                    }
                    if (item.isInProgress()) {
                        long total = item.getTotalBytes();
                        long got = item.getReceivedBytes();
                        int pct = item.getPercentComplete();
                        // 有些构建把"字节已收满"报成 inProgress 且 percent 卡在 99/100，
                        // 不能只等 isComplete()，否则界面永远停在 99%。
                        if ((total > 0 && got >= total) || pct >= 100) {
                            // 字节收满或百分比到顶：交给 settle 确认文件真的落盘（含大小校验）
                            DownloadCenter.get().progress(id, Math.max(pct, 100));
                            finishLater(id, target, total);
                            return;
                        }
                        DownloadCenter.get().progress(id, pct);
                        return;
                    }

                    // 既没完成、也没取消、也不在传输中 → 中断（网页隧道下很常见：CF 半路掐断）
                    int used = retries.getOrDefault(id, 0);
                    if (used < 3) {
                        retries.put(id, used + 1);
                        log.accept("下载中断，第 " + (used + 1) + " 次续传：" + (target != null ? target.getName() : ""));
                        DownloadCenter.get().progress(id, item.getPercentComplete());
                        final int delayMs = 800 * (used + 1);
                        final CefDownloadItemCallback cb = callback;
                        new java.util.Timer("dl-resume", true).schedule(new java.util.TimerTask() {
                            @Override
                            public void run() {
                                try {
                                    cb.resume();     // CEF 会带 Range 头续传
                                } catch (Exception e) {
                                    log.accept("续传失败：" + e.getMessage());
                                }
                            }
                        }, delayMs);
                        return;
                    }

                    DownloadCenter.get().fail(id, "传输中断（已重试 3 次）");
                    log.accept("❌ 下载失败（中断 3 次）：" + (target != null ? target.getAbsolutePath() : ""));
                    downloads.remove(id);
                    retries.remove(id);
                }
            });
            CefBrowser br = client.createBrowser(pendingUrl, false, false);
            this.browser = br;
            this.client = client;
            Component ui = br.getUIComponent();
            this.browserUi = ui;
            SwingUtilities.invokeLater(() -> {
                removeAll();
                add(ui, BorderLayout.CENTER);
                revalidate();
                repaint();
                // 关键：初始化期间用户可能已经切到别的页签了，
                // 这时必须立刻按当前状态隐藏，否则原生窗口一显示就把键盘抢走。
                applyActive();
            });
            log.accept("内嵌浏览器已就绪（JCEF / Chromium）：" + pendingUrl);
        } catch (Throwable t) {
            Startup.error("内嵌浏览器初始化失败", t);
            if (t instanceof NoClassDefFoundError) {
                // 最常见：cef\ 下的依赖 jar 不全（gson / commons-compress / codec / io / lang3）
                initError = "缺少依赖类 " + String.valueOf(t.getMessage()).replace('/', '.')
                        + "（检查 cef\\ 目录是否齐全，见 README「内嵌浏览器」）";
            } else {
                initError = String.valueOf(t.getMessage());
            }
            log.accept("⚠️ 内嵌浏览器起不来：" + initError + "　→ 改用浏览器窗口打开");
            SwingUtilities.invokeLater(() -> status.setText(
                    "<html><div style='text-align:center'>内嵌浏览器没有起来：<br>" + esc(initError)
                            + "<br><br>已回退到浏览器窗口；想再试可以关掉「内嵌浏览器」再打开。</div></html>"));
            // 真回退：自己打不开就交给外部浏览器，别只写一句话
            if (pendingUrl != null) {
                fallback.accept(pendingUrl);
            }
        }
    }

    /** 交给系统默认浏览器；没注入回调时退回当前页签内导航（保证至少能打开）。 */
    private void handOff(String url) {
        if (url == null || url.isEmpty() || "about:blank".equals(url)) {
            return;
        }
        java.util.function.Consumer<String> cb = onExternalLink;
        if (cb != null) {
            SwingUtilities.invokeLater(() -> cb.accept(url));
            return;
        }
        SwingUtilities.invokeLater(() -> {
            if (browser != null) {
                browser.loadURL(url);
            }
        });
    }

    /** 同源判断（协议 + 主机 + 端口）；解析不了就当不同源。 */
    private static boolean sameOrigin(String a, String b) {
        try {
            java.net.URI x = java.net.URI.create(a);
            java.net.URI y = java.net.URI.create(b);
            if (x.getHost() == null || y.getHost() == null) {
                return false;
            }
            return x.getScheme().equalsIgnoreCase(y.getScheme())
                    && x.getHost().equalsIgnoreCase(y.getHost())
                    && effectivePort(x) == effectivePort(y);
        } catch (Exception e) {
            return false;
        }
    }

    private static int effectivePort(java.net.URI u) {
        if (u.getPort() > 0) {
            return u.getPort();
        }
        String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        if (s.equals("https")) {
            return 443;
        }
        if (s.equals("http")) {
            return 80;
        }
        return -1;
    }

    /**
     * 显示/隐藏时通知 CEF 本体。
     *
     * <p>windowed 模式下浏览器是原生子窗口：页签切走以后它可能**仍然抓着系统焦点**，
     * 于是在终端页打字会被它收走（用户实测"终端打的字跑到 DSH 里"）。
     * 这里在切走时把它隐藏并放弃焦点。
     */
    void setActive(boolean active) {
        this.active = active;
        applyActive();
    }

    /** 把 active 状态真正作用到 CEF 的原生窗口上。 */
    private void applyActive() {
        if (browser == null) {
            return;      // 还没初始化：创建完会再调一次
        }
        try {
            // 关键：windowed 模式下 getUIComponent() 是重量级 Canvas，
            // 原生 CEF 子窗口挂在它下面。隐藏外层 JPanel（轻量）不会隐藏这个原生窗口，
            // 所以它仍然可见、仍然能拿到键盘焦点 —— "终端打的字跑进 DSH 页面"。
            // 必须直接隐藏/显示 Canvas，让它下面的原生窗口跟着消失/出现。
            Component ui = browserUi;
            if (ui != null) {
                ui.setVisible(active);
                // 只隐藏还不够：CEF 的 Win32 子窗口可能仍持有系统焦点。
                // 禁用重量级 Canvas 会让 Windows 的 EnableWindow 生效，
                // 强制把焦点让回顶层窗口（重新启用时再交还）。
                ui.setEnabled(active);
            }
            browser.setWindowVisibility(active);
            browser.setFocus(active);
            if (active && ui != null) {
                ui.repaint();
            }
            if ("1".equals(System.getProperty("dsh.term.debug"))) {
                System.out.println("[jcef] active=" + active
                        + " uiVisible=" + (ui != null && ui.isVisible())
                        + " uiEnabled=" + (ui != null && ui.isEnabled())
                        + " focusOwner="
                        + java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
            }
        } catch (Exception ignore) {
            // 浏览器已释放时忽略
        }
    }

    /** 重新加载当前页。 */
    void reload() {
        if (browser != null) {
            browser.reload();
        }
    }

    private CefBrowser browser_ref() {
        return browser;
    }

    /** 标记完成（只在真正拿到文件后才置"已完成"，避免点"打开"扑空）。 */
    private void finish(int id, File target) {
        DownloadCenter.get().done(id);
        DownloadCenter.clearMarkOfWeb(target);
        log.accept("✅ 下载完成：" + (target != null ? target.getAbsolutePath() : "（路径未知）"));
    }

    /**
     * 字节收满但还没 isComplete() 时，等 CEF 把 .crdownload 改成正式文件名再置完成。
     * 最多等 10 秒；等到了就完成，等不到也完成（文件其实已经在盘上）。
     */
    private void finishLater(int id, File target, long expectedBytes) {
        final long deadline = System.currentTimeMillis() + 20_000;
        new java.util.Timer("dl-settle", true).schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                boolean exists = target != null && target.isFile();
                long len = exists ? target.length() : -1;
                // 关键：不能只看"文件在不在"——CEF 会先写 .crdownload 再改名，
                // 而改名后大小可能还没刷到磁盘。等大小对上（或显式未知）再置完成。
                boolean sizeOk = expectedBytes <= 0 || len >= expectedBytes;
                if (exists && sizeOk) {
                    finish(id, target);
                    downloads.remove(id);
                    return;
                }
                if (System.currentTimeMillis() > deadline) {
                    if (exists) {
                        finish(id, target);   // 已经落盘就够了，别再挂着"下载中"
                    } else {
                        DownloadCenter.get().fail(id, "文件未落盘");
                        log.accept("❌ 下载结束但文件不存在：" + (target == null ? "?" : target.getAbsolutePath()));
                    }
                    downloads.remove(id);
                    return;
                }
                cancel();
                new java.util.Timer("dl-settle", true).schedule(this, 300);
            }
        }, 300);
    }

    /** 正在进行的下载：id -> 目标文件（下载完成时用来报路径）。 */
    private static final java.util.Map<Integer, File> downloads =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Windows「下载」已知文件夹的 GUID：资源管理器里可以把它挪到别的盘，此时 %USERPROFILE%\Downloads 并不存在。 */
    private static final String DOWNLOADS_GUID = "{374DE290-123F-4565-9164-39C4925E467B}";
    /** 只打印一次，方便从 dsh-client-launch/stdout 里确认文件到底落在哪。 */
    private static String resolvedDownloadDir;

    /**
     * 下载目录：就是系统真实的「下载」文件夹本身（不再套 DSH 子目录）。
     * 先读注册表已知文件夹（用户把下载夹挪到 D 盘时也能找对），再退回 ~/Downloads、本地化名字，
     * 都不可用才用 ~/DSH。绝不静默写到一个用户想不到的地方。
     */
    static File downloadDir() {
        File home = new File(System.getProperty("user.home", "."));
        File dir = knownDownloadsDir();
        if (dir == null) {
            dir = new File(home, "DSH");
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            dir = new File(home, "DSH");
            dir.mkdirs();
        }
        String abs = dir.getAbsolutePath();
        if (!abs.equals(resolvedDownloadDir)) {
            resolvedDownloadDir = abs;
            System.out.println("[dl] 下载目录：" + abs);
        }
        return dir;
    }

    /** 注册表里真正的「下载」文件夹；读不到返回 null。 */
    private static File knownDownloadsDir() {
        String[] keys = {
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders"
        };
        for (String key : keys) {
            String raw = regQuery(key, DOWNLOADS_GUID);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            File f = new File(expandEnv(raw.trim()));
            if (f.isDirectory()) {
                return f;
            }
        }
        File home = new File(System.getProperty("user.home", "."));
        for (String name : new String[]{"Downloads", "下载", "下載"}) {
            File f = new File(home, name);
            if (f.isDirectory()) {
                return f;
            }
        }
        return null;
    }

    /** 读 HKCU 下某个键的值（只用于 Downloads 已知文件夹，失败就返回 null）。 */
    private static String regQuery(String key, String value) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", value)
                    .redirectErrorStream(true)
                    .start();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("\\s+REG_(?:EXPAND_)?SZ\\s+(.+)$")
                            .matcher(line);
                    if (m.find() && line.contains(value)) {
                        return m.group(1).trim();
                    }
                }
            }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignore) {
            // 读不到注册表就按老办法找
        }
        return null;
    }

    /** 展开 %USERPROFILE% 这类环境变量（User Shell Folders 里存的是可展开字符串）。 */
    private static String expandEnv(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String v = System.getenv(m.group(1));
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(v == null ? m.group(0) : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 同名文件不覆盖：a.zip -> a (1).zip -> a (2).zip。 */
    static File uniqueFile(File dir, String suggested) {
        String name = suggested == null || suggested.isBlank() ? "download.bin" : suggested;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");     // 去掉 Windows 非法字符
        File f = new File(dir, name);
        if (!f.exists()) {
            return f;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) {
                return c;
            }
        }
        return new File(dir, System.currentTimeMillis() + "_" + name);
    }

    /** 进程内共享一个 CefApp；失败一次就记住原因，不反复下载。 */
    private static synchronized CefApp sharedApp() throws Exception {
        if (cefApp != null) {
            return cefApp;
        }
        if (!initError.isEmpty()) {
            throw new IllegalStateException("上次初始化失败：" + initError);
        }
        CefAppBuilder builder = new CefAppBuilder();
        File dir = installDir();
        builder.setInstallDir(dir);
        // 窗口模式：不用离屏渲染，Swing 里直接放 AWT 组件
        builder.getCefSettings().windowless_rendering_enabled = false;
        // 软件渲染兜底：部分老显卡/远程桌面下 GPU 合成会黑屏
        builder.addJcefArgs("--disable-gpu-compositing");

        // 让内嵌 Chromium 跟系统代理：Edge 能开 github、内嵌里却白屏，基本都是这里没接上。
        // 必须把 127.0.0.1 / localhost 排除在代理外，否则 DSH 的本地转发会被绕进代理。
        java.util.List<String> pargs = proxyArgs();
        for (String a : pargs) {
            builder.addJcefArgs(a);
            System.out.println("[jcef] " + a);
        }
        builder.setProgressHandler(new IProgressHandler() {
            @Override
            public void handleProgress(EnumProgress state, float percent) {
                String pct = percent < 0 ? "" : " " + Math.round(percent) + "%";
                System.out.println("[jcef] " + state + pct);
                String human;
                switch (state) {
                    case LOCATING: human = "正在定位 CEF 运行时…"; break;
                    case DOWNLOADING: human = "正在下载 CEF 运行时" + pct + "（约 138MB，只需一次）"; break;
                    case EXTRACTING: human = "正在解包 CEF 运行时" + pct; break;
                    case INSTALL: human = "正在安装" + pct; break;
                    case INITIALIZING: human = "正在启动 Chromium…"; break;
                    case INITIALIZED: human = "即将显示页面…"; break;
                    default: human = "初始化中…";
                }
                progressSink.accept("内嵌浏览器：" + human);
            }
        });
        cefApp = builder.build();
        return cefApp;
    }

    /** 从 Windows「Internet 选项」读代理，转成 CEF 参数；读不到就直连。 */
    private static java.util.List<String> proxyArgs() {
        java.util.List<String> args = new java.util.ArrayList<>();
        String manual = System.getProperty("dsh.cef.proxy", "").trim();
        if (!manual.isEmpty()) {
            args.add("--proxy-server=" + manual);
            args.add("--proxy-bypass-list=127.0.0.1;localhost;[::1]");
            return args;
        }
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return args;
        }
        String base = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
        String pac = regQuery(base, "AutoConfigURL");
        if (pac != null && !pac.isBlank()) {
            args.add("--proxy-pac-url=" + pac.trim());
            args.add("--proxy-bypass-list=127.0.0.1;localhost;[::1]");
            return args;
        }
        if (regDwordIsOne(base, "ProxyEnable")) {
            String server = regQuery(base, "ProxyServer");
            if (server != null && !server.isBlank()) {
                args.add("--proxy-server=" + server.trim());
                args.add("--proxy-bypass-list=127.0.0.1;localhost;[::1]");
            }
        }
        return args;
    }

    /** 读 REG_DWORD 并判断是否非 0（ProxyEnable 用；regQuery 只认字符串型）。 */
    private static boolean regDwordIsOne(String key, String value) {
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", value)
                    .redirectErrorStream(true).start();
            String all = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("REG_DWORD\\s+0x([0-9a-fA-F]+)").matcher(all);
            return m.find() && !"0".equals(m.group(1));
        } catch (Exception ignore) {
            return false;
        }
    }

    private static File installDir() {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null || base.isBlank()) {
            base = System.getProperty("user.home") + File.separator + ".dsh-client";
        }
        File dir = new File(base, "DshClient" + File.separator + "cef-bundle");
        dir.mkdirs();
        return dir;
    }

    /** 关窗时释放浏览器（CefApp 留给进程退出回收）。 */
    void disposeBrowser() {
        try {
            if (browser != null) {
                browser.close(true);
                browser = null;
            }
        } catch (Exception ignore) {
            // 退出路径上别再抛
        }
    }

    private static String esc(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
