package com.chat.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真后端跑协议：不 mock、不起假服务。地址和测试号从环境变量进，
 * 缺任何一项就整条跳过（跳过会和结果一起打印出来，不含混视听的"绿"）。
 */
class ApiSmokeTest {

    private val base = System.getProperty("CHAT_API_BASE", "http://127.0.0.1:8081")
    private val user = System.getProperty("CHAT_USER", "")
    private val pw = System.getProperty("CHAT_PW", "")

    @Test
    fun `登录能拿到令牌并列出会话`() {
        assumeTrue("没给 CHAT_USER/CHAT_PW，跳过真后端这一段", user.isNotEmpty() && pw.isNotEmpty())

        val s = Api(base).login(user, pw)
        assertTrue("令牌是空串", s.token.isNotEmpty())
        assertTrue("userId 空 —— 后端这个字段是 JSON 数字，取法必须能收下它", s.userId.isNotEmpty())
        println("协议·登录 ok  令牌长度=${s.token.length}  userId=${s.userId}")

        val list = Api(base, s.token).conversations(s.userId)
        println("协议·会话 ${list.size} 条，未读合计 ${list.sumOf { it.unread }}")
        list.take(5).forEach { println("  conv=${it.id} type=${it.type} 名=${it.name} target=${it.targetId} 未读=${it.unread}") }
        assertTrue("会话列表为空", list.isNotEmpty())
        assertTrue("每条都要有 id 和未读位（未读位解析不出来的话这个端就看不见红点）",
            list.all { it.id.isNotEmpty() && it.unread >= 0 })
        // 真机截图上抓到过：后端对没值的字段给 JSON null，Android 的 org.json 把它读成
        // 字符串 "null"，界面上就真渲染出这三个字母。这条断言在修好之前必须是红的
        val leaked = list.filter { v -> listOf(v.name, v.lastMessage, v.lastMessageTime, v.targetId).any { it == "null" } }
        assertTrue("有字段把 JSON null 读成了字面量 \"null\"：${leaked.map { it.id }}", leaked.isEmpty())
    }

    @Test
    fun `错密码必须失败而不是被当成成功`() {
        assumeTrue("没给 CHAT_USER，跳过反例", user.isNotEmpty())
        val failed = try {
            Api(base).login(user, "wrong-password-on-purpose-${System.currentTimeMillis()}")
            false
        } catch (e: Api.Failure) {
            println("反例·后端回的是：${e.message}")
            true
        }
        assertTrue("错密码居然登录成功了 —— 这条断言必须在真鉴权坏掉时变红", failed)
    }

    @Test
    fun `地址归一化把裸 IP 补成 8081 且不吞已写的端口`() {
        // normalize 是纯函数，不需要后端，所以它永远该跑，不跟着上面的 assume 一起跳
        val host = "192.168.31.5"
        assertEquals("http://$host:${Cfg.USER_PORT}", Cfg.normalize(host))
        assertEquals("http://$host:9000", Cfg.normalize("http://$host:9000"))
        assertEquals("http://$host:${Cfg.USER_PORT}", Cfg.normalize("$host:8081/"))
        assertEquals("", Cfg.normalize("   "))
    }
}
