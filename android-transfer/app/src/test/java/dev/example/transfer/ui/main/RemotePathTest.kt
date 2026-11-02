package dev.example.transfer.ui.main

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「从服务器下载」卡片的路径拼接/回退逻辑。
 *
 * 这两个函数短到看起来不用测，但它们是**按钮行为的全部**：
 * 拼错了就列不出目录、算错了「上级」就跳到莫名其妙的地方，
 * 而这两件事在真机上只能靠肉眼发现。纯 JVM，随时可跑。
 */
class RemotePathTest {

    @Test
    fun joins_remote_path_without_double_slash() {
        assertEquals("/home/user/a", joinRemotePath("/home/user", "a"))
        assertEquals("/home/user/a", joinRemotePath("/home/user/", "a"))
        assertEquals("/a", joinRemotePath("/", "a"))
        assertEquals("/home/user/我的 文件.mp4", joinRemotePath("/home/user", "我的 文件.mp4"))
    }

    @Test
    fun parent_walks_up_and_stops_at_root() {
        assertEquals("/home/user", parentOf("/home/user/x"))
        assertEquals("/home", parentOf("/home/user"))
        assertEquals("/", parentOf("/home"))
        assertEquals("/", parentOf("/"))
        assertEquals("/", parentOf("/a"))
        // 尾斜杠："/home/user/" 和 "/home/user" 是同一个目录，所以它的上一级是 /home
        assertEquals("/home", parentOf("/home/user/"))
    }

    @Test
    fun parent_of_relative_path_keeps_it() {
        assertEquals("abc", parentOf("abc"))
    }
}
