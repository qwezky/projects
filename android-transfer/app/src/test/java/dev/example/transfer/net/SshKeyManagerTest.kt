package dev.example.transfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 授权行拼装的回归测试。
 *
 * 这里防的是一个很隐蔽的坑：页面上「我的公钥」显示的那一行，必须和服务器上**真正生效**
 * 的那一行同形。曾经两处不一致（页面还是 `internal-sftp -d ...`），照着粘一遍就会把
 * 网关顶掉，症状是"文件传得上去、但服务端永远不开始归档"——从客户端完全看不出原因。
 *
 * v2.5：存档搬到了日志 App，本 App 只剩 SFTP，服务器第 3 行也一起收回成了纯 SFTP 形态
 * （`restrict,command="internal-sftp -d %d/phone-upload"`），所以这里断言的就是它。
 */
class SshKeyManagerTest {

    /** 服务器 `~/.ssh/authorized_keys` 第 3 行现在的形态（2026-10-15 收回能力后）。 */
    private val serverLine3Options = "restrict,command=\"internal-sftp -d %d/phone-upload\""

    @Test
    fun `页面显示的行与服务器第 3 行逐字一致`() {
        val line = SshKeyManager.restrictedSftpOptions("%d/phone-upload")
        assertEquals("页面必须显示服务器上真正生效的那一行", serverLine3Options, line)
        assertFalse("v2.5 起不该再指向归档网关", line.contains("transfer-gateway"))
        assertFalse("也不该再出现 archive-trigger", line.contains("archive-trigger"))
    }

    @Test
    fun `收窄项齐全（restrict 一次关掉所有多余能力）`() {
        val line = SshKeyManager.restrictedSftpOptions("%d/phone-upload")
        assertTrue("必须用 restrict 一次性关掉所有多余能力", line.startsWith("restrict,"))
        assertTrue(line.contains("internal-sftp -d %d/phone-upload"))
    }

    @Test
    fun `授权行把选项拼在公钥前面`() {
        val optionLine = SshKeyManager.restrictedSftpOptions("%d/phone-upload")
        val line = SshKeyManager.authorizedKeysLine(ByteArray(8) { 1 }, "transfer-demo-app", optionLine)
        assertTrue(line.startsWith(optionLine + " "))
        assertTrue(line.contains(" ssh-rsa "))
        assertTrue(line.endsWith(" transfer-demo-app"))
    }

    @Test
    fun `默认配置里的上传目录与服务器部署一致`() {
        val cfg = dev.example.transfer.data.SshConfig()
        assertEquals("/home/user/phone-upload", cfg.remoteDir)
    }
}
