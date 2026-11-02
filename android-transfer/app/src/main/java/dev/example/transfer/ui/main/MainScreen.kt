package dev.example.transfer.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.example.transfer.BuildConfig
import dev.example.transfer.R
import dev.example.transfer.data.SshConfig
import dev.example.transfer.data.TransferRoute
import dev.example.transfer.net.HttpFileServer
import dev.example.transfer.theme.TransferDemoTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun rememberMainScreenViewModel(): MainScreenViewModel {
    val context = LocalContext.current
    val factory = remember(context) { MainScreenViewModel.factory(context) }
    return viewModel(factory = factory)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = rememberMainScreenViewModel(),
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sshConfig by viewModel.sshConfig.collectAsStateWithLifecycle()
    val sshStatus by viewModel.sshStatus.collectAsStateWithLifecycle()
    val upload by viewModel.upload.collectAsStateWithLifecycle()
    val uploadStatus by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val failedUploads by viewModel.failedUploads.collectAsStateWithLifecycle()
    val publicKeyLine by viewModel.publicKeyLine.collectAsStateWithLifecycle()
    val remoteEntries by viewModel.remoteEntries.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val downloadStatus by viewModel.downloadStatus.collectAsStateWithLifecycle()
    val remoteListed by viewModel.remoteListed.collectAsStateWithLifecycle()
    val uploadDirEntries by viewModel.uploadDirEntries.collectAsStateWithLifecycle()
    val uploadDirListed by viewModel.uploadDirListed.collectAsStateWithLifecycle()
    val uploadDirStatus by viewModel.uploadDirStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF 多选：不需要任何存储权限
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) viewModel.uploadUris(uris)
    }

    if (publicKeyLine.isNotEmpty()) {
        PublicKeyDialog(
            line = publicKeyLine,
            onDismiss = viewModel::hidePublicKey,
            onCopy = {
                copyToClipboard(context, "authorized_keys", it)
                scope.launch { snackbarHostState.showSnackbar("公钥行已复制") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "传输助手",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "v${BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        TransferContent(
        files = files,
        status = status,
        downloadDirPath = viewModel.downloadDirPath,
        sshConfig = sshConfig,
        sshStatus = sshStatus,
        upload = upload,
        uploadStatus = uploadStatus,
        failedCount = failedUploads.size,
        uploadEntries = uploadDirEntries,
        uploadListed = uploadDirListed,
        uploadDirStatus = uploadDirStatus,
        remoteEntries = remoteEntries,
        download = download,
        downloadStatus = downloadStatus,
        remoteListed = remoteListed,
        onDelete = viewModel::delete,
        onShare = { shareFile(context, it) },
        onOpen = { if (it.name.endsWith(".apk", ignoreCase = true)) installApk(context, it) else openFile(context, it) },
        onSshConfigChange = viewModel::updateSshConfig,
        onTestConnection = viewModel::testSshConnection,
        onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
        onShowPublicKey = viewModel::showPublicKey,
        onRetryFailed = viewModel::retryFailedUploads,
        onUploadText = viewModel::uploadText,
        onListRemote = { viewModel.listRemoteDir() },
        onOpenRemoteDir = viewModel::openRemoteDir,
        onParentDir = viewModel::remoteParentDir,
        onDownloadRemote = viewModel::downloadRemote,
        onListUploadDir = { viewModel.listUploadDir() },
        onOpenUploadDir = viewModel::openUploadDir,
        onUploadParentDir = viewModel::uploadParentDir,
        snackbarHostState = snackbarHostState,
        onSnackbar = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } },
        modifier = modifier.padding(innerPadding),
    )
    }
}

