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
        Rows.row(groups, "退出登录", "", danger = true) { confirmLogout() }
        Rows.endGroup(groups)

        status.text = "后端地址和网关端口都在项目配置里给（编译期 API_BASE），这一页不提供改的地方。"
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
