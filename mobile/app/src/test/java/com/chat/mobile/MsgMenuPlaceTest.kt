package com.chat.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按消息弹框的摆位：喂的是真机（M2012K10C 1080 宽、dpr 2.75）日志里量到的坐标，
 * 判三件事——贴的是气泡靠头像那一端、三角尖对不上的气泡中心、底下放不下时翻不翻到上面。
 * 真机上 uiautomator 常拿不到 idle（他正在用手机时尤其），光靠截图量不准这几条。
 */
class MsgMenuPlaceTest {

    private val d = 2.75f
    private val winW = 1080
    private val winH = 2200                       // decorView 高：2400 减掉状态栏和导航栏
    private val w = (62 * 5 * d).toInt()          // 卡片宽 5 列 ×62dp = 852
    private val tipW = (12 * d).toInt()
    /* 缝取 0：三角的尖就落在气泡的那条边上，既不压进气泡也不空出一道 */
    private val gap = 0
    private val tipH = (6 * d).toInt()            // 三角高 16
    private val ov = (1 * d).toInt()              // 三角往卡片里压 2，盖住那条描边
    private val edge = (8 * d).toInt()
    private val tipEdge = (12 * d).toInt()
    private val total = 376 + tipH - ov           // 两行格子真机量到 376 + 那颗三角（扣掉重叠）

    private fun put(ax: Int, ay: Int, aw: Int, ah: Int, self: Boolean) =
        MsgMenuPlace.place(winW, winH, ax, ay, aw, ah, self, w, total, gap, tipW, edge, tipEdge)

    @Test
    fun `卡片贴气泡靠头像那一端`() {
        /* 日志里真机按到的两条：别人发的在左（x=166 宽 414），我发的在右（x=406 宽 508） */
        val in1 = put(166, 1329, 414, 94, self = false)
        assertEquals("别人发的：卡片左沿 = 气泡左沿", 166, in1.x)
        val me = put(406, 687, 508, 94, self = true)
        assertEquals("我发的：卡片右沿 = 气泡右沿", 406 + 508, me.x + w)
        println("对齐 ok  别人发的 x=${in1.x}..${in1.x + w}   我发的 x=${me.x}..${me.x + w}")
    }

    @Test
    fun `三角的尖正对着气泡中心`() {
        for ((nm, ax, aw, self) in listOf(
            Row("对方（贴左）", 166, 414, false),
            Row("自己（贴右）", 406, 508, true))) {
            val p = put(ax, 1329, aw, 94, self)
            val centre = ax + aw / 2
            assertEquals("$nm 三角中心要对上气泡中心 $centre",
                centre.toLong(), (p.x + p.tipMargin + tipW / 2).toLong())
            println("三角 ok  $nm 气泡中心=$centre 三角中心=${p.x + p.tipMargin + tipW / 2} Δ=0")
        }
    }

    private data class Row(val nm: String, val ax: Int, val aw: Int, val self: Boolean)

    /** 弹框最外层（也就是那颗尖的顶点）和气泡之间必须**不多不少 0px**：
     *  压在气泡上 = 盖住消息，留缝 = 尖指不到。翻上去那一面同理，只是参照的是气泡顶边。 */
    @Test
    fun `尖贴在气泡边上不压进气泡`() {
        val below = put(166, 1329, 414, 94, self = false)
        assertEquals("朝下摆：尖的顶点 = 气泡底边", 1329 + 94, below.y)
        assertEquals("朝下摆：卡片顶在尖下面一整个三角（扣掉压进卡片的那 2px）",
            below.y + tipH - ov, below.y + 14)

        val above = put(166, 1833, 414, 94, self = false)
        assertTrue("底部那条空间不够，翻上", above.flipUp)
        assertEquals("朝上摆：尖的顶点 = 气泡顶边", 1833, above.y + total)
        println("贴边 ok  朝下 尖y=${below.y} 气泡底=${1329 + 94} Δ=0 / 朝上 尖y=${above.y + total} 气泡顶=1833 Δ=0")
    }

    @Test
    fun `底下放不下就翻到上面`() {
        val p = put(166, 1833, 414, 94, self = false)     // 屏幕最下面那条
        assertTrue("底下只剩 ${winH - (1833 + 94)}px，弹框要 $total+$gap，应该翻上去", p.flipUp)
        assertEquals(1833 - gap - total, p.y)
        val q = put(166, 238, 414, 94, self = false)      // 屏幕最上面那条
        assertFalse(q.flipUp)
        assertEquals(238 + 94 + gap, q.y)
        println("翻面 ok  底部条 y=${p.y} 翻上=true   顶部条 y=${q.y} 翻上=false")
    }

    @Test
    fun `夹边时不越界，指向可以偏`() {
        /* 特别窄的气泡：三角对不上中心，会被"离卡片圆角至少 12dp"夹住 —— 这是取舍，钉在这里 */
        val narrow = put(166, 1329, 80, 94, self = false)
        assertEquals("夹到卡片内侧 12dp", tipEdge, narrow.tipMargin)
        assertTrue("卡片不出屏", narrow.x >= edge && narrow.x + w <= winW - edge)

        /* 我发的、特别靠右：卡片右沿贴气泡右沿，但也不能顶出屏幕 */
        val far = put(790, 687, 260, 94, self = true)
        assertTrue("靠右也不出屏", far.x + w <= winW - edge)
        println("夹边 ok  窄气泡三角夹在 ${narrow.tipMargin}（偏 ${(166 + 40) - (narrow.x + narrow.tipMargin + tipW / 2)}px）；" +
            "靠右卡片 x=${far.x}..${far.x + w}")
    }
}