@Composable
private fun TransferContent(
    files: List<File>,
    status: String,
    downloadDirPath: String,
    sshConfig: SshConfig,
    sshStatus: String,
    upload: MainScreenViewModel.UploadUiState,
    uploadStatus: String,
    failedCount: Int,
    uploadEntries: List<MainScreenViewModel.RemoteEntryUi>,
    uploadListed: Boolean,
    uploadDirStatus: String,
    remoteEntries: List<MainScreenViewModel.RemoteEntryUi>,
    download: MainScreenViewModel.DownloadUiState,
    downloadStatus: String,
    remoteListed: Boolean,
    onDelete: (File) -> Unit,
    onShare: (File) -> Unit,
    onOpen: (File) -> Unit,
    onSshConfigChange: ((SshConfig) -> SshConfig) -> Unit,
    onTestConnection: () -> Unit,
    onPickFiles: () -> Unit,
    onShowPublicKey: () -> Unit,
    onRetryFailed: () -> Unit,
    onUploadText: (String, String) -> Unit,
    onListRemote: () -> Unit,
    onOpenRemoteDir: (String) -> Unit,
    onParentDir: () -> Unit,
    onDownloadRemote: (MainScreenViewModel.RemoteEntryUi) -> Unit,
    onListUploadDir: () -> Unit,
    onOpenUploadDir: (String) -> Unit,
    onUploadParentDir: () -> Unit,
    snackbarHostState: SnackbarHostState,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⑤ 上传的文本草稿
    var uploadTextDraft by remember { mutableStateOf("") }
    var uploadTextName by remember { mutableStateOf("") }
    // ③/⑤ 的目录列表默认收起（只留按钮），点「列出」才摊开，摊开后按钮变「收起」
    var downloadListOpen by remember { mutableStateOf(false) }
    var uploadListOpen by remember { mutableStateOf(false) }

    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Text(
            "手机 ↔ 服务器：同一条 IPv6 直连 + 同一把 SSH key（不经 Cloudflare）。" +
                "存档（上传→服务端归档）已经拆成单独的「日志」App，这里只管传文件。",
            style = MaterialTheme.typography.bodySmall,
        )

        // ------------------------------------------------ ① 走哪条路（+ 我的公钥）
        SectionCard(title = "走哪条路（+ 我的公钥）", badge = "①") {
            RoutePicker(
                route = sshConfig.route,
                enabled = !upload.running,
                onChange = { r -> onSshConfigChange { it.copy(route = r.id) } },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OutlinedTextField(
                value = sshConfig.directHostOverride,
                onValueChange = { v -> onSshConfigChange { it.copy(directHostOverride = v.trim()) } },
                label = { Text("直连地址（IPv6 字面量，留空=自动发现）") },
                singleLine = true,
                enabled = !upload.running,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !upload.running, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onShowPublicKey() }) { Text("我的公钥") }
                Spacer(Modifier.width(4.dp))
                Text(
                    "把这把 key 的行加到服务器 authorized_keys（存档网关那行）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // ------------------------------------------------ ② 测试连接
        SectionCard(title = "测试连接", badge = "②") {
            Text(
                "直连优先、失败退回隧道；成功后顺便报一下上传目录在不在。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = sshConfig.user,
                    onValueChange = { v -> onSshConfigChange { it.copy(user = v.trim()) } },
                    label = { Text("用户名") },
                    singleLine = true,
                    enabled = !upload.running,
                    modifier = Modifier.width(130.dp),
                )
                Spacer(Modifier.width(8.dp))
                Button(enabled = !upload.running, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onTestConnection() }) { Text("测试连接") }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = sshConfig.conservative,
                    enabled = !upload.running,
                    onCheckedChange = { v -> onSshConfigChange { it.copy(conservative = v) } },
                )
                Text(
                    "保守传输模式：关掉大窗口/大包/深流水线，全交回 JSch 默认值。\n" +
                        "只在这条链路反复报 MAC 校验失败时用来做 A/B 对照（默认关）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (sshStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(sshStatus)
            }
        }

        // ------------------------------------------------ ③ 从服务器下载
        SectionCard(title = "从服务器下载（SSH/SFTP，取路设置见 ①）", badge = "③") {
            Text(
                "和 ⑤ 同一把 key、同一条 IPv6 直连通道，方向相反：手机主动去服务器拉文件。" +
                    "断了自动重连、从断点续传；下完的文件在 ④ 里，可以「分享」给别的 App 或「打开」。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            GrowingPathField(
                value = sshConfig.remoteBrowseDir,
                onValueChange = { v -> onSshConfigChange { it.copy(remoteBrowseDir = v.trim()) } },
                label = "远端目录（浏览起点）",
                enabled = !download.running,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    enabled = !download.running,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        downloadListOpen = true
                        onListRemote()
                    },
                ) { Text("列出") }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = !download.running, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onParentDir() }) { Text("上级") }
                Spacer(Modifier.width(4.dp))
                // 一次误触改乱浏览起点后，一键回到用户主目录（/home/user）
                TextButton(
                    enabled = !download.running,
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSshConfigChange { it.copy(remoteBrowseDir = SshConfig().remoteBrowseDir) } },
                ) { Text("重置") }
                if (downloadListOpen && (remoteEntries.isNotEmpty() || remoteListed)) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = !download.running,
                        onClick = { downloadListOpen = false },
                    ) { Text("收起") }
                }
            }
            if (download.running) {
                Spacer(Modifier.height(8.dp))
                val pct = if (download.total > 0) (download.done * 100 / download.total).toInt() else -1
                StatusLine(
                    "${download.phase.ifBlank { "下载中" }}：${download.fileName}" +
                        if (pct >= 0) {
                            "  $pct%（${HttpFileServer.humanSize(download.done)} / ${HttpFileServer.humanSize(download.total)}）"
                        } else {
                            "  ${HttpFileServer.humanSize(download.done)}"
                        } +
                        if (download.speedKbps > 0) "  · ${download.speedKbps} KB/s" else "",
                    StatusLevel.INFO,
                )
                Spacer(Modifier.height(4.dp))
                if (pct >= 0) {
                    LinearProgressIndicator(
                        progress = { (download.done.toFloat() / download.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (downloadListOpen) {
                RemoteListing(
                    entries = remoteEntries,
                    listed = remoteListed,
                    busy = download.running,
                    emptyHint = "这个目录是空的 —— 换个目录试试（可以点「上级」回上一层）。",
                    fileActionLabel = "下载",
                    onOpenDir = { p -> onSnackbar("打开目录 $p"); onOpenRemoteDir(p) },
                    onFileAction = { e -> onSnackbar("开始下载 ${e.name}"); onDownloadRemote(e) },
                )
            }
            if (downloadStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(downloadStatus)
            }
        }

        // ------------------------------------------------ ④ 已下载文件
        SectionCard(title = "已下载的文件（${files.size}）", badge = "④") {
            Text(
                "落盘目录：$downloadDirPath",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "用 ③ 从服务器下载的文件会出现在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Illustration(R.drawable.transfer_empty_downloads)
                Spacer(Modifier.height(8.dp))
                Text(
                    "这里还是空的 —— 用上面的 ③ 从服务器下载文件。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            files.forEach { file ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file),
                        contentDescription = "文件",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            file.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${HttpFileServer.humanSize(file.length())} · ${dateText(file.lastModified())}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSnackbar("已唤起分享面板"); onShare(file) }) { Text("分享") }
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSnackbar("正在打开 ${file.name}"); onOpen(file) }) { Text("打开") }
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onDelete(file) },
                    ) { Text("删除") }
                }
                HorizontalDivider()
            }
        }

        // ------------------------------------------------ ⑤ 上传到服务器（SSH/SFTP）
        SectionCard(title = "上传到服务器（SSH/SFTP，可选直连/隧道）", badge = "⑤") {
            Text(
                "优先直连 IPv6（路径最短、实测字节可靠）；连不上才退回隧道 —— 隧道只适合小操作。" +
                    "断了会自动重连、从断点接着传（远端那半截保留着，不会从头再来）。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !upload.running, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onPickFiles() }) { Text("选择文件上传") }
            }
            if (failedCount > 0 && !upload.running) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("上次有 $failedCount 个没传完", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onRetryFailed() }) { Text("重试失败项（自动续传）") }
                }
            }
            if (upload.running) {
                Spacer(Modifier.height(8.dp))
                UploadProgress(upload)
            }

            // ---- 目标目录：和 ③ 一样可列目录、可进子目录 ----
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("目标目录", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            GrowingPathField(
                value = sshConfig.remoteDir,
                onValueChange = { v -> onSshConfigChange { it.copy(remoteDir = v.trim()) } },
                label = "远程目录",
                enabled = !upload.running,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    enabled = !upload.running,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        uploadListOpen = true
                        onListUploadDir()
                    },
                ) { Text("列出") }
                Spacer(Modifier.width(4.dp))
                TextButton(enabled = !upload.running, onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onUploadParentDir() }) { Text("上级") }
                Spacer(Modifier.width(4.dp))
                // 一次误触清空就会让上传报 SSH_FX_NO_SUCH_FILE，给个一键还原
                TextButton(
                    enabled = !upload.running,
                    onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSshConfigChange { it.copy(remoteDir = SshConfig().remoteDir) } },
                ) { Text("重置") }
                if (uploadListOpen && (uploadEntries.isNotEmpty() || uploadListed)) {
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        enabled = !upload.running,
                        onClick = { uploadListOpen = false },
                    ) { Text("收起") }
                }
            }
            if (sshConfig.remoteDir.isBlank()) {
                Spacer(Modifier.height(4.dp))
                StatusLine(
                    "远程目录不能为空 —— 点上面的「重置」还原成默认值",
                    StatusLevel.WARNING,
                )
            }
            if (uploadListOpen) {
                RemoteListing(
                    entries = uploadEntries,
                    listed = uploadListed,
                    busy = upload.running,
                    emptyHint = "这个目录是空的 —— 换个目录试试（可以点「上级」回上一层）。",
                    fileActionLabel = null,
                    onOpenDir = { p -> onSnackbar("打开目录 $p"); onOpenUploadDir(p) },
                    onFileAction = {},
                )
            }
            if (uploadDirStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(uploadDirStatus)
            }

            // ---- 文本上传：把一段文字直接变成服务器上的文件 ----
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextComposer(
                title = "文本上传",
                hint = "把一段文本写成服务器上的文件（.txt 之类）。剪贴板里的内容也走这条路。",
                draft = uploadTextDraft,
                onDraftChange = { uploadTextDraft = it },
                name = uploadTextName,
                onNameChange = { uploadTextName = it },
                enabled = !upload.running,
                actionLabel = "上传文本",
                onAction = { onUploadText(uploadTextDraft, uploadTextName) },
            )
            if (uploadStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                StatusLine(uploadStatus)
            }
        }

        Text(
            "源码：ui/main/MainScreen.kt（界面）· ui/main/MainScreenViewModel.kt（状态）· data/SshUploadRepository.kt（SFTP）· net/SshPortForward.kt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 8.dp),
        )
    }
}

