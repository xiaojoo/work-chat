package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.concurrent.thread

class LoginActivity : ScaleActivity() {

    private var passVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val server = findViewById<EditText>(R.id.server)
        val account = findViewById<EditText>(R.id.account)
        val password = findViewById<EditText>(R.id.password)
        val submit = findViewById<Button>(R.id.submit)
        val status = findViewById<TextView>(R.id.status)
        val eye = findViewById<ImageButton>(R.id.togglePass)

        // 地址不在这里展示：项目配置里给（编译期 API_BASE）。这格只是逃生口——
        // 留空就用项目配置，填了才存成本机覆盖值（换机器临时连一下，不用重打包）。
        // 但"留空"到底等于连哪儿，得让人看得见，所以把当前生效的那个地址写进占位文案
        server.setSelectAllOnFocus(true)
        // 只把"当前生效的那个地址"给出来。原来把整句提示拼在前面，实测在 393dp 宽、
        // 一格 948px 的框里放不下，最要紧的那截地址被切掉了 —— 那就等于没写
        server.hint = "项目配置：" + Cfg.apiBase(this)

        /* 有令牌就直接进列表：手机上是"看一眼"的端，每次冷启动都重打一遍口令等于没做。
           令牌过期会在列表页报"拉取失败"，那边有「退出」可以回到这里，不会出不去 */
        if (Cfg.token(this).isNotEmpty() && Cfg.userId(this).isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // 上次登进去的号留下当默认值；密码不记（记在明文 prefs 里等于没记）
        val last = Cfg.username(this)
        if (last.isNotEmpty()) account.setText(last)

        /* 两颗都填了才按得动：原来空着点下去才在底下冒一句"用户名和密码都要填"，
           那是把"现在能不能点"这件事推到一次失败之后才说。灰的那档用的是发送那颗同一套
           brand_dim，不是消失、也不是变白 */
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                submit.isEnabled = account.text.toString().trim().isNotEmpty() &&
                        password.text.toString().isNotEmpty()
            }
        }
        account.addTextChangedListener(watcher)
        password.addTextChangedListener(watcher)
        watcher.afterTextChanged(null)

        eye.setOnClickListener {
            passVisible = !passVisible
            val start = password.selectionEnd
            password.inputType = if (passVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.setSelection(minOf(start, password.text.length))
            eye.setImageResource(if (passVisible) R.drawable.ic_eye_open else R.drawable.ic_eye_shut)
        }

        val doLogin = { login(server, account, password, submit, status) }
        submit.setOnClickListener { doLogin() }
        // 软键盘那颗"完成"就是点登录：手机上来回找按钮比敲字麻烦
        password.setOnEditorActionListener { _, actionId, key ->
            val enterPressed = key != null && key.keyCode == KeyEvent.KEYCODE_ENTER &&
                    key.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enterPressed) {
                if (submit.isEnabled) doLogin()
                true
            } else false
        }
    }

    private fun login(server: EditText, account: EditText, password: EditText,
                      submit: Button, status: TextView) {
        val raw = server.text.toString()
        // 空=退回项目配置（setApiBase("") 存的就是空串，apiBase 读到空会 fallback）
        Cfg.setApiBase(this, raw)
        val base = Cfg.apiBase(this)
        if (base.isEmpty()) { say(status, "项目配置里没有后端地址", R.color.danger); return }

        val user = account.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) { say(status, "用户名和密码都要填", R.color.danger); return }

        submit.isEnabled = false
        say(status, getString(R.string.logging_in), R.color.ink_dim)
        // 网络不能在主线程做，这里就一个后台线程；基线阶段不引协程，少一个版本矩阵
        thread(name = "login") {
            try {
                val s = Api(base).login(user, pass)
                Cfg.setToken(this, s.token, s.userId, s.username, s.refreshToken)
                runOnUiThread {
                    say(status, "登录成功 " + s.username, R.color.ok)
                    startActivity(Intent(this, MainActivity::class.java))
                }
            } catch (e: Exception) {
                runOnUiThread {
                    submit.isEnabled = true
                    say(status, whyFailed(e), R.color.danger)
                }
            }
        }
    }

    /**
     * 把异常翻成一句人话。后端回的那句 error 原文优先（它已经是在说事了），
     * 剩下的按"连不上/没答话/号不对"分三类各给一句 —— 原来一律拼 e.message，
     * 屏幕上出现的是 "401 {"error":"用户名或密码错误"}" 这种，等于把传输层摊给人看。
     */
    private fun whyFailed(e: Exception): String {
        val m = e.message ?: ""
        when {
            e is UnknownHostException -> return "连不上这个地址（域名解析不了）"
            e is SocketTimeoutException || e is ConnectException -> return "连不上后端（网络不通，或那个地址上没有服务）"
        }
        // 后端那句 error 原文就是在说事，直接用它（原来一路拼 e.message，屏幕上出现的是
        // 400 {"error":"用户名或密码错误"} 这种整包）
        Regex("\"error\"\\s*:\\s*\"([^\"]*)\"").find(m)?.groupValues?.get(1)?.let { if (it.isNotEmpty()) return it }
        if (m.startsWith("401") || m.startsWith("400")) return "用户名或密码不对"
        return if (m.isEmpty()) "登录失败（后端没给原因）" else "登录失败：" + m
    }

    private fun say(status: TextView, text: String, colorRes: Int) {
        status.text = text
        status.setTextColor(getColor(colorRes))
        // 播报显式给一次：不挂 liveRegion（挂了 uiautomator 就读不到这句，见布局里那段注释）
        status.announceForAccessibility(text)
    }
}
