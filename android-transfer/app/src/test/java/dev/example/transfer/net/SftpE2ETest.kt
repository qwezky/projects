package dev.example.transfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random

/**
 * **与 App 完全相同的代码路径**的端到端验证：Java-WebSocket → Cloudflare 隧道 → sshd → JSch/SFTP。
 *
 * 需要环境变量才跑（否则整类跳过，不影响正常测试）：
 *   E2E_WS_URL=wss://ssh.example.com/   走隧道
 *   E2E_HOST=127.0.0.1  E2E_PORT=22    直连（本机环路，用来当"没有 RTT"的对照组）
 *   E2E_USER=user  E2E_KEY=/tmp/.../id_rsa  E2E_REMOTE_DIR=/home/user/phone-upload
 *
 * 跑法：
 *   E2E_WS_URL=... E2E_USER=user E2E_KEY=... E2E_REMOTE_DIR=... \
 *     ./gradlew testDebugUnitTest --tests '*SftpE2ETest*'
 */
class SftpE2ETest {

    private val wsUrl: String? = System.getenv("E2E_WS_URL")
    private val user: String = System.getenv("E2E_USER") ?: "user"
    private val keyPath: String? = System.getenv("E2E_KEY")
    private val remoteDir: String = System.getenv("E2E_REMOTE_DIR") ?: "/home/user/phone-upload"
    private val directHost: String = System.getenv("E2E_HOST") ?: "127.0.0.1"
    private val directPort: Int = System.getenv("E2E_PORT")?.toIntOrNull() ?: 22
    /** tunnel | direct —— 同一套代码，只换传输层 */
    private val transport: String = System.getenv("E2E_TRANSPORT") ?: "tunnel"
    private val wsHostName: String = System.getenv("E2E_WS_HOST") ?: "ssh.example.com"

    private data class Tuning(val label: String, val window: Int, val packet: Int, val bulk: Int) {
        companion object {
            val DEFAULT = Tuning("JSch 默认", 0, 0, 0)
            val MEDIUM = Tuning("中等 window=1M pkt=64K bulk=64", 1 shl 20, 64 * 1024, 64)
            val AGGRESSIVE = Tuning("激进 window=8M pkt=128K bulk=256", 8 shl 20, 128 * 1024, 256)
        }
    }

    private fun requireEnv() {
        assumeTrue("未设 E2E_KEY，跳过", keyPath != null)
        if (transport == "tunnel") assumeTrue("传输=tunnel 但未设 E2E_WS_URL，跳过", wsUrl != null)
        val k = File(keyPath!!)
        assumeTrue("私钥不存在：${k.absolutePath}", k.isFile)
    }

    private fun requireTunnel() {
        requireEnv()
        assumeTrue("该用例专门测隧道，但未设 E2E_WS_URL，跳过", wsUrl != null)
    }

    private fun open(tuning: Tuning = Tuning.DEFAULT, useTunnel: Boolean = transport == "tunnel"): SftpClient =
        SftpClient.open(
            SftpClient.Options(
                user = user,
                host = if (useTunnel) wsHostName else directHost,
                port = directPort,
                privateKeyPath = keyPath!!,
                wsUrl = if (useTunnel) wsUrl else null,
                connectTimeoutMs = 25_000,
                windowSizeMax = tuning.window,
                packetSize = tuning.packet,
                bulkRequests = tuning.bulk,
            )
        )