/**
 * 走哪条路：直连 / 隧道 / 自动 + 传输中自动换路。
 * 说明文字跟着选项走，避免"选了一项却不知道它意味着什么"。
 */
@Composable
private fun RoutePicker(route: String, enabled: Boolean, onChange: (TransferRoute) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TransferRoute.values().forEach { r ->
            if (TransferRoute.of(route) == r) {
                Button(enabled = enabled, onClick = { onChange(r) }) { Text(r.label) }
            } else {
                TextButton(enabled = enabled, onClick = { onChange(r) }) { Text(r.label) }
            }
            Spacer(Modifier.width(6.dp))
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        when (TransferRoute.of(route)) {
            TransferRoute.AUTO ->
                "自动：WiFi/有线走直连、移动数据走隧道；传的过程中发现太慢会自动换另一条" +
                    "（靠断点续传接着搬，见下面阈值）。"
            TransferRoute.DIRECT -> "始终直连 IPv6：校园网下最快（实测 0% 丢包、cwnd 38、RTT 30ms）。"
            TransferRoute.TUNNEL -> "始终走 Cloudflare 隧道（SSH 装进 WebSocket，端到端加密），任何网络都能用。"
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        "换路判据：观察 6 秒、至少搬过 512 KB，速率低于 120 KB/s 就换另一条。" +
            "（广电移动数据直连实测 12–62 KB/s、校园网直连 ≈350 KB/s、隧道服务器侧 1.06 MB/s）",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

/**
 * 会随内容变长的路径输入框。
 *
 * 为什么不用 `Modifier.weight(1f)`：那样宽度被同行按钮钉死，长路径只会在框里滚动、
 * 一眼看不全。这里按字符数估一个宽度（上限是这一行能给的宽度），输入越长框越宽。
 */
@Composable
private fun GrowingPathField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // 一个字符按 8dp 估宽；再加左右内边距与标签占位的余量
        val desired = (value.length.coerceAtLeast(label.length) * 8).dp + 64.dp
        val upper = maxWidth.coerceAtLeast(180.dp)
        val width = desired.coerceIn(180.dp, upper)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.width(width),
        )
    }
}

