package com.chat.mobile

import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 改一个字段的整页（微信「更改名字」那一屏）：顶栏右边是保存，没改动之前置灰——
 * 灰着的那颗是在说"你还没改"，不是死按钮。
 *
 * 只发被改的那一个键：PUT /api/user/profile 按 containsKey 逐条应用，
 * 一次改一个字段就不用担心把别的字段覆盖成读到的旧值。
 */
class EditFieldActivity : EdgeBackActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_edit_field)

        val label = intent.getStringExtra("label") ?: "资料"
        val key = intent.getStringExtra("key") ?: ""
        val original = intent.getStringExtra("value") ?: ""
        val max = intent.getIntExtra("max", 0)

        findViewById<TextView>(R.id.title).text = "更改$label"
        findViewById<TextView>(R.id.hint).text = intent.getStringExtra("hint") ?: ""

        val input = findViewById<EditText>(R.id.input)
        input.inputType = intent.getIntExtra("ime", 0)
        input.setText(original)
        input.setSelection(original.length)
        if (max > 0) input.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(max))

        val save = findViewById<Button>(R.id.save)
        fun armed(on: Boolean) {
            save.isEnabled = on
            save.setTextColor(getColor(if (on) R.color.ink else R.color.ink_dim2))
        }
        armed(false)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(t: Editable?) = armed(t?.toString()?.trim() != original)
        })

        findViewById<Button>(R.id.back).setOnClickListener { finish() }
        save.setOnClickListener {
            val text = input.text.toString().trim()
            save.isEnabled = false
            thread(name = "profile-put") {
                val res = runCatching {
                    Api(Cfg.apiBase(this), Cfg.token(this))
                        .updateProfile(key, text, Cfg.userId(this))
                }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    res.onSuccess {
                        setResult(RESULT_OK)
                        finish()
                    }.onFailure {
                        Toast.makeText(this, "保存失败：${it.message}", Toast.LENGTH_SHORT).show()
                        armed(true)
                    }
                }
            }
        }
    }
}
