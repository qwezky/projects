package dev.example.transfer

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志落盘 + 下次启动显示。
 *
 * 为什么需要：手机上没装 adb 就拿不到 logcat，App 一崩只能看到系统那句
 * 「应用已停止运行」，完全没法定位。这里把未捕获异常的堆栈写到 App 私有目录，
 * 下次启动时读出来显示在界面上，可一键复制粘给开发者。
 *
 * 只做本地文件读写：不联网、不上传，卸载 App 时随私有目录一起清掉。
 */
object CrashLog {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 20_000

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 在 Application/Activity 最早的时机调用一次。 */
    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // 仍然交回系统默认处理：该弹「应用已停止运行」还弹，进程正常退出
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // longVersionCode 是 API 28+；minSdk 是 26，直接调会 NoSuchMethodError（lint 抓到的真 bug）
            @Suppress("DEPRECATION")
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("?")

        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            pw.println("线程: ${thread.name}")
            pw.println("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            pw.println("App : ${context.packageName} $version")
            pw.println()
            throwable.printStackTrace(pw)
        }
        file(context).writeText(sw.toString().take(MAX_CHARS))
    }

    /** 有崩溃记录就返回内容，否则 null。 */
    fun read(context: Context): String? {
        val f = file(context)
        return if (f.isFile && f.length() > 0) runCatching { f.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
