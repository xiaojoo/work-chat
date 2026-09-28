package com.chat.mobile

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
        /* 「界面与显示」这一组照他那张图。三项夜间模式和字体大小都是本机的事，
           后端没有对应字段——所以存 SharedPreferences，杀掉重进还在。
           开关=跟随系统；下面两行是手选，选任一个就把开关拨回关（跟随和手选不能同时成立）。 */
        val nm = Cfg.nightMode(this)
        val follow = nm == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        val nightNow = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        Rows.toggle(groups, "夜间模式跟随系统", on = follow, enabled = true, labelSp = 13f) {
            setNight(if (follow) AppCompatDelegate.MODE_NIGHT_NO
                     else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
        val dayOn = if (follow) !nightNow else nm == AppCompatDelegate.MODE_NIGHT_NO
        val nightOn = if (follow) nightNow else nm == AppCompatDelegate.MODE_NIGHT_YES
        mark(Rows.row(groups, "日间模式", if (dayOn) "✓" else "") {
            setNight(AppCompatDelegate.MODE_NIGHT_NO)
        }, dayOn)
        mark(Rows.row(groups, "夜间模式", if (nightOn) "✓" else "") {
            setNight(AppCompatDelegate.MODE_NIGHT_YES)
        }, nightOn)
        Rows.row(groups, "字体大小", when (Cfg.fontScale(this)) {
            1.15f -> "大"; 1.3f -> "特大"; else -> "标准"
        }) { startActivity(Intent(this, FontSizeActivity::class.java)) }
        Rows.row(groups, "主页底部导航栏设置", "", onClick = null).apply { alpha = 0.4f }
        Rows.endGroup(groups)

        Rows.gap(groups)
        Rows.row(groups, "退出登录", "", danger = true) { confirmLogout() }
        Rows.endGroup(groups)

        status.text = "这三档和字号都存本机（后端没有对应字段）。底部导航四页是结构，没有可配置项，所以压淡。"
    }

    /** 选中那档的 ✓ 用品牌蓝，没选中的空着 */
    private fun mark(v: View, on: Boolean) {
        if (on) v.findViewById<TextView>(R.id.value)?.setTextColor(getColor(R.color.brand))
    }

    /** 存下来再交给 AppCompat：它会自己把在显示的页面重建，不用我手动 recreate */
    private fun setNight(mode: Int) {
        Cfg.setNightMode(this, mode)
        AppCompatDelegate.setDefaultNightMode(mode)
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
