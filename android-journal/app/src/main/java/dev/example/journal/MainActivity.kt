package dev.example.journal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.example.journal.theme.JournalTheme
import dev.example.journal.ui.main.JournalScreen
import dev.example.journal.ui.main.ShareInbox

/**
 * 入口。这个 App 只有一个页面，所以没有用 Navigation3 —— 直接把界面放进 setContent，
 * 少一层依赖、少一处能坏的地方。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 尽早装：这样连 setContent 内部的崩溃也能被记下来
        CrashLog.install(this)

        // 冷启动就带着「分享」进来
        handleShareIntent(intent)

        enableEdgeToEdge()
        setContent {
            JournalTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(modifier = Modifier.fillMaxSize()) {
                    JournalScreen()
                    CrashReportPanel()
                }
                }
            }
        }
    }

    /** App 已经在后台时又收到一次「分享」（singleTop + 这个回调）。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        val payload = sharePayloadFrom(intent) ?: return
        ShareInbox.put(payload)
    }
}

/**
 * 解析「分享」进来的 Intent。
 *
 * 三种形态都要认：
 *   ACTION_SEND + EXTRA_STREAM        单个文件（相册/文件管理器最常见）
 *   ACTION_SEND_MULTIPLE + EXTRA_STREAM  多个文件
 *   ACTION_SEND + EXTRA_TEXT          只有文字（浏览器/聊天里的"分享文本"）
 *
 * 单独拎成纯函数是为了能讲清楚它的边界；Intent 本身在 JVM 单测里是桩，所以这里不做单测，
 * 真机验证靠分享一次看是否自动开始存档。
 */
private fun sharePayloadFrom(intent: Intent?): ShareInbox.Payload? {
    if (intent == null) return null
    val action = intent.action ?: return null
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null

    val uris = mutableListOf<Uri>()
    if (action == Intent.ACTION_SEND) {
        @Suppress("DEPRECATION")
        val single = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (single != null) uris += single
    } else {
        @Suppress("DEPRECATION")
        val many = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
        if (many != null) uris += many
    }
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    if (uris.isEmpty() && text.isNullOrBlank()) return null
    return ShareInbox.Payload(uris, text)
}

/**
 * 上次启动崩溃过的话，把堆栈盖在界面最上层。
 * 手机没装 adb（拿不到 logcat）时，这是唯一能把崩溃原因带出来的通道。
 */
@Composable
private fun CrashReportPanel() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    val text = report ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次启动崩溃了", style = MaterialTheme.typography.titleMedium)
                Text(
                    "把下面内容「复制」后发我就能定位。这只是本机文件，没有上传到任何地方。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("crash", text))
                    }) { Text("复制") }
                    TextButton(onClick = {
                        CrashLog.clear(context)
                        report = null
                    }) { Text("关闭并清除") }
                }
            }
        }
    }
}
