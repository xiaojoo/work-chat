package com.chat.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.concurrent.thread

/** 「我」：头像 + 昵称 + @username，下面只列接口真给的字段，最后常驻一颗退出 */
class MeFragment : Fragment() {

    private lateinit var ava: TextView
    private lateinit var name: TextView
    private lateinit var account: TextView
    private lateinit var fields: LinearLayout
    private lateinit var status: TextView

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_me, c, false)

    override fun onViewCreated(v: View, s: Bundle?) {
        ava = v.findViewById(R.id.ava)
        name = v.findViewById(R.id.name)
        account = v.findViewById(R.id.account)
        fields = v.findViewById(R.id.fields)
        status = v.findViewById(R.id.status)

        name.text = Cfg.username(requireContext())
        ava.text = Cfg.username(requireContext()).take(1).uppercase()

        v.findViewById<Button>(R.id.logout).setOnClickListener {
            Cfg.clearToken(requireContext())
            startActivity(Intent(requireContext(), LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            requireActivity().finish()
        }
    }

    override fun onResume() {
        super.onResume()
        val base = Cfg.apiBase(requireContext()); val tk = Cfg.token(requireContext()); val uid = Cfg.userId(requireContext())
        if (base.isEmpty() || tk.isEmpty() || uid.isEmpty()) { status.text = "还没登录"; return }
        status.text = "读取资料中…"
        thread(name = "profile") {
            try {
                val p = Api(base, tk).profile(uid)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    ava.text = (p.nickname.ifEmpty { p.username }).take(1).uppercase()
                    name.text = p.nickname.ifEmpty { p.username }
                    account.text = "@${p.username} · ID ${p.id}"
                    fields.removeAllViews()
                    // 只列接口真给、且真有值的：remark/location 恒空且写不进去，不摆
                    listOf("部门" to p.department, "职务" to p.position, "邮箱" to p.email,
                        "电话" to p.phone, "个性签名" to p.bio)
                        .filter { it.second.isNotEmpty() }
                        .forEach { addRow(it.first, it.second) }
                    if (p.department.isEmpty() && p.position.isEmpty() && p.email.isEmpty() &&
                        p.phone.isEmpty() && p.bio.isEmpty()) addRow("资料", "都还没填")
                    status.text = "资料来自 GET /api/user/{id}"
                }
            } catch (e: Exception) {
                activity?.runOnUiThread { if (isAdded) status.text = "读取失败：${e.message}" }
            }
        }
    }

    private fun addRow(label: String, value: String) {
        val v = LayoutInflater.from(requireContext()).inflate(R.layout.item_contact, fields, false)
        v.findViewById<TextView>(R.id.ava).visibility = View.GONE
        v.findViewById<TextView>(R.id.name).text = label
        v.findViewById<TextView>(R.id.sub).text = value
        v.isClickable = false
        fields.addView(v)
    }
}
