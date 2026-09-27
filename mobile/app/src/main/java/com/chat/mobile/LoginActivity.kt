package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val server = findViewById<EditText>(R.id.server)
        val account = findViewById<EditText>(R.id.account)
        val password = findViewById<EditText>(R.id.password)
        val submit = findViewById<Button>(R.id.submit)
        val status = findViewById<TextView>(R.id.status)

        // 地址记本机：手机连开发机要填局域网 IP，每次重打太烦
        server.setText(Cfg.apiBase(this))
        // 预填过的框，点下去要先全选——否则新字是接在后面，实测拼出过 127.0.0.1:8081127.0.0.1:8081
        server.setSelectAllOnFocus(true)

        /* 有令牌就直接进列表：手机上是"看一眼"的端，每次冷启动都重打一遍口令等于没做。
           令牌过期会在列表页报"拉取失败"，那边有「退出」可以回到这里，不会出不去 */
        if (Cfg.token(this).isNotEmpty() && Cfg.userId(this).isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        submit.setOnClickListener {
            val base = Cfg.normalize(server.text.toString())
            if (base.isEmpty()) { status.text = "先填服务器地址"; return@setOnClickListener }
            Cfg.setApiBase(this, server.text.toString())

            val user = account.text.toString().trim()
            val pass = password.text.toString()
            if (user.isEmpty() || pass.isEmpty()) { status.text = "用户名和密码都要填"; return@setOnClickListener }

            submit.isEnabled = false
            status.text = getString(R.string.logging_in)
            // 网络不能在主线程做，这里就一个后台线程；基线阶段不引协程，少一个版本矩阵
            thread(name = "login") {
                try {
                    val s = Api(base).login(user, pass)
                    Cfg.setToken(this, s.token, s.userId, s.username)
                    runOnUiThread {
                        submit.isEnabled = true
                        status.text = "登录成功 ${s.username}"
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        submit.isEnabled = true
                        status.text = "登录失败：" + e.message
                    }
                }
            }
        }
    }
}
