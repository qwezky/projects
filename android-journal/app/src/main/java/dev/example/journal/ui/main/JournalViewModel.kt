package dev.example.journal.ui.main

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
import dev.example.journal.data.ArchiveFilter
import dev.example.journal.data.Formatters
import dev.example.journal.data.SshConfig
import dev.example.journal.data.SshRepository
import dev.example.journal.data.TodoIndex
import dev.example.journal.data.TodoItem
import dev.example.journal.data.UploadOutcome
import dev.example.journal.net.SshErrors
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 别的 App「分享」进来的东西暂存在这里，等界面消费一次。
 *
 * 为什么不让 ViewModel 直接解析 Intent：分享可能发生在界面还没起来的时候（冷启动），
 * 也可能是单例 Activity 收到新 Intent —— 用一个只取一次的收件箱把两条路统一成
 * 「放进来 → 界面上取一次」，比两条路各写一遍清楚。
 */
object ShareInbox {
    data class Payload(val uris: List<Uri>, val text: String?)

    private val _pending = MutableStateFlow<Payload?>(null)
    val pending: StateFlow<Payload?> = _pending.asStateFlow()

    fun put(payload: Payload) {
        _pending.value = payload
    }

    fun consume(): Payload? = _pending.value.also { _pending.value = null }
}

/**
 * 界面状态都在 ViewModel 里，旋转屏幕/配置变化不会丢；网络操作跑在 viewModelScope。
 *
 * 这个 App 只做两组事（卡片 ①②③）：
 *   ① 连接（走哪条路 + 我的公钥）
 *   ② 上传存档：只收文本/图片 → 固定落 `phone-archive/raw` → 整批成功后触发一次服务端归档；
 *      别的 App 分享进来的东西也走这里
 *   ③ 待办：**只读**地把服务端 `todos/index.jsonl` 拉回来，在本地按"某日到某日"筛
 */
class JournalViewModel(context: Context) : ViewModel() {

    private val appContext = context.applicationContext

    private val repository = SshRepository(appContext)

    // ------------------------------------------------- ① 连接

    private val _sshConfig = MutableStateFlow(SshConfig.load(appContext))
    val sshConfig: StateFlow<SshConfig> = _sshConfig.asStateFlow()

    private val _sshStatus = MutableStateFlow("")
    val sshStatus: StateFlow<String> = _sshStatus.asStateFlow()

    private val _publicKeyLine = MutableStateFlow("")
    val publicKeyLine: StateFlow<String> = _publicKeyLine.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    fun updateSshConfig(transform: (SshConfig) -> SshConfig) {
        val before = _sshConfig.value
        val next = transform(before)
        _sshConfig.value = next
        SshConfig.save(appContext, next)
        // 用户改了"走哪条路" → 清掉本次会话里"实测哪条慢"的结论，重新判
        if (next.route != before.route) repository.resetRouteDecision()
    }

    fun showPublicKey() {
        _publicKeyLine.value = runCatching { repository.authorizedKeysLine(_sshConfig.value) }
            .getOrElse { "生成失败：${it.message}" }
    }

    fun hidePublicKey() {
        _publicKeyLine.value = ""
    }

    /** 测试连接：直连优先，失败退回隧道；成功后顺便看一眼两个约定路径在不在。 */
    fun testConnection() {
        if (_connecting.value || _archiveUpload.value.running || _todosLoading.value) return
        _connecting.value = true
        _sshStatus.value = "连接中…（自动：WiFi/有线直连、移动数据走隧道；传的过程中太慢也会换路）"
        viewModelScope.launch {
            try {
                val conn = repository.ensureConnection(_sshConfig.value, force = true)
                if (conn.viaDirect) updateSshConfig { it.copy(lastGoodDirectHost = conn.host) }
                val head = if (conn.viaDirect) {
                    "✅ ${conn.label}（${conn.elapsedMs}ms）"
                } else {
                    "✅ ${conn.label}（${conn.elapsedMs}ms，经 Cloudflare）"
                }
                val archiveLine = runCatching {
                    val dir = _sshConfig.value.archiveDirForDisplay
                    if (conn.client.remoteSize(dir) >= 0) "存档收件箱：$dir —— 在" else "存档收件箱：$dir —— **不存在**（上传时会自动建）"
                }.getOrElse { "存档收件箱：查不动：${SshErrors.describe(it)}" }
                val todoLine = runCatching {
                    val p = _sshConfig.value.todosIndexForDisplay
                    if (conn.client.remoteSize(p) >= 0) "待办索引：$p —— 在" else "待办索引：$p —— **还没有**（服务端还没归档过任何东西？）"
                }.getOrElse { "待办索引：查不动：${SshErrors.describe(it)}" }
                _sshStatus.value = buildString {
                    append(head)
                    append("\n")
                    append(archiveLine)
                    append("\n")
                    append(todoLine)
                    val failures = repository.directFailureSummary()
                    if (failures.isNotEmpty() && !conn.viaDirect) append("\n$failures")
                    if (_sshConfig.value.conservative) append("\n保守传输模式已开")
                }
            } catch (e: Exception) {
                _sshStatus.value = "❌ 连接失败：${SshErrors.describe(e)}\n${repository.directFailureSummary()}"
            } finally {
                _connecting.value = false
            }
        }
    }

