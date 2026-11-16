package dev.example.journal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑥ 存档区白名单。判错的后果是"要么把不该传的东西传进归档区，要么把该收的文本/图片拒掉"，
 * 而这条逻辑在真机上只表现为"某个文件没传"，很难反查。纯 JVM，随时可跑。
 */
class ArchiveFilterTest {

    @Test
    fun images_pass_by_mime_or_extension() {
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify("image/png", "a.bin"))
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify(null, "IMG_0001.HEIC"))
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify(null, "表情包.webp"))
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify(null, "photo.avif"))
    }

    @Test
    fun texts_pass_by_mime_or_extension() {
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify("text/plain", "note"))
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify(null, "readme.md"))
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify(null, "笔记.markdown"))
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify(null, "data.jsonl"))
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify(null, "字幕.srt"))
    }

    @Test
    fun octet_stream_falls_back_to_extension() {
        // 真机：SAF 常给 application/octet-stream，此时必须看扩展名，否则 .json/.md 全被拒
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify("application/octet-stream", "a.json"))
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify("application/octet-stream", "a.png"))
        // 提供方给的 application/json 不在 text/* 里，也靠扩展名兜底
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify("application/json", "a.json"))
        // 但如果扩展名也不在白名单里，octet-stream 不能把它救回来
        assertNull(ArchiveFilter.classify("application/octet-stream", "movie.mp4"))
    }

    @Test
    fun mime_parameters_are_ignored() {
        assertEquals(ArchiveKind.TEXT, ArchiveFilter.classify("text/plain;charset=utf-8", "x"))
        assertEquals(ArchiveKind.IMAGE, ArchiveFilter.classify("IMAGE/JPEG", "x"))
    }

    @Test
    fun other_types_are_rejected() {
        assertNull(ArchiveFilter.classify(null, "movie.mp4"))
        assertNull(ArchiveFilter.classify("video/mp4", "movie.mp4"))
        assertNull(ArchiveFilter.classify("application/vnd.android.package-archive", "app.apk"))
        assertNull(ArchiveFilter.classify(null, "archive.zip"))
        assertNull(ArchiveFilter.classify(null, "Makefile"))
        assertNull(ArchiveFilter.classify(null, "notes."))
        assertFalse(ArchiveFilter.isAccepted("application/pdf", "a.pdf"))
        assertTrue(ArchiveFilter.isAccepted(null, "a.log"))
    }

    @Test
    fun rejection_text_lists_every_skipped_name() {
        val text = ArchiveFilter.rejectionText(listOf("xxx.mp4", "yyy.apk"))
        assertTrue(text.contains("存档区只收文本和图片"))
        assertTrue(text.contains("xxx.mp4、yyy.apk"))
    }
}
