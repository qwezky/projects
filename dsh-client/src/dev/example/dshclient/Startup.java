package dev.example.dshclient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 启动期的"黑匣子"。
 *
 * <p>问题背景：Windows 上双击 .bat 后如果窗口没出来，用户什么都看不到 —— 异常打在 stderr 上，
 * 控制台一关就没了。这里把每个阶段和未捕获异常都写进 {@code %USERPROFILE%\.dsh-client-startup.log}，
 * 哪怕界面还没建起来，也能从"最后一步走到了哪里"直接定位。
 *
 * <p>文件超过 512KB 就重开一个，避免常年累积。
 */
final class Startup {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final File LOG = new File(System.getProperty("user.home"), ".dsh-client-startup.log");

    private Startup() {
    }

    /** 在 main 的第一行调用：接管未捕获异常 + 记一条启动标记。 */
    static void install() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            mark("!! 未捕获异常 in thread " + t.getName() + "：" + e);
            appendStackTrace(e);
        });
        mark("==== 启动：" + DshClient.VERSION + " ====");
        mark("java=" + System.getProperty("java.version")
                + " vendor=" + System.getProperty("java.vendor")
                + " os=" + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        mark("cwd=" + System.getProperty("user.dir"));
        mark("jar=" + DshClient.jarPathHint());
    }

    /**
     * EDT 看门狗：每 5 秒往 EDT 排一个空任务，8 秒还没执行完就判定"界面卡死"，
     * 把所有线程栈（尤其是 AWT-EventQueue-0）写进日志。这样卡死也能留证据。
     */
    static void startEdtWatchdog() {
        Thread t = new Thread(() -> {
            while (true) {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                try {
                    javax.swing.SwingUtilities.invokeLater(latch::countDown);
                    if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                        mark("!! EDT 超过 8s 无响应，判定卡死，dump 线程栈 ↓");
                        dumpThreads();
                    }
                } catch (Throwable e) {
                    error("看门狗自身出错", e);
                }
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "dsh-edt-watchdog");
        t.setDaemon(true);
        t.start();
        mark("EDT 看门狗已启动");
    }

    private static void dumpThreads() {
        java.util.Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            Thread th = e.getKey();
            String name = th.getName();
            if (!name.contains("AWT") && !name.contains("dsh") && !name.contains("Swing")) {
                continue;
            }
            append("        --- " + name + " [" + th.getState() + "]");
            for (StackTraceElement el : e.getValue()) {
                append("            at " + el);
            }
        }
    }

    /** 阶段标记。 */
    static void mark(String msg) {
        append(LocalDateTime.now().format(TS) + "  " + msg);
    }

    /** 带堆栈的错误。 */
    static void error(String msg, Throwable e) {
        append(LocalDateTime.now().format(TS) + "  [ERROR] " + msg
                + (e == null ? "" : "：" + e));
        if (e != null) {
            appendStackTrace(e);
        }
    }

    private static void appendStackTrace(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\n")) {
            append("        " + line);
        }
    }

    private static synchronized void append(String line) {
        try {
            if (LOG.length() > 512 * 1024) {
                File old = new File(LOG.getParentFile(), LOG.getName() + ".old");
                // 直接覆盖旧备份，不做多份轮转
                LOG.renameTo(old);
            }
            try (OutputStreamWriter w = new OutputStreamWriter(
                    new FileOutputStream(LOG, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write(System.lineSeparator());
            }
        } catch (Exception ignore) {
            // 日志失败绝不能影响启动
        }
    }
}