/**
 * 远端目录列表：③ 从服务器下载、⑤ 上传到服务器共用。
 * [fileActionLabel] 为 null 时文件行不给按钮（⑤ 只是用来看目录里有什么）。
 */
@Composable
private fun RemoteListing(
    entries: List<MainScreenViewModel.RemoteEntryUi>,
    listed: Boolean,
    busy: Boolean,
    emptyHint: String,
    fileActionLabel: String?,
    onOpenDir: (String) -> Unit,
    onFileAction: (MainScreenViewModel.RemoteEntryUi) -> Unit,
) {
    if (entries.isEmpty()) {
        if (listed) {
            Spacer(Modifier.height(8.dp))
            Illustration(R.drawable.transfer_empty_folder)
            Spacer(Modifier.height(8.dp))
            Text(emptyHint, style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    Spacer(Modifier.height(8.dp))
    // 目录可能有几千项，界面只画前 200 个
    entries.take(200).forEach { entry ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (entry.dir) R.drawable.ic_folder else R.drawable.ic_file),
                contentDescription = if (entry.dir) "目录" else "文件",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!entry.dir) {
                    Text(HttpFileServer.humanSize(entry.size), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (entry.dir) {
                TextButton(enabled = !busy, onClick = { onOpenDir(entry.path) }) { Text("进入") }
            } else if (fileActionLabel != null) {
                TextButton(enabled = !busy, onClick = { onFileAction(entry) }) { Text(fileActionLabel) }
            }
        }
        HorizontalDivider()
    }
    if (entries.size > 200) {
        Text("（只显示了前 200 项）", style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 文本输入块：⑤ 上传的「文本上传」用。
 * （另一个调用方 —— 日志 App 的「文本存档」—— 已经跟着存档功能搬到那个工程里了。）
 */
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
    val haptics = LocalHapticFeedback.current
    Text(title, style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        label = { Text("要上传的文本") },
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
        Button(
            enabled = enabled && draft.isNotBlank(),
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onAction() },
        ) { Text(actionLabel) }
    }
    Spacer(Modifier.height(4.dp))
    TextButton(
        enabled = enabled,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() }
                .getOrNull()
            if (!clip.isNullOrBlank()) onDraftChange(clip)
        },
    ) { Text("粘贴剪贴板到文本框") }
}

/**
 * 空状态插画。
 *
 * 图是 AI 出的线稿、**抠掉了白底**（alpha = 离白有多远，所以贴回白/浅色底时和原稿一模一样）。
 * 深色模式下直接放在深色卡片上会"糊"，所以在下面垫一块浅色圆角底板 —— 浅色模式下这块板几乎看不见。
 */
@Composable
private fun Illustration(@DrawableRes id: Int, modifier: Modifier = Modifier) {
    val plate = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(plate)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().heightIn(max = 170.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * 可收起的选项卡。点标题（整行都可点）在展开/收起之间切换；
 * 收起时只剩标题这一行，内容完全不参与布局。
 *
 * 展开状态放在 [SectionCard] 内部的 `remember`：五张卡在同一个 Column 里各占一个槽位，
 * 所以各自独立，不会"点一张五张一起动"。
 */
@Composable
private fun SectionCard(
    title: String,
    badge: String = "",
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = { expanded = !expanded },
                    )
                    .semantics { set(SemanticsProperties.StateDescription, if (expanded) "已展开" else "已收起") },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (badge.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            Text(
                                badge,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
            // 用 animateContentSize 让展开/收起有过渡，而不是硬切
            Column(modifier = Modifier.animateContentSize()) {
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    content()
                }
            }
        }
    }
}

/**
 * 统一状态行：左侧一个状态图标（Info / CheckCircle / Warning / Error），
 * 颜色按 level 走 —— 成功=primary、失败=error、警告=琥珀、进行中=onSurfaceVariant。
 * 不截断文字：路径 / 文件名 / sha 这类信息原样保留（最多换行，不省略）。
 */
private enum class StatusLevel { INFO, SUCCESS, WARNING, ERROR }

private fun statusLevelOf(text: String): StatusLevel = when {
    text.startsWith("✅") -> StatusLevel.SUCCESS
    text.startsWith("❌") -> StatusLevel.ERROR
    text.startsWith("⚠") -> StatusLevel.WARNING
    else -> StatusLevel.INFO
}

@Composable
private fun StatusLine(
    text: String,
    level: StatusLevel = statusLevelOf(text),
    modifier: Modifier = Modifier,
) {
    val (iconRes, tint) = when (level) {
        StatusLevel.INFO -> R.drawable.ic_info to MaterialTheme.colorScheme.onSurfaceVariant
        StatusLevel.SUCCESS -> R.drawable.ic_check_circle to MaterialTheme.colorScheme.primary
        StatusLevel.WARNING -> R.drawable.ic_warning to Color(0xFF9A6A00)
        StatusLevel.ERROR -> R.drawable.ic_error to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            modifier = Modifier.weight(1f),
        )
    }
}

