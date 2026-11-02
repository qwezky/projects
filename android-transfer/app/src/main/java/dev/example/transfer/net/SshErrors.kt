package dev.example.transfer.net

/**
 * 把异常翻译成人能看懂的一行字。
 *
 * 为什么需要它 —— 真机上 93MB 录屏传挂时，界面写的是：
 * ```
 * 目标 /home/user/phone-upload/xxx.mp4 失败：远程目录不存在且创建失败：
 * /home/user/phone-upload（java.io.IOException: Pipe closed）
 * ```
 * 目录明明存在（`ls` 一查就在），真正发生的是 **SSH 会话已经死了**：
 * 会话一断，JSch 内部的管道被关掉，之后**任何** SFTP 操作都抛
 * `java.io.IOException: Pipe closed`，而 `remoteSize()` 当时用
 * `runCatching{}.getOrDefault(-1)` 把"stat 抛异常"和"文件不存在"混成一个 -1，
 * 于是被错报成"目录不存在"。用户拿着这句话去查目录只会白费功夫。
 *
 * 两个结论（都用字节码核对过，不是猜的）：
 *  1. `Pipe closed` 出自 `com.jcraft.jsch.Channel$MyPipedInputStream`
 *     （Channel / ChannelSftp 内部用 `java.io.PipedInputStream` 投递数据），
 *     它是"会话死了"的信号，跟 Android 的 ContentProvider、跟目录都无关。
 *  2. 错误信息必须指向真正的那一层，否则比不报错更费时间。
 */
internal object SshErrors {

    /** 摊平 cause 链：手机上没 logcat，异常链就是唯一线索。 */
    fun describe(t: Throwable?): String {
        if (t == null) return "未知错误"
        val chain = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val msg = cur.message?.takeIf { it.isNotBlank() }
            chain += if (msg != null) "${cur.javaClass.simpleName}: $msg" else cur.javaClass.simpleName
            if (cur.cause === cur) break
            cur = cur.cause
            depth++
        }
        return chain.joinToString(" ← ")
    }

    /** 会话/连接已死的特征。 */
    fun isSessionDead(t: Throwable?): Boolean {
        val markers = listOf(
            "pipe closed",              // JSch 的 Channel.MyPipedInputStream 被关掉
            "session is down",
            "session is not connected",
            "channel is not opened",
            "socket is closed",
            "socket closed",
            "connection reset",
            "broken pipe",
            "connection timed out",
            "network is unreachable",
            "no route to host",
        )
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val m = cur.message?.lowercase() ?: ""
            if (markers.any { m.contains(it) }) return true
            if (cur.cause === cur) break
            cur = cur.cause
            depth++
        }
        return false
    }

    /** 给用户的一句人话：说清"是什么断了"，而不是让人去猜配置。 */
    fun hint(t: Throwable?): String = if (isSessionDead(t)) {
        "SSH 长连接被网络弄断了（不是目录/配置问题）—— 已自动重连并从断点续传，远端那半截保留着"
    } else {
        "这一轮失败了 —— 已自动重连重试，远端半截文件保留，下次从断点接着传"
    }
}
