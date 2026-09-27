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
                        startActivity(Intent(this, ConversationListActivity::class.java))
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
