package dev.example.journal.ui.main

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.example.journal.BuildConfig
import dev.example.journal.R
import dev.example.journal.data.ArchiveFilter
import dev.example.journal.data.Formatters
import dev.example.journal.data.SshConfig
import dev.example.journal.data.SshRoute
import dev.example.journal.data.TodoIndex
import dev.example.journal.data.TodoItem
import dev.example.journal.theme.JournalTheme
import java.time.LocalDate

@Composable
private fun rememberJournalViewModel(): JournalViewModel {
    val context = LocalContext.current
    val factory = remember(context) { JournalViewModel.factory(context) }
    return viewModel(factory = factory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    modifier: Modifier = Modifier,
    viewModel: JournalViewModel = rememberJournalViewModel(),
) {
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val sshStatus by viewModel.sshStatus.collectAsStateWithLifecycle()
    val connecting by viewModel.connecting.collectAsStateWithLifecycle()
    val publicKeyLine by viewModel.publicKeyLine.collectAsStateWithLifecycle()
    val archiveUpload by viewModel.archiveUpload.collectAsStateWithLifecycle()
    val archiveStatus by viewModel.archiveStatus.collectAsStateWithLifecycle()
    val archiveRejected by viewModel.archiveRejected.collectAsStateWithLifecycle()
    val archiveTrigger by viewModel.archiveTrigger.collectAsStateWithLifecycle()
    val todosFrom by viewModel.todosFrom.collectAsStateWithLifecycle()
    val todosTo by viewModel.todosTo.collectAsStateWithLifecycle()
    val todosActiveOnly by viewModel.todosActiveOnly.collectAsStateWithLifecycle()
    val todosAll by viewModel.todosAll.collectAsStateWithLifecycle()
    val todosLoading by viewModel.todosLoading.collectAsStateWithLifecycle()
    val todosStatus by viewModel.todosStatus.collectAsStateWithLifecycle()
    val todosFetched by viewModel.todosFetched.collectAsStateWithLifecycle()
    val pendingShare by ShareInbox.pending.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 存档选择器刻意用 */*：白名单在 App 内判（SAF 常把 .json/.md 标成
    // application/octet-stream，用 MIME 过滤会让这些文件根本选不到），被拒的会红字列出来
    val pickArchiveFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.archiveUris(uris)
    }

    // 分享进来的东西：进存档（文件按白名单筛、文字直接落档）
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) viewModel.handlePendingShare()
    }

    var textDraft by remember { mutableStateOf("") }
    var textName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("日志", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        JournalContent(
            sshConfig = sshConfig,
            sshStatus = sshStatus,
            connecting = connecting,
            archiveUpload = archiveUpload,
            archiveStatus = archiveStatus,
            archiveRejected = archiveRejected,
            archiveTrigger = archiveTrigger,
            todosFrom = todosFrom,
            todosTo = todosTo,
            todosActiveOnly = todosActiveOnly,
            todosAll = todosAll,
            todosLoading = todosLoading,
            todosFetched = todosFetched,
            todosStatus = todosStatus,
            textDraft = textDraft,
            textName = textName,
            onTextDraftChange = { textDraft = it },
            onTextNameChange = { textName = it },
            onConfigChange = viewModel::updateSshConfig,
            onTestConnection = viewModel::testConnection,
            onShowPublicKey = viewModel::showPublicKey,
            onHidePublicKey = viewModel::hidePublicKey,
            onPickArchiveFiles = { pickArchiveFiles.launch(arrayOf("*/*")) },
            onArchiveText = { name ->
                viewModel.archiveText(textDraft, name)
                textDraft = ""
                textName = ""
            },
            onRetryArchiveTrigger = viewModel::retryArchiveTrigger,
            onSetFrom = viewModel::setTodosFrom,
            onSetTo = viewModel::setTodosTo,
            onSetActiveOnly = viewModel::setTodosActiveOnly,
            onQuickRange = viewModel::quickRange,
            onLoadTodos = viewModel::loadTodos,
            onCopy = { label, text ->
                copyToClipboard(context, label, text)
                scope.launch { snackbarHostState.showSnackbar("已复制") }
            },
            publicKeyLine = publicKeyLine,
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalContent(
    sshConfig: SshConfig,
    sshStatus: String,
    connecting: Boolean,
    archiveUpload: JournalViewModel.UploadUiState,
    archiveStatus: String,
    archiveRejected: List<String>,
    archiveTrigger: JournalViewModel.ArchiveTriggerUi,
    todosFrom: String,
    todosTo: String,
    todosActiveOnly: Boolean,
    todosAll: List<TodoItem>,
    todosLoading: Boolean,
    todosFetched: Boolean,
    todosStatus: String,
    textDraft: String,
    textName: String,
    onTextDraftChange: (String) -> Unit,
    onTextNameChange: (String) -> Unit,
    onConfigChange: ((SshConfig) -> SshConfig) -> Unit,
    onTestConnection: () -> Unit,
    onShowPublicKey: () -> Unit,
    onHidePublicKey: () -> Unit,
    onPickArchiveFiles: () -> Unit,
    onArchiveText: (String) -> Unit,
    onRetryArchiveTrigger: () -> Unit,
    onSetFrom: (String) -> Unit,
    onSetTo: (String) -> Unit,
    onSetActiveOnly: (Boolean) -> Unit,
    onQuickRange: (JournalViewModel.QuickRange) -> Unit,
    onLoadTodos: () -> Unit,
    onCopy: (String, String) -> Unit,
    publicKeyLine: String,
    modifier: Modifier = Modifier,
) {
    val visible = remember(todosAll, todosFrom, todosTo, todosActiveOnly) {
        TodoIndex.inRange(todosAll, todosFrom, todosTo, todosActiveOnly)
    }
    val busy = archiveUpload.running || connecting

    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ─────────────────────────────────────────── ① 连接
        SectionCard("① 连接（走哪条路 / 我的公钥）", initiallyExpanded = true) {
            Text("用户名", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sshConfig.user,
                onValueChange = { v -> onConfigChange { it.copy(user = v.trim()) } },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            RoutePicker(sshConfig.route, enabled = !busy) { route ->
                onConfigChange { it.copy(route = route.id) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                when (SshRoute.of(sshConfig.route)) {
                    SshRoute.AUTO ->
                        "自动：有公网 IPv6 先直连（最快），没有就直接走 HTTP；传的过程中太慢会在两者间换。"
                    SshRoute.DIRECT -> "始终直连 IPv6；不通就报错，不会偷偷换路。"
                    SshRoute.HTTP -> "始终走 trans.example.com 的 HTTP 通道（没有 IPv6 也能用），支持断点续传。"
                    SshRoute.TUNNEL -> "始终走 Cloudflare 隧道里的 SSH-over-WebSocket；大文件会被 QUIC 那一跳弄坏，只适合小操作。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Text("HTTP 通道地址", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sshConfig.httpBaseUrl,
                onValueChange = { v -> onConfigChange { it.copy(httpBaseUrl = v.trim()) } },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("设备 ID / 设备密钥（服务器上 trans-http-device list 里这台）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sshConfig.httpDeviceId,
                onValueChange = { v -> onConfigChange { it.copy(httpDeviceId = v.trim()) } },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sshConfig.httpToken,
                onValueChange = { v -> onConfigChange { it.copy(httpToken = v.trim()) } },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("直连地址（IPv6 字面量，留空 = 自动发现）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = sshConfig.directHostOverride,
                onValueChange = { v -> onConfigChange { it.copy(directHostOverride = v.trim()) } },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sshConfig.conservative,
                    onCheckedChange = { v -> onConfigChange { it.copy(conservative = v) } },
                    enabled = !busy,
                )
                Text("保守传输模式（大文件出 MAC 错时再开）", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, onClick = onTestConnection) { Text(if (connecting) "测试中…" else "测试连接") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(enabled = !busy, onClick = onShowPublicKey) { Text("我的公钥") }
            }
            if (sshStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(sshStatus, StatusLevel.Info)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "存档走 SFTP 或 HTTP（有 IPv6 直连优先，没有就走 trans.example.com），应用不监听任何端口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // ─────────────────────────────────────────── ② 上传存档
        SectionCard("② 上传存档（只收文本和图片）") {
            Text(
                "选中的东西会传到服务器 ${sshConfig.archiveDirForDisplay}，整批传成功后自动让服务端归档" +
                    "（OCR / 抽待办都在服务器那边做）。别的 App 分享过来的文件/文字也走这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !busy, onClick = onPickArchiveFiles) { Text("选择文本/图片存档") }
            }
            if (archiveRejected.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                StatusLine(ArchiveFilter.rejectionText(archiveRejected), StatusLevel.Error)
            }
            if (archiveUpload.running) {
                Spacer(Modifier.height(8.dp))
                UploadProgress(archiveUpload)
            }
            if (archiveStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(archiveStatus, StatusLevel.Success)
            }
            if (archiveTrigger.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                StatusLine(
                    archiveTrigger.message,
                    if (archiveTrigger.failed) StatusLevel.Error else StatusLevel.Success,
                )
                if (archiveTrigger.failed) {
                    TextButton(enabled = !busy, onClick = onRetryArchiveTrigger) { Text("重试触发归档") }
                } else {
                    Text(
                        "归档是服务端后台做的，过一会儿点 ③ 的「查询待办」就能看到新条目。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            TextComposer(
                title = "文本存档",
                hint = "把一段文本直接存进服务器（剪贴板、临时笔记、聊天里的一段话都行）。",
                draft = textDraft,
                onDraftChange = onTextDraftChange,
                name = textName,
                onNameChange = onTextNameChange,
                enabled = !busy,
                actionLabel = "存档",
                onAction = { onArchiveText(textName) },
            )
        }

        // ─────────────────────────────────────────── ③ 待办（某日 ~ 某日）
        SectionCard("③ 待办（某日 ~ 某日）") {
            // 第一次展开这张卡时自动查一次：省掉一次点击，也不至于打开 App 就连网
            LaunchedEffect(Unit) {
                if (!todosFetched) onLoadTodos()
            }
            Text(
                "日期区间是**重叠**判断：一条 10-14 → 10-15 的事项，查 10-15 ~ 10-18 也会出现（那天还要做）。" +
                    "标记：☐ 未办结 / ✅ 已完成 / ❌ 已取消。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JournalViewModel.QuickRange.entries.forEach { range ->
                    OutlinedButton(
                        enabled = !todosLoading,
                        onClick = { onQuickRange(range) },
                    ) { Text(range.label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DateField(
                    label = "从",
                    value = todosFrom,
                    enabled = !todosLoading,
                    onValueChange = onSetFrom,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                DateField(
                    label = "到",
                    value = todosTo,
                    enabled = !todosLoading,
                    onValueChange = onSetTo,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = todosActiveOnly,
                    onCheckedChange = onSetActiveOnly,
                    enabled = !todosLoading,
                )
                Text("只看未办结", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !todosLoading, onClick = onLoadTodos) {
                    Text(if (todosLoading) "读取中…" else "查询待办")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = visible.isNotEmpty(),
                    onClick = { onCopy("待办", TodoIndex.renderText(visible, todosFrom, todosTo, todosActiveOnly)) },
                ) { Text("复制这批") }
            }
            if (todosStatus.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(todosStatus, StatusLevel.Info)
            }
            if (todosLoading) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            if (todosFetched) {
                Text(
                    Formatters.rangeText(todosFrom, todosTo, todosActiveOnly) + "：" + TodoIndex.summary(visible),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (visible.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                EmptyState(
                    iconRes = R.drawable.ic_event_note,
                    title = "这个区间没有待办",
                    hint = "换个大一点的区间，或者先上传一份材料让服务端抽一遍。",
                )
                } else {
                    Spacer(Modifier.height(6.dp))
                    TodoList(visible)
                }
            }
        }

        Text(
            "日志 v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
    }

    if (publicKeyLine.isNotBlank()) {
        PublicKeyDialog(
            line = publicKeyLine,
            onDismiss = onHidePublicKey,
            onCopy = { onCopy("公钥行", it) },
        )
    }
}

/** 待办列表：按开始日期分组，一条一件事。 */
@Composable
private fun TodoList(items: List<TodoItem>) {
    val shown = remember(items) { items.take(200) }
    val groups = remember(shown) { TodoIndex.groupByDate(shown) }
    groups.forEach { (date, group) ->
        Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                Formatters.shortDate(date),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            group.forEach { item ->
                TodoRow(item)
            }
        }
    }
    if (items.size > 200) {
        Text("（只显示了前 200 项，区间收小一点看全）", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    val (stateIconRes, stateTint, stateDesc) = when {
        item.isDone -> Triple(R.drawable.ic_check_circle, MaterialTheme.colorScheme.primary, "已完成")
        item.isCancelled -> Triple(R.drawable.ic_radio_unchecked, MaterialTheme.colorScheme.error, "已取消")
        else -> Triple(R.drawable.ic_radio_unchecked, MaterialTheme.colorScheme.outline, "未办结")
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Icon(
            painter = painterResource(stateIconRes),
            contentDescription = stateDesc,
            tint = stateTint,
            modifier = Modifier.size(22.dp).padding(top = 1.dp, end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = item.task,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.isCancelled) TextDecoration.LineThrough else null,
            )
            if (item.dateSpanText.isNotEmpty()) {
                Text(item.dateSpanText, style = MaterialTheme.typography.bodySmall)
            }
            val meta = buildList {
                if (item.passes > 0) add("抽取一致度 ${item.votes}/${item.passes} 路")
                if (item.sourceName.isNotBlank()) add("来源 ${item.sourceName}")
            }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.isDone || item.isCancelled) {
                val verb = if (item.isDone) "已完成" else "已取消"
                val by = item.closedBy.ifBlank { "（服务端没记来源）" }
                Text("$verb · $by", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            if (item.wasReopened) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sync),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "后来又被恢复（${item.reopenedBy}）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            if (item.evidence.isNotBlank()) {
                Text(
                    "依据：${item.evidence}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            item.warnings.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        w,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    HorizontalDivider()
}

/** 日期输入：左边是文本框（可以直接改），右边一个按钮弹系统日历。 */
@Composable
private fun DateField(
    label: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        IconButton(enabled = enabled, onClick = { pickDate(context, value, onValueChange) }) {
            Icon(painter = painterResource(R.drawable.ic_calendar), contentDescription = "选择日期")
        }
    }
}

private fun pickDate(context: Context, current: String, onPicked: (String) -> Unit) {
    val initial = TodoIndex.validDate(current) ?: LocalDate.now()
    DatePickerDialog(
        context,
        { _, year, month, day ->
            onPicked(LocalDate.of(year, month + 1, day).toString())
        },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

@Composable
private fun RoutePicker(route: String, enabled: Boolean, onChange: (SshRoute) -> Unit) {
    val current = SshRoute.of(route)
    Text("走哪条路", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SshRoute.entries.forEach { option ->
            if (option == current) {
                Button(enabled = enabled, onClick = { onChange(option) }) { Text(option.label) }
            } else {
                OutlinedButton(enabled = enabled, onClick = { onChange(option) }) { Text(option.label) }
            }
        }
    }
}

/** 存档卡片的上传进度行：第 i/n 个、文件名、百分比、速度、阶段文字。 */
@Composable
private fun UploadProgress(upload: JournalViewModel.UploadUiState) {
    val pct = if (upload.total > 0) (upload.done * 100 / upload.total).toInt() else -1
    Text(
        buildString {
            append(upload.phase.ifBlank { "上传中" })
            // count == 0 是"还在筛类型/准备"，这时没有第 i/n 个可言
            if (upload.count > 0) append(" ${upload.index}/${upload.count}：${upload.fileName}")
            if (pct >= 0) {
                append("  $pct%（${Formatters.humanSize(upload.done)} / ${Formatters.humanSize(upload.total)}）")
            } else {
                append("  ${Formatters.humanSize(upload.done)}")
            }
            if (upload.speedKbps > 0) append("  · ${upload.speedKbps} KB/s")
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    if (pct >= 0) {
        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** 文本输入块：文本存档用（和传输助手那张卡的交互一致，只是目标钉死在 archiveDir）。 */
@Composable
private fun TextComposer(
    title: String,
    hint: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    name: String,
    onNameChange: (String) -> Unit,
    enabled: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    val context = LocalContext.current
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        label = { Text("要存档的文本") },
        enabled = enabled,
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("文件名（留空自动起名）") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(enabled = enabled && draft.isNotBlank(), onClick = onAction) { Text(actionLabel) }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(
        enabled = enabled,
        onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
                .getOrNull()
            if (!clip.isNullOrBlank()) onDraftChange(clip)
        },
    ) { Text("粘贴剪贴板到文本框") }
}

/**
 * 可收起的选项卡。点标题（整行都可点，role=Button）在展开/收起之间切换；
 * 收起时只剩标题这一行，内容完全不参与布局。animateContentSize 让展开/收起平滑。
 */
@Composable
private fun SectionCard(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = { expanded = !expanded })
                    .semantics { stateDescription = if (expanded) "已展开" else "已收起" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }
}

/** 状态行的语义级别：决定左侧图标与配色。 */
private enum class StatusLevel { Info, Success, Error }

/**
 * 统一状态行：左侧 16-18dp 图标 + 右侧正文，颜色随级别变化。
 * 不截断：路径 / 文件名 / sha 这类信息原样完整展示。
 */
@Composable
private fun StatusLine(text: String, level: StatusLevel, modifier: Modifier = Modifier) {
    val (iconRes, color) = when (level) {
        StatusLevel.Success -> R.drawable.ic_check_circle to MaterialTheme.colorScheme.primary
        StatusLevel.Error -> R.drawable.ic_warning to MaterialTheme.colorScheme.error
        StatusLevel.Info -> R.drawable.ic_info to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(painter = painterResource(iconRes), contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 空状态：primaryContainer 圆形底 + 大图标 + 标题 + 灰色提示，居中。
 * 用矢量图标而非位图，深色模式下也清晰。
 */
@Composable
private fun EmptyState(iconRes: Int, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun PublicKeyDialog(line: String, onDismiss: () -> Unit, onCopy: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把这行加到服务器") },
        text = {
            Column {
                Text("在服务器上执行（先备份再追加）：", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak\n" +
                        "cat >> ~/.ssh/authorized_keys <<'EOF'\n$line\nEOF",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "这行是 restrict + command= 指向归档网关：这条 key 只能 SFTP（传存档、读待办索引）" +
                        "和发一个固定字符串 archive-trigger —— 没有 shell、没有 pty、**没有任何端口转发权限**。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onCopy(line) }) { Text("复制公钥行") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Preview(showBackground = true, widthDp = 380, heightDp = 1000)
@Composable
private fun JournalContentPreview() {
    JournalTheme {
        JournalContent(
            sshConfig = SshConfig(),
            sshStatus = "✅ 直连 2001:db8::1（3.1s）\n存档收件箱：/home/user/phone-archive/raw —— 在\n待办索引：/home/user/phone-archive/todos/index.jsonl —— 在",
            connecting = false,
            archiveUpload = JournalViewModel.UploadUiState(
                running = true, fileName = "笔记.md", phase = "上传中",
                done = 8192, total = 20480, speedKbps = 340, index = 1, count = 2,
            ),
            archiveStatus = "✅ 笔记.md → /home/user/phone-archive/raw/笔记.md（20.0 KB）\n　sha256=…",
            archiveRejected = listOf("录屏.mp4"),
            archiveTrigger = JournalViewModel.ArchiveTriggerUi(message = "✅ 已触发归档：QUEUED 已开始归档，服务器稍后会在 archives 下出现结果"),
            todosFrom = "2026-10-15",
            todosTo = "2026-10-21",
            todosActiveOnly = false,
            todosAll = listOf(
                TodoItem(
                    id = 160, task = "参加使用生成式AI加速MATLAB/Simulink模型设计培训",
                    startDate = "2026-10-15", endDate = "2026-10-15", state = "active",
                    evidence = "10/15 使用生成式AI加速 MATLAB/Simulink 模型设计 16:00-17:00 腾讯会议-会议号：681-266-827",
                    sourceName = "Screenshot_2026-10-14-21-33-56-26_4214240bacdb49e915f21ad227d7ec92.jpg",
                    votes = 6, passes = 6,
                ),
                TodoItem(
                    id = 157, task = "报名FPV无人机系统设计与实战课程",
                    startDate = "2026-10-14", endDate = "2026-10-15", state = "done",
                    evidence = "报名表开放时间为10月14日14:00-10月15日23:59",
                    sourceName = "Screenshot_2026-10-14-21-25-17-05_9d26c6446fd7bb8e41d99b6262b17def.jpg",
                    votes = 6, passes = 6, variants = listOf("报名FPV无人机系统设计与实战课程", "报名课程"),
                    closedBy = "text-1792051547040.txt（材料日期 2026-10-15）",
                ),
            ),
            todosLoading = false,
            todosFetched = true,
            todosStatus = "✅ 已读取 12 条（索引更新于 10-15 16:48）",
            textDraft = "明天下午三点交材料",
            textName = "",
            onTextDraftChange = {},
            onTextNameChange = {},
            onConfigChange = {},
            onTestConnection = {},
            onShowPublicKey = {},
            onHidePublicKey = {},
            onPickArchiveFiles = {},
            onArchiveText = {},
            onRetryArchiveTrigger = {},
            onSetFrom = {},
            onSetTo = {},
            onSetActiveOnly = {},
            onQuickRange = {},
            onLoadTodos = {},
            onCopy = { _, _ -> },
            publicKeyLine = "",
            modifier = Modifier.padding(16.dp),
        )
    }
}