    // ------------------------------------------------- ② 上传存档

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

    /** 触发归档这一步的独立状态：上传成功但触发失败时只它变红，不能连带把上传说成失败。 */
    data class ArchiveTriggerUi(
        val running: Boolean = false,
        val failed: Boolean = false,
        val message: String = "",
    )

    private val _archiveUpload = MutableStateFlow(UploadUiState())
    val archiveUpload: StateFlow<UploadUiState> = _archiveUpload.asStateFlow()

    private val _archiveStatus = MutableStateFlow("")
    val archiveStatus: StateFlow<String> = _archiveStatus.asStateFlow()

    /** 被白名单拒掉的文件名。刻意和成功/失败分开：它只是"没传"，不是"传坏了"。 */
    private val _archiveRejected = MutableStateFlow<List<String>>(emptyList())
    val archiveRejected: StateFlow<List<String>> = _archiveRejected.asStateFlow()

    private val _archiveTrigger = MutableStateFlow(ArchiveTriggerUi())
    val archiveTrigger: StateFlow<ArchiveTriggerUi> = _archiveTrigger.asStateFlow()

    private val archiveGuard = AtomicBoolean(false)

    /**
     * 存档一批文件：先在本地按 [ArchiveFilter] 筛类型，通过的走那条韧性上传路径
     * （断点续传/自动重连/选路全保留），目标是固定的 [SshConfig.archiveDir]；
     * **整批都传成功**才触发一次归档。
     */
    fun archiveUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!beginArchive()) return
        viewModelScope.launch {
            try {
                _archiveTrigger.value = ArchiveTriggerUi()
                _archiveUpload.value = UploadUiState(running = true, phase = "正在检查文件类型…")
                val items = withContext(Dispatchers.IO) { uris.map { repository.describe(it) } }
                val accepted = items.filter { ArchiveFilter.isAccepted(it.mime, it.displayName) }
                _archiveRejected.value =
                    items.filterNot { ArchiveFilter.isAccepted(it.mime, it.displayName) }.map { it.displayName }

                if (accepted.isEmpty()) {
                    // 一个都不合格就一个都不传，连 SSH 都不建 —— 免得用户以为是网络问题
                    _archiveStatus.value =
                        "❌ 选中的 ${items.size} 个文件都不在存档区白名单里，一个都没上传"
                    return@launch
                }

                val results = mutableListOf<String>()
                var failed = 0
                accepted.forEachIndexed { index, item ->
                    _archiveUpload.value = UploadUiState(
                        running = true, fileName = item.displayName,
                        done = 0, total = item.size, index = index + 1, count = accepted.size,
                    )
                    results += runOne(
                        _archiveUpload, item.displayName, index + 1, accepted.size, { failed += 1 },
                    ) { onPhase, onProgress ->
                        repository.uploadResilient(
                            _sshConfig.value, item,
                            targetDir = _sshConfig.value.archiveDirForDisplay,
                            onPhase = onPhase, onProgress = onProgress,
                        )
                    }
                }

                finishArchiveBatch(results, failed)
            } catch (e: Exception) {
                _archiveStatus.value = "❌ 存档失败：${SshErrors.describe(e)}"
            } finally {
                _archiveUpload.value = UploadUiState()
                archiveGuard.set(false)
            }
        }
    }

    /**
     * 存档一段文本：和 [archiveUris] 走同一条路 —— 固定落进 `archiveDir`，
     * 传完**同一批**触发一次归档。界面上「文本存档」和别的 App 分享过来的文字都进这里。
     */
    fun archiveText(text: String, fileName: String) {
        val body = text.trim()
        if (body.isEmpty()) {
            _archiveStatus.value = "文本是空的，没东西可存档"
            return
        }
        val name = fileName.trim().ifBlank { "text-${System.currentTimeMillis()}.txt" }
        if (!beginArchive()) return
        viewModelScope.launch {
            try {
                _archiveTrigger.value = ArchiveTriggerUi()
                _archiveRejected.value = emptyList()
                _archiveUpload.value = UploadUiState(running = true, count = 1)
                var failed = 0
                val result = runOne(_archiveUpload, name, 1, 1, { failed += 1 }) { onPhase, onProgress ->
                    repository.uploadText(
                        _sshConfig.value, body, name,
                        targetDir = _sshConfig.value.archiveDirForDisplay,
                        onPhase = onPhase, onProgress = onProgress,
                    )
                }
                finishArchiveBatch(listOf(result), failed)
            } catch (e: Exception) {
                _archiveStatus.value = "❌ 存档失败：${SshErrors.describe(e)}"
            } finally {
                _archiveUpload.value = UploadUiState()
                archiveGuard.set(false)
            }
        }
    }

    /** 抢存档的独占锁；已经在跑就只更新提示，不动别的卡片。 */
    private fun beginArchive(): Boolean {
        if (archiveGuard.compareAndSet(false, true)) return true
        _archiveStatus.value = "上一次存档还在跑，等它结束再点"
        return false
    }

    /**
     * 传一个（文件或文本）：刷进度/速度，并拼出给人看的结果行。
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
                append("✅ ${displayName} → ${outcome.remotePath}（${Formatters.humanSize(outcome.bytes)}）")
                if (outcome.skippedAlreadyComplete) {
                    append("\n　远端本来就有同长度文件，这一轮没重传")
                    append("\n　（如果下面这个 sha256 和服务器上的对不上，就先删掉远端那个文件再传一次）")
                }
                if (outcome.attempts > 1) append("\n　中间断了，自动重连续传成功（第 ${outcome.attempts} 次尝试）")
                if (outcome.resumedFrom > 0 && !outcome.skippedAlreadyComplete) {
                    append("\n　其中 ${Formatters.humanSize(outcome.resumedFrom)} 是之前传上去的，这次没重传")
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

    /**
     * 一批存档上传结束后的收口：**整批都成功**才触发归档。
     * 归档是按"批"处理的，缺一个就不触发，免得服务端归档出一个不完整的批次。
     */
    private suspend fun finishArchiveBatch(results: List<String>, failed: Int) {
        val summary = results.joinToString("\n")
        if (failed > 0) {
            _archiveStatus.value =
                "⚠️ 有 $failed 个没传完，这一批不触发归档（归档按整批处理）\n$summary"
            return
        }
        _archiveStatus.value = summary
        triggerArchive()
    }

    /**
     * 通知服务端归档，并把它回的那行（`QUEUED ...`）显示出来。
     * 失败只标在触发这一步上 —— 文件确实已经在服务器上了。
     */
    private suspend fun triggerArchive() {
        _archiveTrigger.value = ArchiveTriggerUi(running = true)
        _archiveTrigger.value = runCatching { repository.triggerArchive(_sshConfig.value) }
            .fold(
                onSuccess = { out ->
                    ArchiveTriggerUi(message = "✅ 已触发归档：${out.ifBlank { "（服务端没有输出）" }}")
                },
                onFailure = { e ->
                    ArchiveTriggerUi(
                        failed = true,
                        message = "文件已上传，但触发归档失败：${SshErrors.describe(e)}",
                    )
                },
            )
    }

    /** 触发那一步失败后的重试：上传成果都还在服务器上，这里只重发那条命令。 */
    fun retryArchiveTrigger() {
        if (_archiveUpload.value.running || _archiveTrigger.value.running) return
        viewModelScope.launch { triggerArchive() }
    }

    // ------------------------------------------------- ③ 待办（只读）

    private val _todosFrom = MutableStateFlow(Formatters.today().toString())
    val todosFrom: StateFlow<String> = _todosFrom.asStateFlow()

    private val _todosTo = MutableStateFlow(
        Formatters.todayPlus((SshConfig().defaultRangeDays - 1).toLong()),
    )
    val todosTo: StateFlow<String> = _todosTo.asStateFlow()

    init {
        // 初始区间按**存盘里那个** defaultRangeDays 算（上面那个只是字段默认值）
        val days = _sshConfig.value.defaultRangeDays
        if (days > 0) _todosTo.value = Formatters.todayPlus((days - 1).toLong())
    }

    private val _todosActiveOnly = MutableStateFlow(false)
    val todosActiveOnly: StateFlow<Boolean> = _todosActiveOnly.asStateFlow()

    private val _todosLoading = MutableStateFlow(false)
    val todosLoading: StateFlow<Boolean> = _todosLoading.asStateFlow()

    /** 拉回来的**全部**待办（本地筛之前），点"查询"才更新。 */
    private val _todosAll = MutableStateFlow<List<TodoItem>>(emptyList())
    val todosAll: StateFlow<List<TodoItem>> = _todosAll.asStateFlow()

    private val _todosStatus = MutableStateFlow("还没查过 —— 点「查询待办」")
    val todosStatus: StateFlow<String> = _todosStatus.asStateFlow()

    /** 索引文件在服务器上的最后修改时间（`MM-dd HH:mm`），用来解释"刚传的为什么还没出现"。 */
    private val _todosIndexTime = MutableStateFlow("")
    val todosIndexTime: StateFlow<String> = _todosIndexTime.asStateFlow()

    private val _todosFetched = MutableStateFlow(false)
    val todosFetched: StateFlow<Boolean> = _todosFetched.asStateFlow()

    /** 坏行数（解析不了的 JSONL 行）：宁可如实报出来，也不要静默少显示几条。 */
    private val _todosSkipped = MutableStateFlow(0)
    val todosSkipped: StateFlow<Int> = _todosSkipped.asStateFlow()

    fun setTodosFrom(value: String) {
        _todosFrom.value = value.trim()
    }

    fun setTodosTo(value: String) {
        _todosTo.value = value.trim()
    }

    fun setTodosActiveOnly(value: Boolean) {
        _todosActiveOnly.value = value
    }

    /** 快捷区间。注意 `未来` 是从今天起算、`近` 是往前含今天。 */
    fun quickRange(range: QuickRange) {
        val today = LocalDate.now()
        when (range) {
            QuickRange.TODAY -> {
                _todosFrom.value = today.toString()
                _todosTo.value = today.toString()
            }
            QuickRange.LAST_7 -> {
                _todosFrom.value = today.minusDays(6).toString()
                _todosTo.value = today.toString()
            }
            QuickRange.NEXT_7 -> {
                _todosFrom.value = today.toString()
                _todosTo.value = today.plusDays(6).toString()
            }
            QuickRange.THIS_MONTH -> {
                _todosFrom.value = today.withDayOfMonth(1).toString()
                _todosTo.value = today.withDayOfMonth(today.lengthOfMonth()).toString()
            }
            QuickRange.ALL -> {
                _todosFrom.value = ""
                _todosTo.value = ""
            }
        }
        loadTodos()
    }

    enum class QuickRange(val label: String) {
        TODAY("今天"),
        LAST_7("近 7 天"),
        NEXT_7("未来 7 天"),
        THIS_MONTH("本月"),
        ALL("全部"),
    }

    /**
     * 把服务端的待办索引拉回来（**只读一次全量**，之后在本地筛）。
     *
     * 为什么拉全量而不是按区间问服务端：网关只放行 SFTP 与一个字面量命令，
     * 没有"按区间查"的能力；而索引文件本来就不大（一行一件事），
     * 本地筛还顺带让"改区间"变成零网络操作 —— 只有"刷新"才真的连一次。
     */
    fun loadTodos() {
        if (_todosLoading.value) return
        _todosLoading.value = true
        _todosStatus.value = "读取中… ${_sshConfig.value.todosIndexForDisplay}"
        viewModelScope.launch {
            try {
                val remote = repository.readRemoteText(_sshConfig.value, _sshConfig.value.todosIndexForDisplay)
                val parsed = TodoIndex.parse(remote.text)
                _todosAll.value = parsed.items
                _todosSkipped.value = parsed.skippedLines
                _todosIndexTime.value = Formatters.epochSecondsToLocalText(remote.modifiedEpochSeconds)
                _todosFetched.value = true
                _todosStatus.value = buildString {
                    append("✅ 已读取 ${parsed.items.size} 条")
                    if (_todosIndexTime.value.isNotEmpty()) append("（索引更新于 ${_todosIndexTime.value}）")
                    if (parsed.skippedLines > 0) append("，其中 ${parsed.skippedLines} 行解析不了已跳过")
                    append("\n· ${remote.label}")
                }
            } catch (e: Exception) {
                _todosStatus.value = "❌ ${SshErrors.describe(e)}"
            } finally {
                _todosLoading.value = false
            }
        }
    }

    // ------------------------------------------------- 分享进来的东西

    /** 消费一次「分享」进来的内容：一律走存档（文件按白名单筛，文字直接落档）。 */
    fun handlePendingShare(): Boolean {
        val payload = ShareInbox.consume() ?: return false
        return when {
            payload.uris.isNotEmpty() -> {
                _archiveStatus.value = "收到分享：${payload.uris.size} 个文件，开始存档…"
                archiveUris(payload.uris)
                true
            }
            !payload.text.isNullOrBlank() -> {
                archiveText(payload.text, "分享文本-${System.currentTimeMillis()}.txt")
                true
            }
            else -> false
        }
    }

    override fun onCleared() {
        repository.invalidateConnection()
    }

    companion object {
        /**
         * 显式工厂（不依赖 ViewModelStoreOwner 的默认工厂，也不依赖 CreationExtras 里
         * 有没有 Application —— Context 从 Compose 的 LocalContext 拿，那个必然可用）。
         */
        fun factory(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    JournalViewModel(context.applicationContext) as T
            }
    }
}
