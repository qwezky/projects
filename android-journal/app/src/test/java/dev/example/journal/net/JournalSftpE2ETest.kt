package dev.example.journal.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import dev.example.journal.data.TodoIndex
import java.io.File
import java.nio.file.Files

/**
 * **与 App 完全相同的代码路径**的端到端验证（JSch → sshd → 受限网关）。
 *
 * 需要环境变量才跑（否则整类跳过，不影响正常测试）：
 *   E2E_KEY=/tmp/.../id_rsa          （RSA-4096 私钥，授权行要与日志 App 那行同形）
 *   E2E_USER=user  E2E_HOST=127.0.0.1  E2E_PORT=22
 *   E2E_ARCHIVE_DIR=/home/user/phone-archive/raw        （默认值就是它）
 *   E2E_TODOS_INDEX=/home/user/phone-archive/todos/index.jsonl
 *
 * 跑法：
 *   E2E_USER=user E2E_KEY=/tmp/journal-e2e/id_rsa E2E_HOST=127.0.0.1 \
 *     ./gradlew testDebugUnitTest --tests '*JournalSftpE2ETest*'
 *
 * 它验证四件事（都在不污染归档库的前提下）：
 *  1. 网关**只**放行 SFTP 与字面量 `archive-trigger`（发 `id` 必须被拒）；
 *  2. 待办索引能经 SFTP 读回来、并且 App 的解析器能把它解析成带日期的条目；
 *  3. 存档上传这条路走得通（写进 raw、字节数对得上），然后**把它删掉**；
 *  4. 触发命令能拿到服务端的 `QUEUED`。
 */
class JournalSftpE2ETest {

    private val keyPath: String? = System.getenv("E2E_KEY")
    private val user: String = System.getenv("E2E_USER") ?: "user"
    private val host: String = System.getenv("E2E_HOST") ?: "127.0.0.1"
    private val port: Int = System.getenv("E2E_PORT")?.toIntOrNull() ?: 22
    private val archiveDir: String = System.getenv("E2E_ARCHIVE_DIR") ?: "/home/user/phone-archive/raw"
    private val todosIndex: String = System.getenv("E2E_TODOS_INDEX") ?: "/home/user/phone-archive/todos/index.jsonl"

    private fun requireEnv() {
        assumeTrue("未设 E2E_KEY，跳过", keyPath != null)
        val k = File(keyPath!!)
        assumeTrue("私钥不存在：${k.absolutePath}", k.isFile)
    }

    private fun open(): SftpClient {
        requireEnv()
        return SftpClient.open(
            SftpClient.Options(
                user = user,
                host = host,
                port = port,
                privateKeyPath = keyPath!!,
                connectTimeoutMs = 25_000,
            )
        )
    }

    @Test
    fun `网关只放行 SFTP 与 archive-trigger`() {
        open().use { client ->
            val refused = runCatching { client.execOnce("id") }
            assertTrue(
                "`id` 必须被网关拒绝，实际：${refused.getOrNull() ?: refused.exceptionOrNull()?.message}",
                refused.isFailure,
            )
            val empty = runCatching { client.execOnce("") }
            assertTrue("空命令也必须被拒绝", empty.isFailure)
        }
    }

    @Test
    fun `待办索引能读回来并解析出日期区间`() {
        open().use { client ->
            val size = client.remoteSize(todosIndex)
            assumeTrue("服务器上还没有 $todosIndex（跳过）", size > 0)
            val text = client.readText(todosIndex)
            val mtime = client.remoteMtime(todosIndex)
            val parsed = TodoIndex.parse(text)

            assertTrue("应该解析出若干条待办", parsed.items.isNotEmpty())
            assertEquals("索引里不该有解析不了的行", 0, parsed.skippedLines)
            assertTrue("mtime 应该拿得到（秒）", mtime > 1_600_000_000L)

            // 日期区间筛选：把所有条目的最小/最大日期当区间，必须一条不漏
            val dates = parsed.items.mapNotNull { TodoIndex.validDate(it.startDate.ifBlank { it.endDate }) }
            assertTrue("至少要有带日期的条目", dates.isNotEmpty())
            val lo = dates.minOrNull()!!.toString()
            val hi = dates.maxOrNull()!!.toString()
            val inRange = TodoIndex.inRange(parsed.items, lo, hi)
            val shouldBeIn = parsed.items.filter { item ->
                val d = TodoIndex.validDate(item.startDate) ?: return@filter false
                !d.isBefore(java.time.LocalDate.parse(lo)) && !d.isAfter(java.time.LocalDate.parse(hi))
            }
            assertTrue("开始日期落在区间内的条目一条都不能漏", inRange.containsAll(shouldBeIn))

            println(
                "[E2E] 待办索引 ${size}B · ${parsed.items.size} 条 · 覆盖 $lo ~ $hi · " +
                    TodoIndex.summary(inRange),
            )
        }
    }

    @Test
    fun `存档上传走得通并且不会留下垃圾`() {
        open().use { client ->
            val name = "journal-e2e-${System.currentTimeMillis()}.txt"
            val local = Files.createTempFile("journal-e2e-", ".txt").toFile()
            local.writeText("日志 App 端到端自测：这一行会在核对字节数之后被删掉，服务端不该留下它。\n")

            val remotePath = "${archiveDir.trimEnd('/')}/$name"
            try {
                client.ensureDir(archiveDir)
                assertTrue("raw 目录应当存在（不存在时 ensureDir 会建）", client.remoteSize(archiveDir) >= 0)
                client.upload(local, remotePath)
                assertEquals("传完远端字节数应和本地一致", local.length(), client.remoteSize(remotePath))
                // 读回来对一遍内容：只比大小的话，"传了个空文件"也会通过
                val back = client.readText(remotePath)
                assertEquals(local.readText(), back)
                println("[E2E] 存档上传 OK：$remotePath（${local.length()}B），随后删除")
            } finally {
                // 关键：删掉，别把测试垃圾留在服务端收件箱里（触发归档那一轮就归档不到东西）
                runCatching { client.remove(remotePath) }
                local.delete()
            }
            assertEquals("测试文件应当已经删掉", -1L, client.remoteSize(remotePath))
        }
    }

    @Test
    fun `触发命令能拿到 QUEUED`() {
        open().use { client ->
            val out = client.execOnce("archive-trigger")
            assertTrue("服务端应答应含 QUEUED，实际：$out", out.contains("QUEUED"))
            println("[E2E] archive-trigger → $out")
        }
    }
}