    private fun tempFile(bytes: Int, seed: Long = 42L): File {
        val f = Files.createTempFile("e2e-", ".bin").toFile()
        val rnd = Random(seed)
        val buf = ByteArray(256 * 1024)
        f.outputStream().buffered().use { out ->
            var left = bytes
            while (left > 0) {
                rnd.nextBytes(buf)
                val n = minOf(left, buf.size)
                out.write(buf, 0, n)
                left -= n
            }
        }
        return f
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ 测试

    @Test
    fun tunnel_connects_lists_directory_and_discovers_ipv6() {
        requireTunnel()
        val t0 = System.currentTimeMillis()
        open().use { c ->
            val handshakeMs = System.currentTimeMillis() - t0
            val entries = c.list(remoteDir)
            println("E2E 握手(经隧道) 耗时 = ${handshakeMs}ms")
            println("E2E 远端目录 $remoteDir 条目数 = ${entries.size}")
            entries.take(5).forEach { println("E2E   ${if (it.dir) "d" else "-"} ${it.size} ${it.name}") }

            // 关键验证：收窄成 restrict,command="internal-sftp" 的 key 能否读 /proc/net/if_inet6
            val ipv6 = ServerAddressDiscovery.globalIpv6(c)
            println("E2E 读 /proc/net/if_inet6 得到的公网 IPv6 = $ipv6")
            assertTrue("应能读出至少一个全局 IPv6 地址（免 DDNS 的地址发现靠它）", ipv6.isNotEmpty())
            assertTrue("应是 240c/240a 之类的全局地址", ipv6.all { !it.startsWith("fe80") && it.contains(":") })
        }
    }

    @Test
    fun upload_then_download_bytes_match() {
        requireEnv()
        val size = System.getenv("E2E_VERIFY_BYTES")?.toIntOrNull() ?: (4 * 1024 * 1024)
        val local = tempFile(size)
        val remote = "$remoteDir/e2e-verify.bin"
        val back = Files.createTempFile("e2e-back-", ".bin").toFile()

        try {
            open().use { c ->
                val t0 = System.currentTimeMillis()
                var lastPct = -1
                try {
                    c.upload(local, remote) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct / 25 != lastPct / 25 && pct >= 0) { lastPct = pct; println("E2E 上传进度 ${pct}%") }
                    }
                } catch (e: Exception) {
                    c.diagnostics("上传失败时")
                    throw e
                }
                val upMs = System.currentTimeMillis() - t0
                println("E2E 上传 ${size / 1024}KB 用时 ${upMs}ms → ${"%.0f".format(size / 1024.0 / (upMs / 1000.0))} KB/s")

                assertEquals("远端大小应与本地一致", size.toLong(), c.remoteSize(remote))
                assertEquals("本地 sha256 应与远端拉回的一致", sha256(local), run {
                    c.download(remote, back)
                    sha256(back)
                })
                c.diagnostics("传输正常结束时")
            }
        } finally {
            // 远端文件留在 ~/phone-upload 里，由外部脚本统一清理
            local.delete(); back.delete()
        }
    }

    @Test
    fun resume_continues_from_remote_size() {
        requireEnv()
        val firstHalfBytes = 1 * 1024 * 1024
        val full = tempFile(firstHalfBytes + 512 * 1024)
        val remote = "$remoteDir/e2e-resume.bin"

        // 先只上传前半段（造出一个"传了一半"的远端文件）
        val partial = Files.createTempFile("e2e-partial-", ".bin").toFile()
        full.inputStream().use { input ->
            partial.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var left = firstHalfBytes
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(left, buf.size))
                    if (n < 0) break
                    out.write(buf, 0, n); left -= n
                }
            }
        }

        try {
            open().use { c ->
                c.upload(partial, remote)
                val remoteAfterPartial = c.remoteSize(remote)
                println("E2E 续传前远端大小 = $remoteAfterPartial")
                assertEquals(firstHalfBytes.toLong(), remoteAfterPartial)

                // 续传的进度回调必须：第一次 = 远端已有大小，最后一次 = 全长，且单调不减。
                // 依据（字节码核对）：put(...,RESUME) 在 init() 之后会先回调一次 count(existing)，
                // 把它当成本次写入量去累加，进度就会从起点再叠一遍、直接冲过头。
                val progress = mutableListOf<Long>()
                c.resumeUpload(full, remote, remoteAfterPartial) { done, total ->
                    progress.add(done)
                    assertEquals("进度回调里的 total 应是本地全长", full.length(), total)
                }
                println("E2E 续传进度回调 ${progress.size} 次：前三次=${progress.take(3)} 末值=${progress.lastOrNull()}")
                assertEquals("第一次进度应是续传起点", remoteAfterPartial, progress.first())
                assertEquals("最后一次进度应是全长（不能把起点重复叠加）", full.length(), progress.last())
                assertTrue("进度必须单调不减", progress.zipWithNext().all { (a, b) -> b >= a })

                val remoteAfterResume = c.remoteSize(remote)
                println("E2E 续传后远端大小 = $remoteAfterResume（本地 ${full.length()}）")
                assertEquals("续传后应与本地全长一致", full.length(), remoteAfterResume)

                val back = Files.createTempFile("e2e-resume-back-", ".bin").toFile()
                c.download(remote, back)
                assertEquals("续传后的内容必须逐字节正确", sha256(full), sha256(back))
                back.delete()
            }
        } finally {
            partial.delete(); full.delete()
        }
    }

    @Test
    fun measures_throughput_loopback_vs_tunnel_and_tuning() {
        requireEnv()
        val loBytes = System.getenv("E2E_LO_BYTES")?.toIntOrNull() ?: (8 * 1024 * 1024)
        val tunBytes = System.getenv("E2E_TUN_BYTES")?.toIntOrNull() ?: (2 * 1024 * 1024)

        println("E2E ================ 吞吐对照 ================")
        for (tuning in listOf(Tuning.DEFAULT, Tuning.MEDIUM, Tuning.AGGRESSIVE)) {
            // 对照组：本机环路直连（没有 RTT），看协议本身的上限
            val lo = tempFile(loBytes)
            try {
                open(tuning, useTunnel = false).use { c ->
                    val t0 = System.currentTimeMillis()
                    c.upload(lo, "$remoteDir/e2e-lo.bin")
                    val ms = System.currentTimeMillis() - t0
                    println("E2E [环路] ${tuning.label} : ${loBytes / 1024}KB / ${ms}ms = ${"%.1f".format(loBytes / 1024.0 / (ms / 1000.0))} KB/s")
                }
            } finally {
                lo.delete()
            }
        }
        for (tuning in listOf(Tuning.DEFAULT, Tuning.MEDIUM, Tuning.AGGRESSIVE)) {
            if (wsUrl == null) {
                println("E2E [隧道] 未设 E2E_WS_URL，跳过")
                break
            }
            val t = tempFile(tunBytes)
            try {
                open(tuning, useTunnel = true).use { c ->
                    val t0 = System.currentTimeMillis()
                    try {
                        c.upload(t, "$remoteDir/e2e-tun.bin")
                    } catch (e: Exception) {
                        c.diagnostics("吞吐-隧道 ${tuning.label} 失败")
                        println("E2E [隧道] ${tuning.label} : 失败 ${e.message?.take(50)}")
                        throw e
                    }
                    val ms = System.currentTimeMillis() - t0
                    println("E2E [隧道] ${tuning.label} : ${tunBytes / 1024}KB / ${ms}ms = ${"%.1f".format(tunBytes / 1024.0 / (ms / 1000.0))} KB/s")
                }
            } catch (e: Exception) {
                // 单个调参失败不要中断整轮
            } finally {
                t.delete()
            }
        }
        println("E2E ==========================================")
    }

    @Test
    fun local_ws_bridge_upload_matches_bytes() {
        // 验证我自己的 WS 字节管道：本地 WS→TCP 桥接本机 sshd，**完全绕开 Cloudflare 隧道**。
        // 失败 ⇒ 我的代码有问题；通过 ⇒ 问题在隧道那一跳。
        assumeTrue("未设 E2E_KEY，跳过", keyPath != null)
        val bridge = LocalWsTcpBridge(tcpHost = "127.0.0.1", tcpPort = 22)
        bridge.isDaemon = true
        bridge.start()
        // 等桥起来
        val deadline = System.currentTimeMillis() + 5000
        while (bridge.port <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val wsLocal = "ws://127.0.0.1:${bridge.port}/"
        println("本地桥 wsUrl = $wsLocal")

        val size = System.getenv("E2E_LOCAL_BRIDGE_BYTES")?.toIntOrNull() ?: (8 * 1024 * 1024)
        val local = tempFile(size)
        val back = Files.createTempFile("e2e-bridge-back-", ".bin").toFile()
        val remote = "$remoteDir/e2e-bridge.bin"

        try {
            SftpClient.open(
                SftpClient.Options(
                    user = user, host = "127.0.0.1", port = 22,
                    privateKeyPath = keyPath!!, wsUrl = wsLocal, connectTimeoutMs = 20_000,
                )
            ).use { c ->
                val t0 = System.currentTimeMillis()
                try {
                    c.upload(local, remote)
                } catch (e: Exception) {
                    c.diagnostics("本地桥上传失败时")
                    throw e
                }
                val ms = System.currentTimeMillis() - t0
                println("E2E [本地 WS 桥] 上传 ${size / 1024}KB / ${ms}ms = ${"%.1f".format(size / 1024.0 / (ms / 1000.0))} KB/s")
                assertEquals("远端大小应一致", size.toLong(), c.remoteSize(remote))
                c.download(remote, back)
                assertEquals("经本地 WS 桥传输后字节必须完全一致", sha256(local), sha256(back))
                c.diagnostics("本地桥正常结束时")
            }
        } finally {
            bridge.shutdown()
            local.delete(); back.delete()
        }
    }

    @Test
    fun tunnel_size_sweep() {
        requireTunnel()
        println("E2E ===== 经隧道的尺寸扫描（看是阈值型还是随机型）=====")
        for (kb in listOf(64, 128, 256, 512, 1024, 2048)) {
            val bytes = kb * 1024
            val local = tempFile(bytes, seed = kb.toLong())
            val remote = "$remoteDir/e2e-sweep.bin"
            var result = "成功"
            try {
                open().use { c ->
                    val t0 = System.currentTimeMillis()
                    try {
                        c.upload(local, remote)
                        val ms = System.currentTimeMillis() - t0
                        val remoteSize = c.remoteSize(remote)
                        if (remoteSize != bytes.toLong()) result = "大小不符（远端 $remoteSize）"
                        else result = "成功 ${ms}ms ${"%.0f".format(kb / (ms / 1000.0))} KB/s"
                    } catch (e: Exception) {
                        c.diagnostics("sweep ${kb}KB 失败")
                        result = "失败：${e.message?.take(60)}"
                    }
                }
            } finally {
                local.delete()
            }
            println("E2E 隧道 ${kb}KB → $result")
        }
        println("E2E ==============================================")
    }

    @Test
    fun stream_upload_and_resume_match_bytes() {
        // App 的真实上传路径：文件来自 SAF（内容 URI），只有 InputStream，没有本地文件路径。
        // 这里验证 uploadStream：整传、以及"远端已有一半时从偏移续传"。
        requireEnv()
        val totalBytes = System.getenv("E2E_STREAM_BYTES")?.toIntOrNull() ?: (3 * 1024 * 1024)
        val full = tempFile(totalBytes, seed = 7L)
        val remote = "$remoteDir/e2e-stream.bin"

        try {
            open().use { c ->
                // ---- 1) 整传（模拟首次上传）----
                val progress = mutableListOf<Long>()
                val written = full.inputStream().use { input ->
                    c.uploadStream(input, remote, totalBytes.toLong()) { done, _ -> progress.add(done) }
                }
                assertEquals("应写入全部字节", totalBytes.toLong(), written)
                assertEquals("远端大小应一致", totalBytes.toLong(), c.remoteSize(remote))
                assertTrue("应回报进度（拿到 ${progress.size} 次）", progress.isNotEmpty())
                assertEquals(
                    "进度必须是**累计值**且最后等于总字节数（JSch 传的是增量，累加错了就会只等于最后一块）",
                    totalBytes.toLong(), progress.last(),
                )
                assertTrue(
                    "进度必须单调不减：${progress.take(5)}…",
                    progress.zipWithNext().all { (a, b) -> b >= a },
                )
                println("E2E 流式上传 ${totalBytes / 1024}KB 完成，进度回调 ${progress.size} 次，最后一次=${progress.last()}")

                // ---- 2) 续传：把远端截掉一部分，再从偏移接着传 ----
                val half = totalBytes / 2
                val all = full.readBytes()
                val prefix = java.nio.file.Files.createTempFile("e2e-prefix-", ".bin").toFile()
                prefix.writeBytes(all.copyOfRange(0, half))
                c.upload(prefix, remote) // 先把远端做成"传了一半"
                assertEquals("续传前远端应是一半", half.toLong(), c.remoteSize(remote))

                val resumed = full.inputStream().use { input ->
                    c.uploadStream(input, remote, totalBytes.toLong(), resumeFrom = half.toLong())
                }
                assertEquals("续传应只写剩余字节", (totalBytes - half).toLong(), resumed)
                assertEquals("续传后远端应是全长", totalBytes.toLong(), c.remoteSize(remote))

                val back = java.nio.file.Files.createTempFile("e2e-stream-back-", ".bin").toFile()
                c.download(remote, back)
                assertEquals("续传后的内容必须逐字节正确", sha256(full), sha256(back))
                back.delete(); prefix.delete()
            }
        } finally {
            full.delete()
        }
    }

    @Test
    fun measures_upload_forms_under_latency() {
        // 复现"手机走直连 IPv6"的场景：本地 WS 桥 + 人为延迟（模拟 RTT），
        // 对比 JSch 三种上传代码路径。App 用的是 C（OutputStream 形式），怀疑它最慢。
        assumeTrue("未设 E2E_KEY，跳过", keyPath != null)
        val bytes = System.getenv("E2E_FORM_BYTES")?.toIntOrNull() ?: (512 * 1024)
        val local = tempFile(bytes, seed = 99L)
        println("E2E ===== 三种上传形式对比（${bytes / 1024}KB，本地 WS 桥 + 人为延迟）=====")

        try {
            for (delayMs in listOf(0, 40)) {
                val bridge = LocalWsTcpBridge(tcpPort = 22, delayMs = delayMs)
                bridge.isDaemon = true
                bridge.start()
                val deadline = System.currentTimeMillis() + 5000
                while (bridge.port <= 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
                val ws = "ws://127.0.0.1:${bridge.port}/"
                println("E2E --- 模拟 RTT ≈ ${delayMs * 2}ms（每帧延迟 ${delayMs}ms）---")
                try {
                    val tunings = if (delayMs == 0) listOf(Tuning.DEFAULT) else listOf(Tuning.DEFAULT, Tuning.AGGRESSIVE)
                    for (tuning in tunings) {
                        val forms = listOf<Pair<String, (SftpClient) -> Unit>>(
                            "A put(本地路径)" to { c -> c.upload(local, "$remoteDir/e2e-formA.bin") },
                            "B put(InputStream)" to { c ->
                                local.inputStream().use { c.uploadInputStream(it, "$remoteDir/e2e-formB.bin", bytes.toLong()) }
                            },
                            "C put(->OutputStream)" to { c ->
                                local.inputStream().use { c.uploadStream(it, "$remoteDir/e2e-formC.bin", bytes.toLong()) }
                            },
                        )
                        for ((label, action) in forms) {
                            try {
                                SftpClient.open(
                                    SftpClient.Options(
                                        user = user, host = "127.0.0.1", port = 22,
                                        privateKeyPath = keyPath!!, wsUrl = ws, connectTimeoutMs = 20_000,
                                        readTimeoutMs = 120_000,
                                        windowSizeMax = tuning.window, packetSize = tuning.packet, bulkRequests = tuning.bulk,
                                    )
                                ).use { c ->
                                    val t0 = System.currentTimeMillis()
                                    action(c)
                                    val ms = System.currentTimeMillis() - t0
                                    val kbps = bytes / 1024.0 / (ms / 1000.0)
                                    println("E2E [${tuning.label}] $label : ${ms}ms = ${"%.0f".format(kbps)} KB/s")
                                }
                            } catch (e: Exception) {
                                println("E2E [${tuning.label}] $label : 失败 ${e.message?.take(60)}")
                            }
                        }
                    }
                } finally {
                    bridge.shutdown()
                    Thread.sleep(200)
                }
            }
        } finally {
            local.delete()
        }
        println("E2E ====================================================")
    }

    @Test
    fun download_resumes_from_local_prefix() {
        // 「从服务器下载」的续传：本地已有 half 字节时，应从远端 half 偏移接着读
        // （`_get` 字节码：offset = (mode==RESUME) ? skip : 0；SFTP READ 自带偏移 ⇒ 不重传前缀）
        requireEnv()
        val totalBytes = 3 * 1024 * 1024
        val full = tempFile(totalBytes, seed = 11L)
        val remote = "$remoteDir/e2e-dl-resume.bin"
        val staging = Files.createTempFile("e2e-dl-", ".bin").toFile()

        try {
            open().use { c ->
                c.upload(full, remote)
                assertEquals(totalBytes.toLong(), c.remoteSize(remote))

                // 造一个"下了一半"的本地文件（前 1/3）
                val half = totalBytes / 3
                staging.writeBytes(full.readBytes().copyOfRange(0, half))

                val progress = mutableListOf<Long>()
                val got = c.downloadTo(remote, staging, resumeFrom = half.toLong()) { done, total ->
                    progress.add(done)
                    assertEquals("total 应是远端全长", totalBytes.toLong(), total)
                }
                println("E2E 续传下载：回调 ${progress.size} 次，第一次=${progress.firstOrNull()}，末次=${progress.lastOrNull()}")
                assertEquals("本地最终应是全长", totalBytes.toLong(), got)
                assertEquals("续传后内容必须逐字节一致", sha256(full), sha256(staging))
                assertEquals("第一次进度应是续传起点", half.toLong(), progress.first())
                assertEquals(
                    "末次进度应是全长（起点不能被重复累加）",
                    totalBytes.toLong(), progress.last(),
                )
                assertTrue("进度必须单调不减", progress.zipWithNext().all { (a, b) -> b >= a })

                // 再从零下一次（覆盖语义），确保 resumeFrom=0 时不会往旧内容后面追加
                val fresh = Files.createTempFile("e2e-dl-fresh-", ".bin").toFile()
                fresh.writeBytes(ByteArray(1024) { 7 })
                c.downloadTo(remote, fresh)
                assertEquals("覆盖下载后应是全长", totalBytes.toLong(), fresh.length())
                assertEquals("覆盖下载内容必须逐字节一致", sha256(full), sha256(fresh))
                fresh.delete()
            }
        } finally {
            full.delete(); staging.delete()
        }
    }

    @Test
    fun parses_proc_net_if_inet6() {
        val sample = """
            fe800000000000005e879cfffee36836 03 40 20 80 wlp0s20f3
            00000000000000000000000000000001 01 80 10 80       lo
            240cc60330099e655e879cfffee36836 03 40 00 00 wlp0s20f3
        """.trimIndent()
        val got = ServerAddressDiscovery.parseGlobalIpv6(sample)
        assertEquals(listOf("2001:db8::1"), got)
    }

    @Test
    fun authorized_keys_line_is_restricted_and_wellformed() {
        val line = SshKeyManager.authorizedKeysLine(
            publicKeyBlob = ByteArray(8) { 1 },
            comment = "transfer-demo-app",
            options = SshKeyManager.restrictedSftpOptions("%d/phone-upload"),
        )
        assertTrue("必须带 restrict", line.startsWith("restrict,"))
        assertTrue("必须强制 internal-sftp 且钉住起点目录", line.contains("command=\"internal-sftp -d %d/phone-upload\""))
        assertTrue("公钥类型应为 ssh-rsa", line.contains(" ssh-rsa "))
        assertTrue("应带注释", line.endsWith("transfer-demo-app"))
        println("E2E authorized_keys 行样例 = $line")
    }
}
