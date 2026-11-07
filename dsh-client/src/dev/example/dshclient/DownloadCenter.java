package dev.example.dshclient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 下载中心：内嵌浏览器（JCEF）的下载都记在这里，状态栏那个"下载"按钮读它。
 *
 * <p>为什么不让 CEF 自己弹框：JCEF 的原生"另存为"对话框在 windowed 模式下没有父窗口，
 * 直接把进程带崩过（2.13.6/2.13.8 的 dump 都在 libcef.dll）。所以这里改成：
 * **静默落盘 + 自己维护列表**，用户点状态栏的下载按钮就能看到进度、打开文件、定位目录。
 */
final class DownloadCenter {

    /** 一条下载记录。id 用 CEF 的 download id，同一个文件重下也不会串。 */
    static final class Item {
        final int id;
        final String name;
        final File path;
        volatile int percent;
        volatile String state;

        Item(int id, String name, File path, int percent, String state) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.percent = percent;
            this.state = state;
        }
    }

    private static final DownloadCenter INSTANCE = new DownloadCenter();

    private final List<Item> items = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private DownloadCenter() {
        startSweeper();
    }

    /**
     * 兜底扫描：CEF 有时"文件已经下完、却不再回调 onDownloadUpdated"，界面就永远停在 100% / 下载中。
     *
     * <p>这里不依赖任何回调，直接看磁盘：CEF 下载时会写 `<文件名>.crdownload`，完成后改名成正式文件。
     * 所以「正式文件存在 + 没有 .crdownload 兄弟文件」= 真的下完了。
     */
    private void startSweeper() {
        java.util.Timer t = new java.util.Timer("dl-sweeper", true);
        t.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    for (Item it : items) {
                        if (!"下载中".equals(it.state) || it.path == null) {
                            continue;
                        }
                        java.io.File f = it.path;
                        java.io.File part = new java.io.File(f.getAbsolutePath() + ".crdownload");
                        if (f.isFile() && f.length() > 0 && !part.exists()) {
                            it.percent = 100;
                            it.state = "已完成";
                            clearMarkOfWeb(f);
                            fire();
                        }
                    }
                } catch (RuntimeException ignore) {
                    // 扫描出错不能影响下载本身
                }
            }
        }, 1500, 1500);
    }

    static DownloadCenter get() {
        return INSTANCE;
    }

    /** 新的下载（最新的排最前）。 */
    void start(int id, String name, File path) {
        items.add(0, new Item(id, name, path, 0, "下载中"));
        fire();
    }

    void progress(int id, int percent) {
        Item it = find(id);
        if (it != null) {
            it.percent = percent;
            it.state = "下载中";
            fire();
        }
    }

    /**
     * 去掉 Windows 的"来源：网络"标记（Mark of the Web）。
     *
     * <p>内嵌 Chromium 下载的文件会被加上 `Zone.Identifier` 备用数据流，资源管理器就显示
     * "来自网络"，双击可能被 SmartScreen 拦。这里在完成后把该流清空。
     */
    static void clearMarkOfWeb(java.io.File f) {
        if (f == null || !f.isFile()) {
            return;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            return;
        }
        // 1) 先把 ADS 清成 0 字节（多数情况够用）
        try {
            java.nio.file.Path ads = java.nio.file.Paths.get(f.getAbsolutePath() + ":Zone.Identifier");
            java.nio.file.Files.write(ads, new byte[0]);
        } catch (Exception ignore) {
            // 继续尝试 2)
        }
        // 2) 再让系统正式解除锁定（Explorer 里那个"解除锁定"复选框就是它）
        try {
            String path = f.getAbsolutePath().replace("'", "''");
            new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Unblock-File -LiteralPath '" + path + "'")
                    .redirectErrorStream(true).start();
        } catch (Exception ignore) {
            // 清不掉也不影响文件本身
        }
    }

    void done(int id) {
        Item it = find(id);
        if (it != null) {
            it.percent = 100;
            it.state = "已完成";
            fire();
        }
    }

    void fail(int id, String why) {
        Item it = find(id);
        if (it != null) {
            it.state = why == null || why.isEmpty() ? "已失败" : "已失败：" + why;
            fire();
        }
    }

    /** 取消 / 中断。 */
    void cancel(int id) {
        Item it = find(id);
        if (it != null) {
            it.state = "已取消";
            fire();
        }
    }

    List<Item> items() {
        return new ArrayList<>(items);
    }

    /** 还没结束的条数（按钮上显示小圆点用）。 */
    int activeCount() {
        int n = 0;
        for (Item it : items) {
            if ("下载中".equals(it.state)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 删掉一条：文件走回收站（失败才永久删），再从列表移除。
     * @return 给日志用的人话（空串 = 正常）
     */
    String delete(int id, boolean deleteFile) {
        Item it = find(id);
        if (it == null) {
            return "记录不存在";
        }
        String msg = "";
        if (deleteFile && it.path != null && it.path.isFile()) {
            Recycle.Result r = Recycle.delete(it.path);
            if (r == Recycle.Result.RECYCLED) {
                msg = "";
            } else if (r == Recycle.Result.DELETED) {
                msg = "回收站不可用，已永久删除";
            } else {
                msg = "文件删除失败（记录已移除）";
            }
        }
        items.remove(it);
        fire();
        return msg;
    }

    void clearFinished() {
        items.removeIf(it -> !"下载中".equals(it.state));
        fire();
    }

    void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private Item find(int id) {
        for (Item it : items) {
            if (it.id == id) {
                return it;
            }
        }
        return null;
    }

    private void fire() {
        for (Runnable r : listeners) {
            try {
                r.run();
            } catch (RuntimeException ignore) {
                // 界面回调出错不能影响下载本身
            }
        }
    }
}
