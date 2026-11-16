package dev.example.journal.data

/** 存档区收下的两类东西。 */
enum class ArchiveKind(val label: String) {
    IMAGE("图片"),
    TEXT("文本"),
}

/**
 * ⑥ 存档区的类型白名单：**上传之前**在本地判，不合格的一个字节都不发。
 *
 * 为什么不能用 SAF 的文件选择器去过滤：真机上 ContentResolver 经常把 `.json`/`.md`
 * 报成 `application/octet-stream`（甚至 `application/json` 不在 `text/*` 里），
 * 用 MIME 当选择器会让这些文件**根本选不到**。所以选择器放开，白名单在这里兜底。
 *
 * 判定顺序：MIME 优先，拿不到可信 MIME 再退回扩展名。
 * `application/octet-stream` 与 `*/*` 视为"没给类型"，而不是"不是文本/图片" —— 否则
 * 真机上最常见的那条路（SAF 给 octet-stream）会把本来该收的 .md/.json 全拒掉。
 *
 * 纯函数、无 Android 依赖，单测在 ArchiveFilterTest。
 */
object ArchiveFilter {

    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
    )

    val TEXT_EXTENSIONS = setOf(
        "txt", "md", "markdown", "csv", "json", "jsonl", "log", "yaml", "yml",
        "toml", "ini", "srt", "vtt", "tex", "xml", "html",
    )

    /** 这些 MIME 等于"提供方没说实话"，必须让扩展名来判。 */
    private val UNRELIABLE_MIME = setOf(
        "application/octet-stream",
        "binary/octet-stream",
        "application/unknown",
        "*/*",
    )

    /** 收下就是 [ArchiveKind]，不收返回 null。 */
    fun classify(mime: String?, fileName: String): ArchiveKind? {
        // 有些提供方给 "text/plain;charset=utf-8"，参数部分与类型无关
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.isNotEmpty() && type !in UNRELIABLE_MIME) {
            if (type.startsWith("image/")) return ArchiveKind.IMAGE
            if (type.startsWith("text/")) return ArchiveKind.TEXT
        }
        return when (val ext = extensionOf(fileName)) {
            in IMAGE_EXTENSIONS -> ArchiveKind.IMAGE
            in TEXT_EXTENSIONS -> ArchiveKind.TEXT
            else -> null
        }
    }

    fun isAccepted(mime: String?, fileName: String): Boolean = classify(mime, fileName) != null

    /**
     * 被拒时给用户看的一句。把白名单本身也说出来 —— 只说"不支持"的话，
     * 用户不知道该把文件转成什么格式再传。
     */
    fun rejectionText(names: List<String>): String =
        "存档区只收文本和图片（jpg/png/webp/gif/bmp/heic/avif 与 " +
            "txt/md/json/csv/log/yaml/xml/html 等），已跳过：${names.joinToString("、")}"

    /**
     * 扩展名：取最后一个点之后的部分并小写。
     * 没有点、点在开头（`.gitignore`）、或结尾是点（`notes.`）都算"没有扩展名"。
     */
    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1).lowercase()
    }
}
