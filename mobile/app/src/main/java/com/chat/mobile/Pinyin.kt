package com.chat.mobile

import java.text.Collator
import java.util.Locale

/**
 * 中文姓名按拼音首字母归组，照搬前端 src/utils/pinyin.js 的边界字表。
 * 那边注释写得很清楚：Intl 的 zh 拼音排序在这台机器上实际是 ICU 默认序，
 * 但汉字确实按拼音排，所以这套边界字可用。Android 侧 java.text.Collator 也是 ICU，
 * 所以同一份表能直接搬——但"能搬"不等于"搬对了"，归组结果要在真机上打出来对一遍。
 */
object Pinyin {

    private val BOUND = listOf(
        "A" to "阿", "B" to "八", "C" to "擦", "D" to "哒", "E" to "蛾", "F" to "发", "G" to "噶",
        "H" to "哈", "J" to "击", "K" to "喀", "L" to "垃", "M" to "妈", "N" to "拿", "O" to "哦",
        "P" to "啪", "Q" to "期", "R" to "然", "S" to "撒", "T" to "塌", "W" to "挖", "X" to "昔",
        "Y" to "压", "Z" to "匝")

    // 多音字姓氏：排序只认常用读音，这些字当姓时读另一个音
    private val SURNAME = mapOf(
        "曾" to "Z", "解" to "X", "单" to "S", "区" to "O", "查" to "Z", "仇" to "Q", "会" to "G",
        "朴" to "P", "覃" to "T", "召" to "S", "乐" to "Y", "尉" to "Y", "折" to "S", "秘" to "B")

    private val coll: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE)

    fun initial(name: String?): String {
        val s = (name ?: "").trim()
        if (s.isEmpty()) return "#"
        val c0 = s[0]
        if (c0 in 'a'..'z') return c0.uppercase()
        if (c0 in 'A'..'Z') return c0.toString()
        if (c0.code < 128) return "#"
        SURNAME[c0.toString()]?.let { return it }
        var letter = "#"
        for ((l, b) in BOUND) if (coll.compare(s, b) >= 0) letter = l
        return letter
    }

    fun compare(a: String, b: String): Int = coll.compare(a, b)
}
