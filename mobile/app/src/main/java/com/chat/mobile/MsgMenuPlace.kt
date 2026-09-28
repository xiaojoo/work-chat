package com.chat.mobile

/**
 * 长按消息那个弹框摆在哪儿：底下的空间不够就翻到上面，并把那颗三角挪到正对着这条消息的气泡。
 *
 * 抽成纯函数是为了能在 JVM 上跑数字——真机上 uiautomator 常拿不到 idle（他正在用手机时尤其），
 * 光靠截图量不准"翻没翻、三角指没指上"。
 */
object MsgMenuPlace {

    data class Place(val x: Int, val y: Int, val flipUp: Boolean, val tipMargin: Int)

    /**
     * @param winW/winH   可视窗口（decorView 的宽高）
     * @param anchorX/Y/W/H  那条消息气泡在窗口里的位置
     * @param rowX/rowW   整行的位置——弹框按整行居中，气泡贴左贴右都不会顶出屏幕
     * @param w           弹框宽（含左右 padding）
     * @param total       弹框高 + 那颗三角的高
     * @param gap         弹框和气泡之间留的缝
     * @param tipW        三角宽
     * @param edge        弹框离屏幕左右至少留这么多
     * @param tipEdge     三角离弹框左右至少留这么多（不贴着圆角）
     */
    fun place(winW: Int, winH: Int, anchorX: Int, anchorY: Int, anchorW: Int, anchorH: Int,
              rowX: Int, rowW: Int, w: Int, total: Int, gap: Int, tipW: Int,
              edge: Int, tipEdge: Int): Place {
        val below = winH - (anchorY + anchorH)
        val above = anchorY
        /* 底下放不下才翻到上面；两边都放不下时留在下面——被截的是离消息远的那头，不是消息本身 */
        val flipUp = below < total + gap && above >= total + gap
        val y = if (flipUp) anchorY - gap - total else anchorY + anchorH + gap

        val x = (rowX + rowW / 2 - w / 2).coerceIn(edge, (winW - w - edge).coerceAtLeast(edge))
        /* 三角的尖要对上气泡的横向中心；贴边时宁可挪到卡片内侧 12dp，也不让它跑出卡片 */
        val centre = anchorX + anchorW / 2
        val max = (w - tipEdge - tipW).coerceAtLeast(tipEdge)
        val tip = (centre - x - tipW / 2).coerceIn(tipEdge, max)
        return Place(x, y, flipUp, tip)
    }
}
