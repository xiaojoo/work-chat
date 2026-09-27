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

        // 地址不在这里展示：项目配置里给（编译期 API_BASE）。这格只是逃生口——
        // 留空就用项目配置，填了才存成本机覆盖值（换机器临时连一下，不用重打包）
        server.setSelectAllOnFocus(true)

        /* 有令牌就直接进列表：手机上是"看一眼"的端，每次冷启动都重打一遍口令等于没做。
           令牌过期会在列表页报"拉取失败"，那边有「退出」可以回到这里，不会出不去 */
        if (Cfg.token(this).isNotEmpty() && Cfg.userId(this).isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        submit.setOnClickListener {
            val raw = server.text.toString()
            // 空=退回项目配置（setApiBase("") 存的就是空串，apiBase 读到空会 fallback）
            Cfg.setApiBase(this, raw)
            val base = Cfg.apiBase(this)
            if (base.isEmpty()) { status.text = "项目配置里没有后端地址"; return@setOnClickListener }

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
