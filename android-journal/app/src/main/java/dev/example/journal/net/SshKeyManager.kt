package dev.example.journal.net

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
     * ⚠️ 这是**纯 SFTP** 那一种收窄，不是本 App 用的形态（本 App 还要发
     * `archive-trigger`，能 SFTP 的 forced command 发不出命令）。保留它是因为
     * 别的用途（只传文件）用得上，以及既有单元测试在用。
     *
     * 注意：没有 chroot（那需要 sudo 改 sshd_config），所以它仍能按绝对路径读写 $HOME。
     */
    fun restrictedSftpOptions(startDir: String): String =
        "restrict,command=\"internal-sftp -d $startDir\""

    /**
     * 本 App 该用的那一行：`command` 指向服务端的 [gatewayPath] 网关。
     *
     * 选项与服务器 `~/.ssh/authorized_keys` 里本 App 那一行**逐字对齐**，
     * 免得"页面上显示的行"与"真正生效的行"悄悄漂移 —— 传输助手那边吃过一次亏：
     * 页面生成的还是 `internal-sftp -d ...`，照着粘一遍就把 `archive-trigger` 顶掉，
     * 症状是"文件传得上去、但服务端永远不开始归档"。
     *
     * 用 `restrict` 一次关掉所有多余能力（pty / 端口转发 / agent / X11 / user-rc）：
     * 日志 App 只做 SFTP 与一条触发命令，一个转发都不需要。
     *
     * 这个形态是**端到端实测过的**（2026-10-15 用同形临时 key 在真实 sshd 上验过：
     * SFTP 读 `todos/index.jsonl`、写 `phone-archive/raw`、发 `archive-trigger` 都通，
     * 而 `id` 被拒）。
     */
    fun restrictedGatewayOptions(gatewayPath: String): String =
        "restrict,command=\"$gatewayPath\""
}
