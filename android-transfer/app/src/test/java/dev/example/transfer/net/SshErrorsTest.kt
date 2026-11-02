package dev.example.transfer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 错误信息的"翻译"必须准 —— 这次真机失败就是栽在这上面：
 * 会话断了却报「远程目录不存在且创建失败」，用户拿着这句话查目录只会白费功夫。
 *
 * 这些用例不需要网络，属于随时可跑的回归测试。
 */
class SshErrorsTest {

    @Test
    fun pipe_closed_is_recognized_as_dead_session() {
        // JSch 的 Channel$MyPipedInputStream 被关掉时抛的就是这句
        val e = IOException("Pipe closed")
        assertTrue("Pipe closed 必须识别成会话已死", SshErrors.isSessionDead(e))
        assertTrue("提示语应说明不是目录/配置问题", SshErrors.hint(e).contains("不是目录"))
    }

    @Test
    fun recognizes_dead_session_through_cause_chain() {
        val root = IOException("Connection reset")
        val wrapped = IllegalStateException("查远端文件大小失败（/home/user/phone-upload/x.mp4）", root)
        assertTrue("cause 链里的根因也要能识别", SshErrors.isSessionDead(wrapped))
    }

    @Test
    fun does_not_flag_an_ordinary_missing_file_error() {
        val e = IllegalStateException("远程目录 /nope 不存在，创建也失败：SftpException: Permission denied")
        assertFalse("普通错误不能被误判成断线", SshErrors.isSessionDead(e))
        assertTrue("普通错误的提示也不该说'网络断了'", !SshErrors.hint(e).contains("弄断"))
    }

    @Test
    fun describe_flattens_whole_cause_chain() {
        val e = IllegalStateException("外层", IOException("Pipe closed"))
        val text = SshErrors.describe(e)
        assertTrue("应包含外层：$text", text.startsWith("IllegalStateException: 外层"))
        assertTrue("应包含根因：$text", text.contains("IOException: Pipe closed"))
        assertTrue("应带箭头连接：$text", text.contains("←"))
    }

    @Test
    fun describe_survives_null_and_self_referencing_cause() {
        assertEquals("未知错误", SshErrors.describe(null))
        val e = IOException("a")
        assertFalse(SshErrors.describe(e).isEmpty())
    }
}
