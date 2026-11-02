package dev.example.transfer.net

import com.jcraft.jsch.JSch
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 生成一把 RSA-4096 并打印可直接粘进服务器 `~/.ssh/authorized_keys` 的那一行。
 *
 * 平时不跑（避免污染正常测试）；显式给环境变量才执行：
 *   E2E_KEYGEN_OUT=/tmp/transfer-e2e/id_rsa ./gradlew testDebugUnitTest --tests '*SshKeyGenToolTest*'
 */
class SshKeyGenToolTest {

    @Test
    fun generateRestrictedSftpKey() {
        val out = System.getenv("E2E_KEYGEN_OUT")
        assumeTrue("未设 E2E_KEYGEN_OUT，跳过密钥生成", out != null)

        val keyFile = File(out!!)
        val startDir = System.getenv("E2E_REMOTE_DIR") ?: "%d/phone-upload"
        val comment = System.getenv("E2E_KEY_COMMENT") ?: "transfer-demo-app"

        val generated = SshKeyManager.generateRsa4096(
            jsch = JSch(),
            privateKeyFile = keyFile,
            passphrase = null,
            comment = comment,
            options = SshKeyManager.restrictedSftpOptions(startDir),
        )

        assertTrue("私钥应已落盘", keyFile.isFile && keyFile.length() > 0)

        // 这两行是给外面脚本抓的，格式别改
        println("E2E_PRIVATE_KEY=${keyFile.absolutePath}")
        println("E2E_PRIVATE_KEY_BYTES=${keyFile.length()}")
        println("E2E_AUTHORIZED_KEYS_LINE=${generated.authorizedKeysLine}")
    }
}
