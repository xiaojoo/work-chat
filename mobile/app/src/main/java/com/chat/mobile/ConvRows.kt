package com.chat.mobile

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条 LOAD_MESSAGES 回包里，哪些行属于屏幕上这条会话。
 *
 * 网关的回包是按连接推的，但这一页拿到回包后是**整批替换**本地列表的：
 * 只要回包里混进别的会话的行（换会话时的旧回包、以后接进来的跨会话批量），
 * 这一页就会静悄悄地把别人的聊天记录摆在这儿，界面上一点痕迹都没有。
 * 所以按行认一次领，丢掉的条数交给调用方显示出来——丢了东西要看得见。
 */
object ConvRows {

    /** @param dropped 没有原样摆进列表的行数，包括读不出 conversationId 的那些 */
    data class Split(val kept: List<JSONObject>, val dropped: Int)

    fun split(arr: JSONArray, convId: String): Split {
        val kept = ArrayList<JSONObject>(arr.length())
        var dropped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // 缺字段也算外来：网关的 MessageResponse 一定带 conversationId，不带就是不该摆在这页的东西
            val cid = o.optString("conversationId")
            if (cid == convId) kept.add(o) else dropped++
        }
        return Split(kept, dropped)
    }
}
