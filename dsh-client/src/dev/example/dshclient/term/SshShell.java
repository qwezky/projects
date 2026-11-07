package dev.example.dshclient.term;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 一条交互式 SSH shell 通道（带 PTY），复用客户端已有的主机/端口/账号/私钥。
 *
 * <p>为什么单独开一条会话而不是复用端口转发那条：JSch 的 {@link Session} 上可以同时挂多个 Channel，
 * 但 shell 通道的 PTY 尺寸、与端口转发互不影响；而**独立会话**还有个实际好处 —— 转发那条断了，
 * 终端不会跟着断（看门狗重连时用户正在跑的 {@code tail -f} 不会被一起杀掉）。
 *
 * <p>已知边界：服务器的 {@code authorized_keys} 里若写了 {@code restrict} / {@code no-pty}，
 * shell 会被拒（表现为 connect 抛 "channel open failure"）。这是服务器侧的策略，客户端只能把话说明白，
 * 不能绕过去。
 */
public final class SshShell {

    /** 输出回调：读线程上调用，实现方自己负责切到 EDT。 */
    public interface Listener {
        void onOutput(byte[] data, int len);

        void onClosed(String reason);
    }

    private final String host;
    private final int port;
    private final String user;
    private final String keyPath;
    /**
     * 非空 = 走 Cloudflare 的 WebSocket 隧道（SSH over WS）。
     * 由调用方构造传入 —— {@code WsSshSocketFactory} 在 dshclient 包内是包可见的，
     * 这里只认 JSch 的接口，省得为了一个工厂方法去改老代码的可见性。
     */
    private final SocketFactory socketFactory;
    private final Listener listener;

    private Session session;
    private ChannelShell channel;
    private InputStream in;
    private OutputStream out;
    private Thread reader;
    private volatile boolean closing;

    public SshShell(String host, int port, String user, String keyPath, SocketFactory socketFactory, Listener listener) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.keyPath = keyPath;
        this.socketFactory = socketFactory;
        this.listener = listener;
    }

    /** 阻塞连接；调用方放后台线程。cols/rows 是初始 PTY 尺寸。 */
    public void connect(int cols, int rows, int timeoutMs) throws Exception {
        JSch jsch = new JSch();
        if (keyPath != null && !keyPath.isBlank()) {
            jsch.addIdentity(keyPath);
        }
        Session s = jsch.getSession(user, host, port);
        s.setConfig("StrictHostKeyChecking", "no");
        s.setConfig("PreferredAuthentications", "publickey");
        s.setServerAliveInterval(20_000);
        s.setServerAliveCountMax(3);
        if (socketFactory != null) {
            s.setSocketFactory(socketFactory);
        }
        s.connect(timeoutMs > 0 ? timeoutMs : 15_000);

        ChannelShell ch;
        try {
            ch = (ChannelShell) s.openChannel("shell");
        } catch (Exception e) {
            throw new IllegalStateException("服务器不允许开 shell（" + e.getMessage() + "）。"
                    + "常见原因：authorized_keys 里这把 key 带 no-pty 或 command=\"internal-sftp\"。"
                    + "解决：给同一把 key 再加一行允许 pty 的条目，或在设置里换一把没有限制的 key。", e);
        }
        ch.setPtyType("xterm-256color", Math.max(2, cols), Math.max(2, rows), 0, 0);
        ch.setEnv("TERM", "xterm-256color");
        InputStream chIn = ch.getInputStream();
        OutputStream chOut = ch.getOutputStream();
        try {
            ch.connect(timeoutMs > 0 ? timeoutMs : 15_000);
        } catch (Exception e) {
            throw new IllegalStateException("服务器拒绝分配 PTY（" + e.getMessage() + "）。"
                    + "多半是 authorized_keys 里的 no-pty / command=\"internal-sftp\" 限制。", e);
        }
        // 有些限制只在前几秒体现出来：连上后 session 立刻退出、或 PTY 请求被拒绝
        try {
            Thread.sleep(300);
        } catch (InterruptedException ignore) {
            Thread.currentThread().interrupt();
        }
        if (!ch.isConnected()) {
            throw new IllegalStateException("shell 通道建立后立刻被服务器关闭 —— "
                    + "authorized_keys 大概率限制了 pty/强制了 sftp。");
        }

        this.session = s;
        this.channel = ch;
        this.in = chIn;
        this.out = chOut;
        this.closing = false;

        reader = new Thread(this::readLoop, "dsh-term-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        byte[] buf = new byte[8192];
        try {
            while (!closing) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                if (n > 0) {
                    listener.onOutput(buf, n);
                }
            }
            listener.onClosed(closing ? "已断开" : "远端关闭了连接");
        } catch (IOException e) {
            if (!closing) {
                listener.onClosed("读取出错：" + e.getMessage());
            }
        } finally {
            closeQuietly();
        }
    }

    public void write(byte[] data) {
        OutputStream o = out;
        if (o == null) {
            return;
        }
        try {
            synchronized (this) {
                o.write(data);
                o.flush();
            }
        } catch (IOException e) {
            listener.onClosed("写入失败：" + e.getMessage());
        }
    }

    public void setPtySize(int cols, int rows) {
        ChannelShell ch = channel;
        if (ch == null) {
            return;
        }
        try {
            ch.setPtySize(Math.max(2, cols), Math.max(2, rows), 0, 0);
        } catch (Exception ignore) {
            // 有些服务器/网关不支持窗口变更，忽略即可（输出会按 80 列折行，不影响可用）
        }
    }

    public boolean isConnected() {
        return channel != null && channel.isConnected();
    }

    public void close() {
        closing = true;
        try {
            if (channel != null) {
                channel.disconnect();
            }
        } catch (Exception ignore) {
            // 关的时候出错没意义
        }
        try {
            if (session != null) {
                session.disconnect();
            }
        } catch (Exception ignore) {
            // 同上
        }
        channel = null;
        session = null;
        in = null;
        out = null;
    }

    private void closeQuietly() {
        try {
            if (session != null) {
                session.disconnect();
            }
        } catch (Exception ignore) {
            // 忽略
        }
    }
}
