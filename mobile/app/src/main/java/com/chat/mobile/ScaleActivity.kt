package com.chat.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * 全 app 的 activity 都从这儿走，为的是「设置 → 字体大小」那一档。
 *
 * Android 没有"应用内字号"这个 API，唯一不去改系统设置就能生效的做法，
 * 是在 attachBaseContext 里换一个带 fontScale 的配置上下文——布局里所有 sp
 * 就是按这个倍率算的。
 *
 * 已经在栈里的页面不会自己重算配置，所以 onResume 对一下账：
 * 实际生效的倍率和存的那份不一致就 recreate()，等他退回哪一页、哪一页自己更新。
 */
open class ScaleActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val f = Cfg.fontScale(newBase)
        val c = Configuration(newBase.resources.configuration).apply { fontScale = f }
        super.attachBaseContext(newBase.createConfigurationContext(c))
    }

    override fun onResume() {
        super.onResume()
        if (resources.configuration.fontScale != Cfg.fontScale(this)) recreate()
    }

}
