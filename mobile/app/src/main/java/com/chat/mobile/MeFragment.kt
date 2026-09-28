package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.concurrent.thread

/**
 * 「我」= 微信那张顶部卡 + 一组设置行。卡点进个人信息，设置点进设置页（退出登录在那儿）。
 * 只列接口真给的字段，微信的通知/通用/关于这里没有后端，不抄。
 */
class MeFragment : Fragment() {

    private lateinit var ava: TextView
    private lateinit var name: TextView
    private lateinit var account: TextView
    private lateinit var groups: LinearLayout
    private lateinit var status: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_me, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        ava = v.findViewById(R.id.ava)
        name = v.findViewById(R.id.name)
        account = v.findViewById(R.id.account)
        groups = v.findViewById(R.id.groups)
        status = v.findViewById(R.id.status)

        name.text = Cfg.username(requireContext())
        ava.text = Cfg.username(requireContext()).take(1).uppercase()

        v.findViewById<View>(R.id.card).setOnClickListener {
            startActivity(Intent(requireContext(), ProfileActivity::class.java))
        }

        Rows.gap(groups)
        /* item_setting 是设置页/个人信息页共用的那份，字号不许在共享件上改（见"共享 layout 按调用方设"），
           所以这一页自己把两档压到工作台的字号：行标题 13sp（= 工作台分组小标题那档）、
           行右值 12sp（= 工作台格子名那档）。 */
        val row = Rows.row(groups, "设置", "") {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        row.findViewById<TextView>(R.id.label)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        row.findViewById<TextView>(R.id.value)?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        Rows.endGroup(groups)
    }

    override fun onResume() {
        super.onResume()
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext())
        val uid = Cfg.userId(requireContext())
        if (base.isEmpty() || tk.isEmpty() || uid.isEmpty()) { status.text = "还没登录"; return }
        status.text = "读取资料中…"
        thread(name = "profile") {
            val res = runCatching { Api(base, tk).profile(uid) }
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                res.onSuccess { p ->
                    val shown = p.nickname.ifEmpty { p.username }
                    ava.text = shown.take(1).uppercase()
                    name.text = shown
                    account.text = "@${p.username}"
                    status.text = "资料来自 GET /api/user/{id}"
                }.onFailure { status.text = "读取失败：${it.message}" }
            }
        }
    }
}
