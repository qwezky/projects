package dev.example.journal.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权行拼装的回归测试。
 *
 * 这里防的是一个很隐蔽的坑：页面上「我的公钥」显示的那一行，必须和服务器上**真正生效**
 * 的那一行同形。传输助手那边两处曾经不一致（页面还是 `internal-sftp -d ...`），
 * 照着粘一遍就会把网关顶掉，症状是"文件传得上去、但服务端永远不开始归档"——
 * 从客户端完全看不出原因。日志 App 两件事（传存档、读待办）全靠网关，更不能错。
 */
class SshKeyManagerTest {

    private val gateway = "/home/user/dsh-work/inbox-archiver/bin/transfer-gateway"

    @Test
    fun `网关行用 restrict 一次关掉所有多余能力`() {
        val line = SshKeyManager.restrictedGatewayOptions(gateway)
        assertEquals("restrict,command=\"$gateway\"", line)
        assertTrue("必须用 restrict", line.startsWith("restrict,"))
        assertTrue("必须把 forced command 指向网关", line.contains("command=\"$gateway\""))
        // 日志 App 不做端口转发：restrict 已经默认拒绝所有转发（也不需要 permitopen）
        assertFalse("不该放行任何端口转发", line.contains("permitopen"))
        assertFalse("不该再自己拼一串 no-pty/no-agent-forwarding", line.contains("no-pty"))
        assertFalse("不能是纯 SFTP 的 forced command", line.contains("internal-sftp"))
    }

    @Test
    fun `纯 SFTP 变体仍然可用（别的用途）`() {
        val line = SshKeyManager.restrictedSftpOptions("%d/phone-upload")
        assertTrue(line.contains("internal-sftp -d %d/phone-upload"))
        assertTrue(line.startsWith("restrict"))
    }

    @Test
    fun `授权行把选项拼在公钥前面`() {
        val optionLine = SshKeyManager.restrictedGatewayOptions(gateway)
        val line = SshKeyManager.authorizedKeysLine(ByteArray(8) { 1 }, "journal-app", optionLine)
        assertTrue(line.startsWith(optionLine + " "))
        assertTrue(line.contains(" ssh-rsa "))
        assertTrue(line.endsWith(" journal-app"))
    }

    @Test
    fun `默认配置里的路径与服务器部署一致`() {
        val cfg = dev.example.journal.data.SshConfig()
        assertEquals(gateway, cfg.gatewayPath)
        assertEquals("/home/user/phone-archive/raw", cfg.archiveDir)
        assertEquals("/home/user/phone-archive/todos/index.jsonl", cfg.todosIndexPath)
    }
}
