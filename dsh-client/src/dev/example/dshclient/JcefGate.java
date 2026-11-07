package dev.example.dshclient;

/**
 * JCEF 的"存在性检查"。
 *
 * <p>这个类**故意不 import 任何 org.cef / me.friwi 的类**：主 jar 里没有 JCEF 时，
 * 只要不碰 {@link JcefView}，就永远不会触发 NoClassDefFoundError。
 * 判断统一走 {@link Class#forName}，所以没有 JCEF 也能正常打包、正常跑（退回浏览器窗口）。
 */
final class JcefGate {

    private JcefGate() {
    }

    /** 三个必需类都在 classpath 上才算可用。 */
    static boolean available() {
        return has("org.cef.CefApp")
                && has("org.cef.browser.CefBrowser")
                && has("me.friwi.jcefmaven.CefAppBuilder");
    }

    private static boolean has(String className) {
        try {
            Class.forName(className, false, JcefGate.class.getClassLoader());
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 给日志用：说明为什么没起来。 */
    static String diagnose() {
        if (!has("org.cef.CefApp")) {
            return "缺少 jcef-api.jar（cef\\ 目录）";
        }
        if (!has("me.friwi.jcefmaven.CefAppBuilder")) {
            return "缺少 jcefmaven.jar（cef\\ 目录）";
        }
        return "可用";
    }
}
