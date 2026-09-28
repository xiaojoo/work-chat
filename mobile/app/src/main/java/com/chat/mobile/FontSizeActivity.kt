package com.chat.mobile

import android.os.Bundle
import android.widget.Button

/**
 * 「设置 → 字体大小」：三档，选中那档打 ✓。
 * 存本机（后端没有这个字段），选完这一页立刻 recreate() 生效；
 * 栈里其它页在他退回时各自对账更新（见 ScaleActivity.onResume）。
 */
class FontSizeActivity : EdgeBackActivity() {

    private val levels = listOf(1f to "标准", 1.15f to "大", 1.3f to "特大")

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.back).apply { text = "‹"; setOnClickListener { finish() } }
        findViewById<android.widget.TextView>(R.id.title)?.text = "字体大小"
        render()
    }

    private fun render() {
        val groups = findViewById<android.widget.LinearLayout>(R.id.groups)
        val now = Cfg.fontScale(this)
        groups.removeAllViews()
        levels.forEach { (f, name) ->
            val v = Rows.row(groups, name, if (f == now) "✓" else "") {
                Cfg.setFontScale(this, f)
                recreate()
            }
            v.findViewById<android.widget.TextView>(R.id.value)?.setTextColor(
                getColor(if (f == now) R.color.brand else R.color.ink_dim))
        }
        Rows.endGroup(groups)
        findViewById<android.widget.TextView>(R.id.status).text =
            "字号存本机，选完这一页立刻变；已经打开的别的页，退回时各自跟上。后端没有这个字段。"
    }
}
