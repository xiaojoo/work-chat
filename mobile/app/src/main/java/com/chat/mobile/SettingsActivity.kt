package com.chat.mobile

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate

/**
 * 微信那个「设置」页的形状：灰底上几组白行，组标题 13sp，行末浅线从文字起头。
 * 组里只摆后端/本机真有的东西：个人信息（PUT /api/user/profile 那一套）、退出登录。
 *
 * 服务器地址和消息网关这两行已经撤掉——那两个值是**项目配置**（app/build.gradle.kts 的
 * API_BASE，换机器在 mobile/local.properties 写 api.base=...），不在界面上展示也不给改。
 * 微信的通知/通用/关于这里没有对应接口，也不抄文案。
 */
class SettingsActivity : EdgeBackActivity() {

    private lateinit var groups: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_settings)
        groups = findViewById(R.id.groups)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()   // 从个人信息页改完名字回来，这一页的昵称要跟着新
    }

    private fun render() {
        groups.removeAllViews()

        Rows.row(groups, "个人信息", "") {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        Rows.endGroup(groups)

        Rows.gap(groups)
        /* 「界面与显示」这一组照他那张图。三档夜间模式都是本机的事，后端没有对应字段——
           所以存 SharedPreferences，杀掉重进还在。
           三行共用同一颗开关的形状，同时只亮一颗：开关说的是"选中的这一档"，
           不是"此刻屏幕亮不亮"——跟随系统且系统正在夜间时，下面两颗照样熄着。 */
        val nm = Cfg.nightMode(this)
        Rows.toggle(groups, "跟随系统",
            on = nm == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            enabled = true, labelSp = 13f) {
            setNight(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        Rows.toggle(groups, "日间模式",
            on = nm == AppCompatDelegate.MODE_NIGHT_NO,
            enabled = true, labelSp = 13f) {
            setNight(AppCompatDelegate.MODE_NIGHT_NO)
        }
        Rows.toggle(groups, "夜间模式",
            on = nm == AppCompatDelegate.MODE_NIGHT_YES,
            enabled = true, labelSp = 13f) {
            setNight(AppCompatDelegate.MODE_NIGHT_YES)
        }
        Rows.row(groups, "字体大小", when (Cfg.fontScale(this)) {
            1.15f -> "大"; 1.3f -> "特大"; else -> "标准"
        }) { startActivity(Intent(this, FontSizeActivity::class.java)) }
        Rows.endGroup(groups)

        Rows.gap(groups)
        Rows.row(groups, "退出登录", "", danger = true) { confirmLogout() }
        Rows.endGroup(groups)

        status.text = "这三档和字号都存本机（后端没有对应字段），杀掉重进还在。"
    }

    /** 存下来再交给 AppCompat：换肤真的发生时它会自己重建这一页。
     *  但「跟随系统→日间」在系统本来就是浅色的时候不换肤，AppCompat 不重建，
     *  三颗开关会停在按下去之前的样子——所以自己重画一遍。 */
    private fun setNight(mode: Int) {
        Cfg.setNightMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(mode)
        render()
    }

    /** 微信那块的底部面板：退出登录 / 关闭微信 / 取消。
     *  退出只清本机令牌，关闭是退掉整个应用——两件事别合成一颗按钮 */
    private fun confirmLogout() {
        val sheet = Dialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet.setContentView(R.layout.sheet_logout)
        sheet.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        sheet.findViewById<TextView>(R.id.actLogout).setOnClickListener {
            sheet.dismiss()
            Cfg.clearToken(this)
            /* 令牌都没了，那条长连接和挂在上面的通知、角标要一并收掉 */
            NotifyService.stop(this)
            startActivity(Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        sheet.findViewById<TextView>(R.id.actClose).setOnClickListener {
            sheet.dismiss()
            finishAffinity()
        }
        sheet.findViewById<TextView>(R.id.actCancel).setOnClickListener { sheet.dismiss() }
        sheet.show()
    }
}
