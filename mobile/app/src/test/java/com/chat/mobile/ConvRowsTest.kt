package com.chat.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LOAD_MESSAGES 回包的按会话认领。判的是"外来行进不进得来"和"丢了几行数不数得准"——
 * 这一页拿到回包是整批替换本地列表的，过滤失效的症状是**别人的聊天记录摆在这条会话里**，
 * 界面上不会有任何异常，所以只能靠这一条把它钉住。
 * 会话 id 用两个同形状的假号：这里判的是"带 g 前缀的群会话 id 认领得对不对"，
 * 换成任何两个不同的字符串结论都一样，不需要是真机上那两条。
 */
class ConvRowsTest {

    private val mine = "g1900000000000001"
    private val other = "g1900000000000002"

    private fun row(conv: String, id: String, withConv: Boolean = true) = JSONObject().apply {
        put("messageId", id)
        if (withConv) put("conversationId", conv)
        put("content", "x")
    }

    @Test
    fun `整批都是本会话时一行都不该丢`() {
        val arr = JSONArray().put(row(mine, "a")).put(row(mine, "b")).put(row(mine, "c"))
        val s = ConvRows.split(arr, mine)
        assertEquals(3, s.kept.size)
        assertEquals(0, s.dropped)
    }

    /** 反证只破一边：混进一条别的会话，kept 少一条、dropped 必须正好报出 1 */
    @Test
    fun `混进来的那条要被丢掉并且数得出来`() {
        val arr = JSONArray().put(row(mine, "a")).put(row(other, "x")).put(row(mine, "b"))
        val s = ConvRows.split(arr, mine)
        assertEquals(2, s.kept.size)
        assertEquals(1, s.dropped)
        assertEquals(listOf("a", "b"), s.kept.map { it.optString("messageId") })
    }

    /** 网关的 MessageResponse 一定带 conversationId；不带就是不该摆在这页的东西，按外来算 */
    @Test
    fun `读不出会话id的行按外来算`() {
        val arr = JSONArray().put(row(mine, "a")).put(row(other, "y", withConv = false))
        val s = ConvRows.split(arr, mine)
        assertEquals(1, s.kept.size)
        assertEquals(1, s.dropped)
    }

    /** 调用方拿到 kept 之后自己 reverse，所以这里必须还是服务端那个新→旧的原序 */
    @Test
    fun `kept 保持回包原序`() {
        val arr = JSONArray().put(row(mine, "new")).put(row(mine, "old"))
        assertEquals(listOf("new", "old"), ConvRows.split(arr, mine).kept.map { it.optString("messageId") })
    }
}
