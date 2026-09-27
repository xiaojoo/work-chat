package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 微信那个「设置」页的形状：灰底上几组白行，组标题 13sp，行末浅线从文字起头。
 * 组里只摆后端/本机真有的东西：个人信息（PUT /api/user/profile 那一套）、
 * 服务器地址与消息网关（本机偏好）、退出登录。微信的通知/通用/关于这里没有对应接口，不抄文案。
 */
class SettingsActivity : AppCompatActivity() {

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
        Rows.row(groups, "服务器地址", Cfg.apiBase(this), "还没设置") { editServer() }
        Rows.row(groups, "消息网关", Cfg.wsBase(this), "还没设置", onClick = null)
        Rows.endGroup(groups)

        Rows.gap(groups)
        Rows.row(groups, "退出登录", "", danger = true) { confirmLogout() }
        Rows.endGroup(groups)

        status.text = "服务器地址存在本机；网关端口由它推导（同宿主 :${Cfg.GW_PORT}）。" +
            "换地址后下拉刷新会话列表即可，不用重装。"
    }

    private fun editServer() {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(Cfg.apiBase(this@SettingsActivity))
            hint = "例如 192.168.31.5"
            setSelectAllOnFocus(true)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("服务器地址")
            .setView(input)
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("保存") { d, _ ->
                Cfg.setApiBase(this, input.text.toString())
                d.dismiss()
                render()
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("退出登录")
            .setMessage("退出后要重新输入账号密码，本机只清掉令牌，不改服务器上的资料。")
            .setNegativeButton("取消") { d, _ -> d.dismiss() }
            .setPositiveButton("退出") { _, _ ->
                Cfg.clearToken(this)
                startActivity(Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            .show()
    }
}
