package com.chat.mobile

import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import java.time.DayOfWeek

/** 微信那套底部四 tab：微信 / 通讯录 / 工作台 / 我。
 *  实例一次建好反复复用，切回去还能看到原来的滚动位置。 */
class MainActivity : AppCompatActivity() {

    private val tabs by lazy {
        listOf(findViewById<LinearLayout>(R.id.tab_chats), findViewById(R.id.tab_contacts),
            findViewById(R.id.tab_work), findViewById(R.id.tab_me))
    }
    private val frags by lazy { listOf(ChatsFragment(), ContactsFragment(), WorkFragment(), MeFragment()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tabs.forEachIndexed { i, t -> t.setOnClickListener { show(i) } }
        show(0)
    }

    private fun show(i: Int) {
        tabs.forEachIndexed { n, t ->
            t.isActivated = n == i
            // 图标和文字都吃 tint，选中只换色不动几何
            (t.getChildAt(0) as? android.widget.ImageView)?.isActivated = n == i
            (t.getChildAt(1) as? android.widget.TextView)?.isActivated = n == i
        }
        val tag = "f$i"
        val tx = supportFragmentManager.beginTransaction()
        frags.forEachIndexed { n, f ->
            val existing = supportFragmentManager.findFragmentByTag("f$n")
            if (existing != null) { if (n == i) tx.attach(existing) else tx.detach(existing) }
        }
        if (supportFragmentManager.findFragmentByTag(tag) == null) {
            tx.add(R.id.container, frags[i], tag)
        }
        tx.commitNowAllowingStateLoss()
    }
}
