package dev.example.dshclient;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

/**
 * 删除文件——优先**移到回收站**，实在不行才永久删。
 *
 * <p>为什么不用 `Files.delete`：那是永久删除，误删就没了。Windows 回收站是 Shell 的能力，
 * 纯 Java 没有 API，但系统自带 PowerShell，可以调 .NET 的
 * `Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(..., SendToRecycleBin)` —— 零依赖、无弹窗。
 *
 * <p>Linux 上退回 `gio trash`（GNOME 系自带，走 XDG 回收站）；都没有就连同 XDG 规则自己挪进
 * `~/.local/share/Trash/files`，最后才 `Files.delete`。
 */
final class Recycle {

    enum Result { RECYCLED, DELETED, FAILED }

    private Recycle() {
    }

    static Result delete(File file) {
        if (file == null || !file.exists()) {
            return Result.FAILED;
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                if (windowsRecycle(file)) {
                    return Result.RECYCLED;
                }
            } else {
                if (gioTrash(file)) {
                    return Result.RECYCLED;
                }
                if (xdgTrash(file)) {
                    return Result.RECYCLED;
                }
            }
        } catch (Exception e) {
            Startup.mark("移到回收站失败（" + e.getMessage() + "），改为永久删除：" + file);
        }
        try {
            Files.delete(file.toPath());
            return Result.DELETED;
        } catch (Exception e) {
            Startup.error("删除失败：" + file, e);
            return Result.FAILED;
        }
    }

    /** PowerShell 调 .NET：SendToRecycleBin（无确认框，不进永久删除）。 */
    private static boolean windowsRecycle(File file) throws Exception {
        String path = file.getAbsolutePath().replace("'", "''");
        // 枚举写成全名，不依赖 PowerShell 的字符串→枚举隐式转换
        String script = "Add-Type -AssemblyName Microsoft.VisualBasic; "
                + "[Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('" + path + "',"
                + "[Microsoft.VisualBasic.FileIO.UIOption]::OnlyErrorDialogs,"
                + "[Microsoft.VisualBasic.FileIO.RecycleOption]::SendToRecycleBin)";
        ProcessBuilder pb = new ProcessBuilder(powerShellExe(), "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-Command", script);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // 不读输出会把管道塞满；一个小缓冲就够，反正内容没用
        try (java.io.InputStream in = p.getInputStream()) {
            byte[] buf = new byte[512];
            while (in.read(buf) != -1) {
                // 丢弃
            }
        }
        boolean done = p.waitFor(15, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return false;
        }
        return p.exitValue() == 0 && !file.exists();
    }

    /** Windows 10/11 自带 powershell.exe；装了 PS7 的机器可能只有 pwsh。 */
    private static String powerShellExe() {
        String sysRoot = System.getenv("SystemRoot");
        if (sysRoot != null && !sysRoot.isEmpty()) {
            File exe = new File(sysRoot, "System32\\WindowsPowerShell\\v1.0\\powershell.exe");
            if (exe.isFile()) {
                return exe.getAbsolutePath();
            }
        }
        return "powershell";
    }

    private static boolean gioTrash(File file) {
        try {
            Process p = new ProcessBuilder("gio", "trash", file.getAbsolutePath())
                    .redirectErrorStream(true).start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && !file.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /** 没有 gio 时按 XDG 规范自己放进 ~/.local/share/Trash（含 .trashinfo，文件管理器能看到）。 */
    private static boolean xdgTrash(File file) {
        try {
            String home = System.getProperty("user.home");
            File base = new File(home, ".local/share/Trash");
            File files = new File(base, "files");
            File info = new File(base, "info");
            if (!files.isDirectory() && !files.mkdirs()) {
                return false;
            }
            if (!info.isDirectory() && !info.mkdirs()) {
                return false;
            }
            File target = new File(files, file.getName());
            for (int i = 1; target.exists() && i < 1000; i++) {
                target = new File(files, file.getName() + "." + i);
            }
            Files.move(file.toPath(), target.toPath());
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                    .format(new java.util.Date());
            String body = "[Trash Info]\nPath=" + file.getAbsolutePath().replace(" ", "%20")
                    + "\nDeletionDate=" + stamp + "\n";
            Files.write(new File(info, target.getName() + ".trashinfo").toPath(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
