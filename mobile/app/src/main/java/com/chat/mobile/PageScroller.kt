package com.chat.mobile

import android.content.Context
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.HorizontalScrollView

/** 表情那一排用的横向滚动条：松手时整页吸附。
 *
 *  为什么不用 HorizontalScrollView 自己的 fling：它的 fling 是私有 FlingRunnable 起的，
 *  既没有公开回调（setOnFlingListener 是 AbsListView/RecyclerView 上的），也重写不到 onFling。
 *  而原来那版"滚动停下 130ms 后再补一段 smoothScrollTo"实测就是他说的那个卡顿——
 *  先停住、再以 60~500px/s 爬回整页（17px 爬了 115ms，中间还有一帧 39ms 没出帧）。
 *
 *  现在的做法：ACTION_UP 里先让 super 走完，再 smoothScrollTo 到目标页——
 *  HorizontalScrollView.smoothScrollTo 自己会把在跑的 fling 掐掉（endFling + abortAnimation），
 *  所以两段动画在同一帧接上，看不出来断点。
 *  方向按最近 100ms 的手指速度判：甩得快就顺方向进一页，慢慢拖就吸到离得最近的那一页。
 *  overscroll 关掉：它让 scrollX 冲到 1097 再弹回 1080，那 17px 的弹回也是爬行的来源。 */
class PageScroller @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) :
    HorizontalScrollView(ctx, attrs) {

    var pageCount = 0
    private val recent = ArrayDeque<Pair<Long, Float>>()

    init {
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> recent.clear()
            MotionEvent.ACTION_MOVE -> {
                val t = SystemClock.uptimeMillis()
                recent.addLast(t to e.rawX)
                while (recent.isNotEmpty() && t - recent.first().first > 100) recent.removeFirst()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = super.onTouchEvent(e)
                settle(e.rawX)
                return r
            }
        }
        return super.onTouchEvent(e)
    }

    /** px/ms：超过这个速度算"甩"，按方向整页翻 */
    private fun settle(rawEnd: Float) {
        val w = width
        if (w == 0 || pageCount < 1) return
        val t = SystemClock.uptimeMillis()
        val v = if (recent.size >= 2) {
            (rawEnd - recent.first().second) / (t - recent.first().first).coerceAtLeast(1L)
        } else 0f
        val cur = Math.round(scrollX / w.toFloat())
        val to = when {
            v > FLING -> cur - 1
            v < -FLING -> cur + 1
            else -> (scrollX + w / 2) / w
        }.coerceIn(0, pageCount - 1)
        smoothScrollTo(to * w, 0)
    }

    private companion object {
        const val FLING = 0.8f
    }
}
