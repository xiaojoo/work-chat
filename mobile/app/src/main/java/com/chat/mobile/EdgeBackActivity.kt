package com.chat.mobile

import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

/**
 * 带"从左缘往右推 = 返回"的一屏。聊天页、个人信息页、设置页都用它，
 * 免得同一段手势判断抄三份再各改各的。
 *
 * 只观察不拦截：起点必须落在左缘 24dp 内，横向位移超过 60dp 且明显大过纵向才算，
 * 不然会把列表滚动和气泡里的文字选择抢走。
 */
open class EdgeBackActivity : AppCompatActivity() {

    private var edgeX = -1f
    private var edgeY = -1f

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { edgeX = e.x; edgeY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - edgeX
                val dy = if (e.y - edgeY < 0) edgeY - e.y else e.y - edgeY
                if (edgeX in 0f..dp(24f) && dx > dp(60f) && dx > dy * 2) {
                    edgeX = -1f; finish(); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> edgeX = -1f
        }
        return super.dispatchTouchEvent(e)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
