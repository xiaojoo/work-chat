package com.chat.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长按消息弹框的摆位：用真机（M2012K10C 1080x2400、dpr 2.75）量到的坐标喂进去，
 * 判三件事——底下放不下翻不翻到上面、三角指没指上那条气泡、贴边时会不会跑出去。
 * 真机上 uiautomator 拿不到 idle 时（他正在用手机）这几条照样能出数字。
 */
class MsgMenuPlaceTest {

    private val d = 2.75f
    private val winW = 1080
    private val winH = 2200                       // decorView 高：2400 减掉状态栏和导航栏
    private val w = (62 * 5 * d).toInt()          // 弹框宽 5 列 ×62dp
    private val tipW = (12 * d).toInt()
    private val gap = (2 * d).toInt()
    private val edge = (8 * d).toInt()
    private val tipEdge = (12 * d).toInt()
    private val total = (373 + 6 * d).toInt()     // 两行格子实测高 + 那颗三角

    private fun put(ax: Int, ay: Int, aw: Int, ah: Int) = MsgMenuPlace.place(
        winW, winH, ax, ay, aw, ah, 0, winW, w, total, gap, tipW, edge, tipEdge)

    @Test
    fun `底下放不下就翻到上面`() {
        val p = put(166, 1833, 260, 94)           // 屏幕最下面那条消息
        assertTrue("底部这条应该翻到上面（下面只剩 ${winH - (1833 + 94)}px，弹框要 $total+$gap）", p.flipUp)
        assertEquals("翻上去之后 y = 消息顶 - 缝 - 弹框高", 1833 - gap - total, p.y)

        val q = put(166, 238, 260, 87)            // 屏幕最上面那条
        assertFalse("顶部这条底下够放，不该翻", q.flipUp)
        assertEquals(238 + 87 + gap, q.y)
        println("摆位 ok  底部条 y=${p.y} 翻上=${p.flipUp}   顶部条 y=${q.y} 翻上=${q.flipUp}")
    }

    @Test
    fun `三角的尖正对着气泡中心`() {
        for ((nm, ax, aw) in listOf(Triple("对方（贴左）", 166, 260), Triple("自己（贴右）", 654, 260))) {
            val p = put(ax, 1833, aw, 94)
            val centre = ax + aw / 2
            val tipCentre = p.x + p.tipMargin + tipW / 2
            assertEquals("$nm 三角中心要对上气泡中心 $centre", centre.toLong(), tipCentre.toLong())
            println("三角 ok  $nm 气泡中心=$centre 三角中心=$tipCentre Δ=0  弹框 x=${p.x}")
        }
    }

    @Test
    fun `弹框和三角都不越界`() {
        val p = put(166, 1833, 260, 94)
        assertTrue("弹框左边不出屏", p.x >= edge)
        assertTrue("弹框右边不出屏", p.x + w <= winW - edge)
        assertTrue("三角不贴着圆角", p.tipMargin >= tipEdge && p.tipMargin + tipW <= w - tipEdge)

        /* 特别宽的消息：气泡中心跑到 1050，三角会被卡片右边界夹住——不越界，但指向会偏，
           这条断言就是把这个取舍钉在测试里，别以后当成 bug 去找 */
        val wide = MsgMenuPlace.place(winW, winH, 790, 1833, 260, 94, 0, winW,
            w, total, gap, tipW, edge, tipEdge)
        assertEquals("夹到卡片内侧 12dp", w - tipEdge - tipW, wide.tipMargin)
        println("边界 ok  弹框 x=${p.x}..${p.x + w}  三角 ${p.tipMargin}..${p.tipMargin + tipW}；" +
            "超宽气泡时三角夹在 ${wide.tipMargin}，指向偏 ${(790 + 130) - (wide.x + wide.tipMargin + tipW / 2)}px")
    }
}
