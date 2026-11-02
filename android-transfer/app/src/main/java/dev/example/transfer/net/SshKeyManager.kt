package dev.example.transfer.net

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.File
import java.util.Base64

/**
 * 密钥管理。
 *
 * 用 RSA-4096 而不是 ed25519 的原因（实测 jar 得出结论）：
 * mwiede/jsch 是 Multi-Release jar，`META-INF/versions/15/` 里才是 JCE EdDSA 实现，
 * 而基础包里 `jce/KeyPairGenEdDSA` 硬编码 `"KeyPairGenEdDSA requires Java15+."` 并抛
 * UnsupportedOperationException → Android 不认 Multi-Release 且没有 JCE EdDSA，
 * 于是必失败（上层显示 `Invalid privatekey`）。
 * 要 ed25519 就得补 bcprov-jdk18on（+6.9MB；已验证只需低层 API、不必注册 JCE Provider）。
 * 既然密钥由 App 自己生成、不复用现成的，RSA-4096 零成本。
 */
object SshKeyManager {

    const val RSA_BITS = 4096

    data class Generated(val privateKeyFile: File, val authorizedKeysLine: String)

    fun generateRsa4096(
        jsch: JSch,
        privateKeyFile: File,
        passphrase: String?,
        comment: String,
        options: String? = null,
    ): Generated {
        val keyPair = KeyPair.genKeyPair(jsch, KeyPair.RSA, RSA_BITS)
        privateKeyFile.parentFile?.mkdirs()
        keyPair.writePrivateKey(privateKeyFile.absolutePath, passphrase?.toByteArray(Charsets.UTF_8))
        return Generated(privateKeyFile, authorizedKeysLine(keyPair.publicKeyBlob, comment, options))
    }

    /** 拼出可直接粘进服务器 `~/.ssh/authorized_keys` 的一行。 */
    fun authorizedKeysLine(publicKeyBlob: ByteArray, comment: String, options: String? = null): String =
        buildString {
            if (!options.isNullOrBlank()) append(options.trim()).append(' ')
            append("ssh-rsa ").append(Base64.getEncoder().encodeToString(publicKeyBlob))
            if (comment.isNotBlank()) append(' ').append(comment.trim())
        }

    /**
     * 把这把 key 的能力收窄到"只能 SFTP，且起点钉在 startDir"。
     *
     * 这是本 App **唯一**的形态：v2.5 起存档功能搬到了日志 App，这里只剩上传/下载（纯 SFTP）。
     * 授权行与服务器 `~/.ssh/authorized_keys` 第 3 行逐字一致（2026-10-15 一起收回了
     * "能发 archive-trigger"那条能力）—— 「我的公钥」页面显示的必须是这一行。
     *
     * 注意：没有 chroot（那需要 sudo 改 sshd_config），所以它仍能按绝对路径读写 $HOME。
     */
    fun restrictedSftpOptions(startDir: String): String =
        "restrict,command=\"internal-sftp -d $startDir\""
}
