package dev.example.transfer.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.example.transfer.data.DirCache
import dev.example.transfer.data.PublicDownloads
import dev.example.transfer.data.SshConfig
import dev.example.transfer.data.SshUploadRepository
import dev.example.transfer.data.TransferRepository
import dev.example.transfer.data.TransferRoute
import dev.example.transfer.data.UploadOutcome
import dev.example.transfer.net.DirList
import dev.example.transfer.net.HttpTransferClient
import dev.example.transfer.net.HttpFileServer
import dev.example.transfer.net.RemoteEntry
import dev.example.transfer.net.SshErrors
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 界面状态都在 ViewModel 里，旋转屏幕/配置变化不会丢；网络操作跑在 viewModelScope。
 *
 * 这个 App 做五组事（卡片 ①②③④⑤）：
 *   ① 走哪条路（直连/隧道/自动）+ 我的公钥
 *   ② 测试连接（用户名/直连地址等连接参数也放这里）
 *   ③ 从服务器下载：列远端目录、续传下载
 *   ④ 看/分享/删除已下载的文件（并把落盘目录显示出来）
 *   ⑤ 上传到服务器：选文件上传、**文本上传**、列目标目录、断了自动重连续传
 *
 * ⚠️ 原来的「存档区」和「分享入档」在 v2.5 拆走了 —— 现在在 `~/android-dev/journal-app`
 * （日志 App）里。那边是同一个路由/密钥/续传实现，只是目标钉死在 phone-archive/raw，
 * 并且多了一个只读的「看待办」能力。
 */
class MainScreenViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    private val repository = TransferRepository(context)

    val files: StateFlow<List<File>> = repository.files

    /** ③ 卡片要把目录本身显示出来（用户得知道东西落在哪）。 */
    val downloadDirPath: String get() = repository.downloadDir.absolutePath

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    init {
        repository.refresh()
    }

    fun delete(file: File) {
        if (repository.delete(file)) _status.value = "已删除：${file.name}"
    }

    // ------------------------------------------------- SSH/SFTP 配置

    private val sshRepository = SshUploadRepository(appContext)

    private val _sshConfig = MutableStateFlow(SshConfig.load(appContext))
    val sshConfig: StateFlow<SshConfig> = _sshConfig.asStateFlow()

    private val _sshStatus = MutableStateFlow("")
    val sshStatus: StateFlow<String> = _sshStatus.asStateFlow()

    private val _upload = MutableStateFlow(UploadUiState())
    val upload: StateFlow<UploadUiState> = _upload.asStateFlow()

    /**
     * ⑥ 上传卡片自己的结果文字。为什么和 [sshStatus] 分开：② 测试连接和 ⑥ 上传都会写状态，
     * 共用一个 flow 时"传完的结果"会跑到 ② 那张卡上去，看起来像测试连接说的话。
     */
    private val _uploadStatus = MutableStateFlow("")
    val uploadStatus: StateFlow<String> = _uploadStatus.asStateFlow()

    /** 上一轮失败的文件，供一键重试（重试会自动续传，代价很小）。 */
    private val _failedUploads = MutableStateFlow<List<Uri>>(emptyList())
    val failedUploads: StateFlow<List<Uri>> = _failedUploads.asStateFlow()

    private val _publicKeyLine = MutableStateFlow("")
    val publicKeyLine: StateFlow<String> = _publicKeyLine.asStateFlow()

    private val uploadGuard = AtomicBoolean(false)

    fun updateSshConfig(transform: (SshConfig) -> SshConfig) {
        val before = _sshConfig.value
        val next = transform(before)
        _sshConfig.value = next
        SshConfig.save(appContext, next)
        // 用户改了"走哪条路" → 清掉本次会话里"实测哪条慢"的结论，重新判
        if (next.route != before.route) sshRepository.resetRouteDecision()
    }

    fun showPublicKey() {
        _publicKeyLine.value = runCatching { sshRepository.authorizedKeysLine(_sshConfig.value) }
            .getOrElse { "生成失败：${it.message}" }
    }

    fun hidePublicKey() {
        _publicKeyLine.value = ""
    }

    /** 上传进度/状态。 */
    data class UploadUiState(
        val running: Boolean = false,
        val fileName: String = "",
        val phase: String = "",
        val done: Long = 0,
        val total: Long = -1,
        val speedKbps: Long = 0,
        val index: Int = 0,
        val count: Int = 0,
    )

    // ------------------------------------------------- ⑥ 上传（文件 / 文本）

    /** 测试连接：直连优先，失败退回隧道；成功后顺便列一下远端目录。 */
    fun testSshConnection() {
        if (_upload.value.running) return
        _sshStatus.value = "连接中…（自动：有 IPv6 走直连，没有就走 HTTP 通道；传的过程中太慢也会换路）"
        viewModelScope.launch {
            try {
                val conn = sshRepository.ensureConnection(_sshConfig.value, force = true)
                if (conn.viaDirect) updateSshConfig { it.copy(lastGoodDirectHost = conn.host) }
                val head = when (conn.route) {
                    TransferRoute.DIRECT -> "✅ ${conn.label}（${conn.elapsedMs}ms）"
                    TransferRoute.HTTP ->
                        "✅ ${conn.label}（${conn.elapsedMs}ms，经 Cloudflare 的 HTTP 通道）—— 传完按大小核对"
                    TransferRoute.TUNNEL ->
                        "⚠️ ${conn.label}（${conn.elapsedMs}ms）—— 只适合小操作，大文件会断，建议改成「自动」或「HTTP」"
                    TransferRoute.AUTO -> "✅ ${conn.label}（${conn.elapsedMs}ms）"
                }
                val dir = _sshConfig.value.remoteDirForDisplay
                val dirLine = try {
                    if (conn.client.remoteSize(dir) >= 0) {
                        val listing = runCatching { sshRepository.listRemote(conn, _sshConfig.value) }
                            .getOrElse { emptyList() }
                        "远端目录：$dir —— 在（${listing.size} 项）"
                    } else {
                        "远端目录：$dir —— **不存在**（上传时会尝试自动创建）"
                    }
                } catch (e: Exception) {
                    "远端目录：$dir —— 查不动：${SshErrors.describe(e)}"
                }
                _sshStatus.value = buildString {
                    append(head)
                    append("\n")
                    append(dirLine)
                    val failures = sshRepository.directFailureSummary()
                    if (failures.isNotEmpty() && !conn.viaDirect) append("\n$failures")
                    if (_sshConfig.value.conservative) append("\n保守传输模式已开")
                }
            } catch (e: Exception) {
                _sshStatus.value = "❌ 连接失败：${SshErrors.describe(e)}\n${sshRepository.directFailureSummary()}"
            }
        }
    }

    /** 上传一组 SAF 文件（逐个传，断了自动重连接着传）。 */
    fun uploadUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        startUploadBatch(uris)
    }

    /** 文本上传：把一段文本变成服务器上的一个文件。 */
    fun uploadText(text: String, fileName: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            _uploadStatus.value = "文本是空的，没东西可传"
            return
        }
        val name = fileName.trim().ifBlank { "text-${System.currentTimeMillis()}.txt" }
        startUploadBatch(emptyList(), text = body, textName = name)
    }

    /** 上传批次的统一入口：[uris] 非空传文件，[text] 非空传文本，共用同一套进度与失败收集。 */
    private fun startUploadBatch(uris: List<Uri>, text: String? = null, textName: String = "") {
        if (uris.isEmpty() && text == null) return
        if (!uploadGuard.compareAndSet(false, true)) {
            _uploadStatus.value = "上一次上传还在跑，等它结束再点"
            return
        }
        viewModelScope.launch {
            try {
                _upload.value = UploadUiState(running = true, count = if (text != null) 1 else uris.size)
                _failedUploads.value = emptyList()
                val results = mutableListOf<String>()
                val failed = mutableListOf<Uri>()

                if (text != null) {
                    results += runOne(_upload, textName, 1, 1, null) { onPhase, onProgress ->
                        // ⑥ 上传卡片的文本走用户在界面上配的 remoteDir
                        sshRepository.uploadText(_sshConfig.value, text, textName, onPhase = onPhase, onProgress = onProgress)
                    }
                } else {
                    val items = withContext(Dispatchers.IO) { uris.map { sshRepository.describe(it) } }
                    items.forEachIndexed { index, item ->
                        _upload.value = UploadUiState(
                            running = true, fileName = item.displayName,
                            done = 0, total = item.size, index = index + 1, count = items.size,
                        )
                        results += runOne(_upload, item.displayName, index + 1, items.size, { failed += item.uri }) { onPhase, onProgress ->
                            // ⑥ 的目标是用户在界面上配的 remoteDir（默认 phone-upload），维持不变
                            sshRepository.uploadResilient(
                                _sshConfig.value, item,
                                onPhase = onPhase, onProgress = onProgress,
                            )
                        }
                    }
                }
                _failedUploads.value = failed
                _uploadStatus.value = results.joinToString("\n")
            } catch (e: Exception) {
                _uploadStatus.value = "❌ 上传失败：${SshErrors.describe(e)}"
            } finally {
                // 传完之后这个目录的内容变了：把缓存作废，下次进来先核对再画
                DirCache.invalidate(appContext, _sshConfig.value.remoteDir.trimEnd('/'))
                _upload.value = UploadUiState()
                uploadGuard.set(false)
            }
        }
    }

    /**
     * 传一个（文件或文本）：刷进度/速度，并拼出给人看的结果行。
     *
     * [state] 由调用方给（卡片 ⑤ 上传用 [upload]）—— 进度条的形状固定，多张卡片各自独立。
     */
    private suspend fun runOne(
        state: MutableStateFlow<UploadUiState>,
        displayName: String,
        index: Int,
        count: Int,
        onFailure: (() -> Unit)?,
        action: suspend ((String) -> Unit, (Long, Long) -> Unit) -> UploadOutcome,
    ): String {
        state.value = state.value.copy(fileName = displayName, index = index, count = count)
        var phaseStartedAt = System.currentTimeMillis()
        var phaseStartDone = -1L
        return try {
            val outcome = action({ phase ->
                phaseStartedAt = System.currentTimeMillis()
                phaseStartDone = -1L
                state.value = state.value.copy(phase = phase, speedKbps = 0)
            }) { done, total ->
                if (phaseStartDone < 0) {
                    phaseStartDone = done
                    phaseStartedAt = System.currentTimeMillis()
                }
                val secs = ((System.currentTimeMillis() - phaseStartedAt).coerceAtLeast(1)) / 1000.0
                val kbps = ((done - phaseStartDone) / 1024.0 / secs).toLong().coerceAtLeast(0L)
                state.value = state.value.copy(done = done, total = total, speedKbps = kbps)
            }
            outcome.directHost?.let { host -> updateSshConfig { it.copy(lastGoodDirectHost = host) } }
            buildString {
                append("✅ ${displayName} → ${outcome.remotePath}（${HttpFileServer.humanSize(outcome.bytes)}）")
                if (outcome.skippedAlreadyComplete) {
                    append("\n　远端本来就有同长度文件，这一轮没重传")
                    append("\n　（如果下面这个 sha256 和服务器上的对不上，就先删掉远端那个文件再传一次）")
                }
                if (outcome.attempts > 1) append("\n　中间断了，自动重连续传成功（第 ${outcome.attempts} 次尝试）")
                if (outcome.resumedFrom > 0 && !outcome.skippedAlreadyComplete) {
                    append("\n　其中 ${HttpFileServer.humanSize(outcome.resumedFrom)} 是之前传上去的，这次没重传")
                }
                append("\n　sha256=${outcome.sha256}")
                append("\n　（在服务器上 sha256sum 同一文件，两个值应完全一致）")
                append("\n　· ${outcome.label}")
            }
        } catch (e: Exception) {
            onFailure?.invoke()
            "❌ ${displayName}：${SshErrors.describe(e)}"
        }
    }

    fun retryFailedUploads() {
        val failed = _failedUploads.value
        if (failed.isNotEmpty()) uploadUris(failed)
    }

    // ------------------------------------------- ④ 从服务器下载（同一条通道，方向相反）

    data class RemoteEntryUi(val name: String, val size: Long, val dir: Boolean, val path: String)

    data class DownloadUiState(
        val running: Boolean = false,
        val fileName: String = "",
        val phase: String = "",
        val done: Long = 0,
        val total: Long = -1,
        val speedKbps: Long = 0,
    )

    private val _remoteEntries = MutableStateFlow<List<RemoteEntryUi>>(emptyList())
    val remoteEntries: StateFlow<List<RemoteEntryUi>> = _remoteEntries.asStateFlow()

    private val _download = MutableStateFlow(DownloadUiState())
    val download: StateFlow<DownloadUiState> = _download.asStateFlow()

    /** 是否成功列过一次远端目录 —— 用来区分"还没点过列出"和"列出来是空的"（后者才显示插画） */
    private val _remoteListed = MutableStateFlow(false)
    val remoteListed: StateFlow<Boolean> = _remoteListed.asStateFlow()

    private val _downloadStatus = MutableStateFlow("")
    val downloadStatus: StateFlow<String> = _downloadStatus.asStateFlow()

    private val downloadGuard = AtomicBoolean(false)

    /**
     * 列远端目录（[dir] 为空则用配置里记住的浏览目录）。
     *
     * 2026-11-13 起是"缓存优先"：先把上次列过的结果**立刻**画出来（0ms），
     * 再在后台带 `If-None-Match` 核对一次——服务端没变回 304（没有 body），界面不动；
     * 变了才替换。这样进目录不用干等一个来回（隧道 RTT 实测 257–290ms，手机上更久）。
     */
    fun listRemoteDir(dir: String? = null) {
        if (_download.value.running) return
        val target = (dir ?: _sshConfig.value.remoteBrowseDir).trim()
        if (target.isEmpty()) {
            _downloadStatus.value = "远端目录不能为空"
            return
        }
        updateSshConfig { it.copy(remoteBrowseDir = target) }

        val cached = DirCache.get(appContext, target)
        if (cached != null) {
            _remoteListed.value = true
            _remoteEntries.value = cached.entries.map {
                RemoteEntryUi(it.name, it.size, it.dir, joinRemotePath(target, it.name))
            }
            _downloadStatus.value = "$target —— 缓存（${agoText(cached.at)}），正在核对…"
        } else {
            _downloadStatus.value = "列目录中… $target"
        }

        viewModelScope.launch {
            try {
                when (val res = sshRepository.listDirResult(_sshConfig.value, target, cached?.etag)) {
                    DirList.Unchanged -> {
                        _remoteListed.value = true
                        _downloadStatus.value = summarizeDir(target, cached?.entries ?: emptyList()) + "（没有变化）"
                    }
                    is DirList.Changed -> {
                        DirCache.put(appContext, target, res.etag, res.entries)
                        _remoteListed.value = true
                        _remoteEntries.value = res.entries.map {
                            RemoteEntryUi(it.name, it.size, it.dir, joinRemotePath(target, it.name))
                        }
                        _downloadStatus.value = summarizeDir(target, res.entries) + if (cached != null) "（已更新）" else ""
                    }
                }
            } catch (e: Exception) {
                if (cached == null) {
                    _remoteEntries.value = emptyList()
                    _downloadStatus.value = "❌ ${SshErrors.describe(e)}"
                } else {
                    // 拉不动就保留已经画出来的缓存，别把界面清空
                    _downloadStatus.value = "$target —— 显示缓存；刷新失败：${SshErrors.describe(e)}"
                }
            }
        }
    }

    private fun summarizeDir(target: String, entries: List<RemoteEntry>): String {
        val dirs = entries.count { it.dir }
        val files = entries.size - dirs
        return buildString {
            append("$target —— $dirs 个目录 / $files 个文件")
            if (files > 0) append("（点文件名右边的「下载」）")
            if (target != "/") append("（点「上级」回上一层）")
        }
    }

    private fun agoText(at: Long): String {
        val ms = System.currentTimeMillis() - at
        return when {
            at <= 0 -> "时间未知"
            ms < 60_000 -> "刚刚"
            ms < 3_600_000 -> "${ms / 60_000} 分钟前"
            ms < 86_400_000 -> "${ms / 3_600_000} 小时前"
            else -> "${ms / 86_400_000} 天前"
        }
    }

    // ------------------------------------------- ④ 搜索（按文件名，整个 /home/user）

    /**
     * 搜索走 HTTP 通道，**不依赖当前传输路线**：
     * SFTP 那把 key 只允许 sftp 子系统，跑不了 find；而 HTTP 接口就是一条查询。
     */
    private val _searchHits = MutableStateFlow<List<RemoteEntryUi>>(emptyList())
    val searchHits: StateFlow<List<RemoteEntryUi>> = _searchHits.asStateFlow()

    private val _searchStatus = MutableStateFlow("")
    val searchStatus: StateFlow<String> = _searchStatus.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun searchRemote(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            _searchHits.value = emptyList()
            _searchStatus.value = "先输入要搜的文件名（支持部分匹配）"
            return
        }
        val cfg = _sshConfig.value
        _searching.value = true
        _searchStatus.value = "搜索中…「$q」（服务器上跑 find，最深 6 层）"
        viewModelScope.launch {
            try {
                val hits = withContext(Dispatchers.IO) {
                    HttpTransferClient.search(cfg.httpBaseUrl, cfg.httpDeviceId, cfg.httpToken, cfg.remoteBrowseDir, q, 100)
                }
                _searchHits.value = hits.map { RemoteEntryUi(it.name, it.size, it.dir, it.path) }
                val dirs = hits.count { it.dir }
                _searchStatus.value = buildString {
                    append("「$q」命中 ${hits.size} 项")
                    if (hits.isNotEmpty()) append("（$dirs 个目录 / ${hits.size - dirs} 个文件）")
                    append("；目录点「进入」，文件点「下载」")
                }
            } catch (e: Exception) {
                _searchHits.value = emptyList()
                _searchStatus.value = "❌ 搜索失败：${SshErrors.describe(e)}"
            } finally {
                _searching.value = false
            }
        }
    }

    fun clearSearch() {
        _searchHits.value = emptyList()
        _searchStatus.value = ""
    }

    fun openRemoteDir(path: String) = listRemoteDir(path)

    fun remoteParentDir() = listRemoteDir(parentOf(_sshConfig.value.remoteBrowseDir))

    /** 下载一个远端文件：断了自动重连、从断点续传，落在 App 的下载目录（⑤ 卡片里能分享/打开）。 */
    fun downloadRemote(entry: RemoteEntryUi) {
        if (entry.dir || _download.value.running) return
        if (!downloadGuard.compareAndSet(false, true)) {
            _downloadStatus.value = "上一次下载还在跑，等它结束再点"
            return
        }
        viewModelScope.launch {
            var startedAt = System.currentTimeMillis()
            var startDone = -1L
            try {
                _download.value = DownloadUiState(running = true, fileName = entry.name, total = entry.size)
                val result = sshRepository.downloadResilient(
                    config = _sshConfig.value,
                    remotePath = entry.path,
                    destDir = repository.downloadDir,
                    onPhase = { phase ->
                        startedAt = System.currentTimeMillis()
                        startDone = -1L
                        _download.value = _download.value.copy(phase = phase, speedKbps = 0)
                    },
                ) { done, total ->
                    if (startDone < 0) {
                        startDone = done
                        startedAt = System.currentTimeMillis()
                    }
                    val secs = ((System.currentTimeMillis() - startedAt).coerceAtLeast(1)) / 1000.0
                    _download.value = _download.value.copy(
                        done = done, total = total,
                        speedKbps = ((done - startDone) / 1024.0 / secs).toLong().coerceAtLeast(0L),
                    )
                }
                result.directHost?.let { host -> updateSshConfig { it.copy(lastGoodDirectHost = host) } }
                // APK 额外另存一份到公共「下载」目录：否则文件管理器看不见，没法手动安装
                val exported = if (result.file.name.endsWith(".apk", ignoreCase = true)) {
                    withContext(Dispatchers.IO) { PublicDownloads.exportApk(appContext, result.file) }
                } else {
                    null
                }
                repository.refresh() // 让 ⑤ 卡片立刻显示新文件
                _downloadStatus.value = buildString {
                    append("✅ ${entry.name}（${HttpFileServer.humanSize(result.bytes)}）已下到 App 的下载目录")
                    if (result.attempts > 1) append("\n　中间断了，自动重连续传成功（第 ${result.attempts} 次尝试）")
                    if (result.resumedFrom > 0) append("\n　其中 ${HttpFileServer.humanSize(result.resumedFrom)} 是之前下的")
                    if (exported != null) append("\n　也存了一份到 $exported（文件管理器里直接点就能装）")
                    append("\n　· ${result.label}")
                }
            } catch (e: Exception) {
                _downloadStatus.value = "❌ ${entry.name}：${SshErrors.describe(e)}"
            } finally {
                _download.value = DownloadUiState()
                downloadGuard.set(false)
            }
        }
    }

    // ------------------------------------------- ⑥ 上传卡片的目录浏览（和 ④ 各看各的目录）

    /**
     * 上传目标目录的列表。为什么和 ④ 分开存：两张卡浏览的是**不同**的目录
     * （上传钉在 remoteDir、下载从 remoteBrowseDir 起），共用一个 flow 会让一边把另一边覆盖掉。
     */
    private val _uploadDirEntries = MutableStateFlow<List<RemoteEntryUi>>(emptyList())
    val uploadDirEntries: StateFlow<List<RemoteEntryUi>> = _uploadDirEntries.asStateFlow()

    /** 是否成功列过一次上传目录 —— 区分"还没点过列出"和"列出来是空的"。 */
    private val _uploadDirListed = MutableStateFlow(false)
    val uploadDirListed: StateFlow<Boolean> = _uploadDirListed.asStateFlow()

    private val _uploadDirStatus = MutableStateFlow("")
    val uploadDirStatus: StateFlow<String> = _uploadDirStatus.asStateFlow()

    /** 列上传目标目录（⑥ 卡片的「远程目录」）。[dir] 为空则用配置里记住的上传目录。同样缓存优先。 */
    fun listUploadDir(dir: String? = null) {
        if (_upload.value.running) return
        val target = (dir ?: _sshConfig.value.remoteDir).trim()
        if (target.isEmpty()) {
            _uploadDirStatus.value = "远程目录不能为空"
            return
        }
        updateSshConfig { it.copy(remoteDir = target) }

        val cached = DirCache.get(appContext, target)
        if (cached != null) {
            _uploadDirListed.value = true
            _uploadDirEntries.value = cached.entries.map {
                RemoteEntryUi(it.name, it.size, it.dir, joinRemotePath(target, it.name))
            }
            _uploadDirStatus.value = "$target —— 缓存（${agoText(cached.at)}），正在核对…"
        } else {
            _uploadDirStatus.value = "列目录中… $target"
        }

        viewModelScope.launch {
            try {
                when (val res = sshRepository.listDirResult(_sshConfig.value, target, cached?.etag)) {
                    DirList.Unchanged -> {
                        _uploadDirListed.value = true
                        _uploadDirStatus.value = summarizeUploadDir(target, cached?.entries ?: emptyList()) + "（没有变化）"
                    }
                    is DirList.Changed -> {
                        DirCache.put(appContext, target, res.etag, res.entries)
                        _uploadDirListed.value = true
                        _uploadDirEntries.value = res.entries.map {
                            RemoteEntryUi(it.name, it.size, it.dir, joinRemotePath(target, it.name))
                        }
                        _uploadDirStatus.value = summarizeUploadDir(target, res.entries) + if (cached != null) "（已更新）" else ""
                    }
                }
            } catch (e: Exception) {
                if (cached == null) {
                    _uploadDirEntries.value = emptyList()
                    _uploadDirStatus.value = "❌ ${SshErrors.describe(e)}"
                } else {
                    _uploadDirStatus.value = "$target —— 显示缓存；刷新失败：${SshErrors.describe(e)}"
                }
            }
        }
    }

    private fun summarizeUploadDir(target: String, entries: List<RemoteEntry>): String {
        val dirs = entries.count { it.dir }
        val files = entries.size - dirs
        return buildString {
            append("$target —— $dirs 个目录 / $files 个文件")
            if (target != "/") append("（点「上级」回上一层）")
        }
    }

    fun openUploadDir(path: String) = listUploadDir(path)

    fun uploadParentDir() = listUploadDir(parentOf(_sshConfig.value.remoteDir))

    override fun onCleared() {
        repository.close()
        sshRepository.invalidateConnection()
    }

    companion object {
        /**
         * 显式工厂。
         *
         * 刻意不依赖两样东西：ViewModelStoreOwner 的默认工厂、以及 CreationExtras 里有没有
         * Application —— 在 Navigation3 的 entry 下这两点没实机无法确认，绕开最省事。
         * Context 从 Compose 的 LocalContext 拿，那个必然可用。
         */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainScreenViewModel(context.applicationContext) as T
            }
    }
}

/** 拼远端路径：避免配成 `/home/user/` 时出现 `//`。 */
internal fun joinRemotePath(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

/**
 * 上一级目录；已经在根目录就返回根。
 * 单独拎出来是因为它容易写错（`/a` → `/`、`/` → `/`、相对路径 → 原样），
 * 而它错了会让「上级」按钮跳到莫名其妙的地方 —— 有单元测试兜着。
 */
internal fun parentOf(dir: String): String {
    val d = dir.trimEnd('/')
    val cut = d.lastIndexOf('/')
    return when {
        cut < 0 -> if (d.isEmpty()) "/" else d
        cut == 0 -> "/"
        else -> d.substring(0, cut)
    }
}
