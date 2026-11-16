package dev.example.transfer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import dev.example.transfer.net.RemoteEntry
import java.io.File

/**
 * 目录列表的本地缓存 —— 「进目录不用等」靠它。
 *
 * 为什么要有：列一次目录要一个来回（隧道实测 RTT 257–290ms，手机上更久），
 * 而且每次都要等它回来才画界面。现在改成：
 *   1. 先拿缓存**立即**画出来（0ms，标注"缓存"）；
 *   2. 后台带 `If-None-Match` 问一次，服务端没变就回 304（没有 body）→ 界面不动；
 *      变了才替换（etag 变了才刷新）。
 *
 * 存哪：`filesDir/dir-cache.json`，整个表一次读写（目录数量有限，几十 KB 量级）。
 * 上限 [MAX_DIRS] 个目录，按最后访问时间淘汰最旧的。
 * 只存"列目录结果"，不存文件内容 —— 文件还是现下。
 */
internal object DirCache {

    private const val FILE_NAME = "dir-cache.json"
    private const val MAX_DIRS = 300

    /** 一条目录的缓存。[etag] 为 null 表示这条通道没有版本号（SFTP）。 */
    class Cached(
        val etag: String?,
        val at: Long,
        val entries: List<RemoteEntry>,
    )

    private var table: MutableMap<String, Cached>? = null

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    private fun load(ctx: Context): MutableMap<String, Cached> {
        table?.let { return it }
        val map = LinkedHashMap<String, Cached>()
        runCatching {
            val raw = file(ctx).readText()
            val root = JSONObject(raw)
            for (path in root.keys()) {
                val o = root.getJSONObject(path)
                val arr = o.optJSONArray("e") ?: JSONArray()
                val list = ArrayList<RemoteEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val e = arr.getJSONArray(i)
                    list += RemoteEntry(
                        name = e.getString(0),
                        size = e.getLong(1),
                        dir = e.getBoolean(2),
                        modified = e.getInt(3),
                    )
                }
                map[path] = Cached(
                    etag = o.optString("t").takeIf { it.isNotBlank() },
                    at = o.optLong("a", 0L),
                    entries = list,
                )
            }
        }
        table = map
        return map
    }

    @Synchronized
    fun get(ctx: Context, path: String): Cached? = load(ctx)[path]

    @Synchronized
    fun put(ctx: Context, path: String, etag: String?, entries: List<RemoteEntry>) {
        val map = load(ctx)
        map.remove(path) // 重新插到尾部 = 最近用过
        map[path] = Cached(etag, System.currentTimeMillis(), entries)
        while (map.size > MAX_DIRS) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
        runCatching { save(ctx, map) }
    }

    /** 这个目录的内容被我们改过（上传/删除），缓存作废。 */
    @Synchronized
    fun invalidate(ctx: Context, path: String) {
        val map = load(ctx)
        if (map.remove(path) != null) runCatching { save(ctx, map) }
    }

    @Synchronized
    fun clear(ctx: Context) {
        table = LinkedHashMap()
        runCatching { file(ctx).delete() }
    }

    @Synchronized
    fun size(ctx: Context): Int = load(ctx).size

    private fun save(ctx: Context, map: Map<String, Cached>) {
        val root = JSONObject()
        for ((path, c) in map) {
            val arr = JSONArray()
            for (e in c.entries) {
                arr.put(JSONArray().put(e.name).put(e.size).put(e.dir).put(e.modified))
            }
            root.put(
                path,
                JSONObject()
                    .put("t", c.etag ?: "")
                    .put("a", c.at)
                    .put("e", arr),
            )
        }
        val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(root.toString())
        // rename 是原子的：写一半断电也不会留下半个 JSON
        if (!tmp.renameTo(file(ctx))) {
            file(ctx).writeText(root.toString())
            tmp.delete()
        }
    }
}
