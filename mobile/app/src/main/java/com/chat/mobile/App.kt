package com.chat.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** 进程一起来就把夜间模式设成存的那档。
 *  放在 Application 而不是各 activity 里，是因为 setDefaultNightMode 会自己把在显示的页面重建，
 *  而 setLocalNightMode 只管一个窗口——两边各设一次会互相打脸。 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Cfg.nightMode(this))
    }
}
