package dev.example.transfer.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把文件另存一份到**公共「下载」目录**。
 *
 * 为什么需要：④ 从服务器下载下来的文件原本只落在 App 私有目录
 * （`filesDir/downloads`），**任何文件管理器都看不见它** —— 于是"下载完想手动安装 APK"
 * 这条路根本走不通（只能在 App 里点「打开」）。
 *
 * 现在 APK 会额外写一份到公共下载目录：
 *  - Android 10+ 走 MediaStore（分区存储，**不需要任何权限**）
 *  - Android 9 及以下写公共目录，需要 WRITE_EXTERNAL_STORAGE（清单里已声明 maxSdkVersion=28）
 *
 * 返回可读的位置描述（失败返回 null，调用方只提示、不当作错误）。
 */
object PublicDownloads {

    fun exportApk(context: Context, file: File): String? = try {
        val name = file.name
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "「下载」/$name"
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) return null
            val dest = File(dir, name)
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.absolutePath
        }
    } catch (e: Exception) {
        null
    }
}
