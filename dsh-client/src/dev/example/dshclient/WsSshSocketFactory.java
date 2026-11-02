package dev.example.dshclient;

import com.jcraft.jsch.SocketFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 把 SSH 的字节流塞进一条 WebSocket —— 也就是「SSH over WebSocket」那条隧道
 * （`wss://ssh.example.com/`：Cloudflare 隧道的 `ssh://` 入口指向服务器的 localhost:22）。
 *
 * 为什么需要它（2026-10-13 实测）：移动数据下直连 IPv6 到 CERNET 丢包 **10–35%**、
 * cwnd 被压到 1–3、吞吐 0.1–0.5 Mbit/s；而绕 Cloudflare 是好的（服务器侧 1.06 MB/s）。
 * 换路比调参数有效。**SSH 仍然端到端加密**：Cloudflare 只看得到加密后的字节。
 *
 * 为什么用 JDK 自带的 `java.net.http.WebSocket` 而不是第三方库：
 * 这个 jar 要能双击就跑，多一个依赖就多一份"到别人机器上缺库"的风险。Java 11+ 自带。
 *
 * 两个必须注意的点（传输助手那边踩过的坑，别删注释）：
 *  1. **零长度帧不能当 EOF**：对端发空帧只是空帧，据此结束流会让上层看到
 *     `read()<=0` 而误报"流已关闭"；
 *  2. 关闭/错误原因要留证据，否则出错时只剩 JSch 一句含糊的包装异常。
 */
public final class WsSshSocketFactory implements SocketFactory {

    private final String wsUrl;
    private final int connectTimeoutMs;
    private WsStreams streams;

    public WsSshSocketFactory(String wsUrl, int connectTimeoutMs) {
        this.wsUrl = wsUrl;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        try {
            this.streams = new WsStreams(wsUrl, connectTimeoutMs);
            // JSch 的 SocketFactory 只用我们返回的流，所以给一个未连接的空壳即可
            return new Socket();
        } catch (Exception e) {
            throw new IOException("WebSocket 握手失败（" + wsUrl + "）：" + e, e);
        }
    }

    @Override
    public InputStream getInputStream(Socket socket) throws IOException {
        if (streams == null) throw new IOException("先调 createSocket");
        return streams.in;
    }

    @Override
    public OutputStream getOutputStream(Socket socket) throws IOException {
        if (streams == null) throw new IOException("先调 createSocket");
        return streams.out;
    }

    /** 诊断用：出问题时把 WS 现场打出来。 */
    public String diagnostics() {
        return streams == null ? "(未建立)" : streams.diagnostics();
    }

    private static final class WsStreams {
        private final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        private volatile boolean eof;
        private volatile String closeReason = "(未给出原因)";
        private long receivedBytes;
        private long sentBytes;

        final InputStream in;
        final OutputStream out;
        private final WebSocket ws;

        WsStreams(String url, int timeoutMs) throws Exception {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(Math.max(1_000, timeoutMs)))
                    .build();
            CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        @Override
                        public void onOpen(WebSocket webSocket) {
                            webSocket.request(Long.MAX_VALUE);
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                            byte[] b = new byte[data.remaining()];
                            data.get(b);
                            push(b);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                            push(data.toString().getBytes(StandardCharsets.ISO_8859_1));
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                            closeReason = "WS 关闭 code=" + statusCode + " reason=" + reason;
                            finish();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket webSocket, Throwable error) {
                            closeReason = "WS 错误：" + error;
                            finish();
                        }

                        private void push(byte[] b) {
                            if (b.length == 0) return;   // 空帧忽略，绝不能当 EOF
                            receivedBytes += b.length;
                            chunks.add(b);
                        }
                    });
            this.ws = future.get(Math.max(2_000, timeoutMs), TimeUnit.MILLISECONDS);

            this.in = new InputStream() {
                private byte[] cur = new byte[0];
                private int off;

                @Override
                public int read() throws IOException {
                    byte[] one = new byte[1];
                    int n = read(one, 0, 1);
                    return n <= 0 ? -1 : (one[0] & 0xFF);
                }

                @Override
                public int read(byte[] b, int o, int len) throws IOException {
                    if (len == 0) return 0;
                    while (off >= cur.length) {
                        if (eof) return -1;
                        byte[] next;
                        try {
                            next = chunks.poll(1, TimeUnit.SECONDS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                        if (next == null) continue;              // 还没数据，接着等
                        cur = next;
                        off = 0;
                    }
                    int n = Math.min(len, cur.length - off);
                    System.arraycopy(cur, off, b, o, n);
                    off += n;
                    return n;
                }

                @Override
                public int available() {
                    int total = cur.length - off;
                    for (byte[] c : chunks) total += c.length;
                    return total;
                }

                @Override
                public void close() {
                    finish();
                }
            };

            this.out = new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    write(new byte[]{(byte) b}, 0, 1);
                }

                @Override
                public void write(byte[] b, int o, int len) throws IOException {
                    if (eof) throw new IOException("WebSocket 已结束（" + closeReason + "）");
                    if (len == 0) return;                        // 不发空帧
                    byte[] payload = (o == 0 && len == b.length) ? b.clone() : Arrays.copyOfRange(b, o, o + len);
                    try {
                        ws.sendBinary(ByteBuffer.wrap(payload), true).join();
                        sentBytes += len;
                    } catch (Exception e) {
                        throw new IOException("WebSocket 发送失败：" + e, e);
                    }
                }

                @Override
                public void close() {
                    finish();
                }
            };
        }

        private void finish() {
            if (!eof) {
                eof = true;
                chunks.add(new byte[0]);   // 唤醒阻塞中的 read（真实数据帧永不为空）
            }
        }

        String diagnostics() {
            return "收 " + receivedBytes + " B / 发 " + sentBytes + " B / 队列 " + chunks.size() + " / " + closeReason;
        }
    }
}
