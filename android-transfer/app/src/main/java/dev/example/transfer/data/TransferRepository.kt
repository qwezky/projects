package dev.example.transfer.data

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dev.example.transfer.net.DownloadOutcome
import dev.example.transfer.net.Downloader
import dev.example.transfer.net.HttpFileServer
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** 局域网文件服务的运行状态。 */
sealed interface ServerState {
    data object Stopped : ServerState

    data class Running(val port: Int) : ServerState

    data class Failed(val message: String) : ServerState
}

/**
 * 传输业务的数据层：文件落盘目录、文件列表、局域网 HTTP 服务、下载。
 *
 * 落盘目录是 app 私有目录（filesDir/downloads）：
 * 不需要任何存储权限，卸载 app 时随应用一起清掉；
 * 想放到公共「下载」目录再用 DownloadManager 或 MediaStore。
 */
class TransferRepository(context: Context) {

    val downloadDir: File = File(context.filesDir, "downloads").apply { mkdirs() }

    private var server: HttpFileServer? = null

    private val _files = MutableStateFlow<List<File>>(emptyList())
    val files: StateFlow<List<File>> = _files.asStateFlow()

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    fun refresh() {
        _files.value = downloadDir.listFiles()
            ?.filter { it.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun startServer(port: Int) {
        stopServer()
        val candidate = HttpFileServer(downloadDir, port)
        _serverState.value = try {
            candidate.start()
            server = candidate
            ServerState.Running(port)
        } catch (e: Exception) {
            runCatching { candidate.stop() }
            ServerState.Failed(e.message ?: e.toString())
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
        if (_serverState.value !is ServerState.Failed) {
            _serverState.value = ServerState.Stopped
        }
    }

    suspend fun download(rawUrl: String, onProgress: (Long, Long) -> Unit): DownloadOutcome {
        val url = normalizeUrl(rawUrl)
        val dest = File(downloadDir, fileNameFor(url))
        val outcome = Downloader.download(url, dest, onProgress)
        refresh()
        return outcome
    }

    /**
     * 用户经常漏掉协议头（直接粘 `mirrors.tuna.tsinghua.edu.cn/…`），
     * 那样 `URL()` 会抛 MalformedURLException，看起来像"下不下来"。这里补上。
     */
    private fun normalizeUrl(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    fun delete(file: File): Boolean {
        val ok = file.delete()
        if (ok) refresh()
        return ok
    }

    /** 退出时调用，别让服务在后台一直占着端口。 */
    fun close() {
        server?.stop()
        server = null
        _serverState.value = ServerState.Stopped
    }

    /** 局域网 IPv4（优先 192.168/10./172.16-31 这类站点内地址）。 */
    fun localIpv4(): String? {
        val addrs = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
        }.getOrNull().orEmpty()
        return (addrs.firstOrNull { it.isSiteLocalAddress } ?: addrs.firstOrNull())?.hostAddress
    }

    private fun fileNameFor(url: String): String {
        val raw = runCatching { url.toUri().lastPathSegment }.getOrNull().orEmpty()
        val base = raw.substringAfterLast('/').ifBlank { "download.bin" }
        val safe = base.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "download.bin" }
        // 同名则加时间戳，避免覆盖上次下的东西
        return if (File(downloadDir, safe).exists()) {
            val stem = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', "")
            val suffix = if (ext.isEmpty()) "" else ".$ext"
            "$stem-${System.currentTimeMillis()}$suffix"
        } else {
            safe
        }
    }
}