/** ⑤ 与 ③ 共用的上传进度行：第 i/n 个、文件名、百分比、速度、阶段文字。 */
@Composable
private fun UploadProgress(upload: MainScreenViewModel.UploadUiState) {
    val pct = if (upload.total > 0) (upload.done * 100 / upload.total).toInt() else -1
    Text(
        buildString {
            append(upload.phase.ifBlank { "上传中" })
            // count == 0 是"还在筛类型/准备"，这时没有第 i/n 个可言
            if (upload.count > 0) append(" ${upload.index}/${upload.count}：${upload.fileName}")
            if (pct >= 0) {
                append("  $pct%（${HttpFileServer.humanSize(upload.done)} / ${HttpFileServer.humanSize(upload.total)}）")
            } else {
                append("  ${HttpFileServer.humanSize(upload.done)}")
            }
            if (upload.speedKbps > 0) append("  · ${upload.speedKbps} KB/s")
        },
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(4.dp))
    if (pct >= 0) {
        LinearProgressIndicator(
            progress = { (upload.done.toFloat() / upload.total).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

// ------------------------------------------------------------------ 小工具

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
                    "这行带 no-pty 等收窄项 + permitopen=\"127.0.0.1:3080\"：既能 SFTP，也能把服务器的 DSH 端口转发出来，但拿不到 shell。",
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

private fun shareFile(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = HttpFileServer.guessMime(file.name)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "发送到").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/**
 * 装 APK：先确认本应用有「安装未知应用」权限（没有就把用户带到那个开关），再交给安装器。
 *
 * 不这么做的话，点了「打开」只会静默失败 —— 因为从 Android 8 起，
 * 应用要弹安装器必须在清单里声明 REQUEST_INSTALL_PACKAGES 且被用户放行。
 */
private fun installApk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "请先允许「传输助手」安装未知应用，再回来点打开", Toast.LENGTH_LONG).show()
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { Toast.makeText(context, "启动安装器失败：${it.message}", Toast.LENGTH_LONG).show() }
}

private fun openFile(context: Context, file: File) {
    val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, HttpFileServer.guessMime(file.name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun dateText(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

@Preview(showBackground = true, widthDp = 380, heightDp = 900)
@Composable
private fun TransferContentPreview() {
    TransferDemoTheme {
        TransferContent(
            files = listOf(File("/tmp/a.apk"), File("/tmp/b.zip")),
            status = "已删除：a.apk",
            downloadDirPath = "/data/user/0/dev.example.transfer/files/downloads",
            sshConfig = SshConfig(),
            sshStatus = "✅ 直连 2001:db8::1（3.1s）\n远端目录：/home/user/phone-upload —— 在（3 项）",
            upload = MainScreenViewModel.UploadUiState(),
            uploadStatus = "✅ 笔记.md → /home/user/phone-upload/笔记.md（20.0 KB）",
            failedCount = 0,
            uploadEntries = listOf(
                MainScreenViewModel.RemoteEntryUi("notes", 4096, true, "/home/user/phone-upload/notes"),
            ),
            uploadListed = true,
            uploadDirStatus = "/home/user/phone-upload —— 1 个目录 / 0 个文件",
            remoteEntries = listOf(
                MainScreenViewModel.RemoteEntryUi("dist", 4096, true, "/home/user/dsh-work/dsh-client/dist"),
                MainScreenViewModel.RemoteEntryUi("DshClient-1.0.0.jar", 733157, false, "/home/user/dsh-work/dsh-client/dist/DshClient-1.0.0.jar"),
            ),
            download = MainScreenViewModel.DownloadUiState(),
            downloadStatus = "/home/user —— 3 个目录 / 7 个文件",
            remoteListed = true,
            onDelete = {},
            onShare = {},
            onOpen = {},
            onSshConfigChange = {},
            onTestConnection = {},
            onPickFiles = {},
            onShowPublicKey = {},
            onRetryFailed = {},
            onUploadText = { _, _ -> },
            onListRemote = {},
            onOpenRemoteDir = {},
            onParentDir = {},
            onDownloadRemote = {},
            onListUploadDir = {},
            onOpenUploadDir = {},
            onUploadParentDir = {},
            snackbarHostState = remember { SnackbarHostState() },
            onSnackbar = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
