package com.chat.mobile

import android.content.Context

/**
 * 工作台那 11 个入口，桌面端 Workbench.vue 侧栏的三组（工作台 / 内容 / AI）同一份，
 * 图标描点也直接搬它那套 path，不另画一份。
 *
 * 放这儿是因为两屏都要用它：工作台上那张网格，和点某个入口进去的那一屏（WorkListActivity）。
 * 抄两份迟早对不上。
 */
object WorkItems {

    data class Tile(val name: String, val icon: Int)
    data class Card(val cap: String, val tintRes: Int, val tiles: List<Tile>)

    fun cards(ctx: Context): List<Card> = listOf(
        Card("工作台", R.color.brand, listOf(
            Tile("首页", R.drawable.ic_wb_home), Tile("我的任务", R.drawable.ic_wb_task),
            Tile("最近内容", R.drawable.ic_wb_inbox), Tile("收藏", R.drawable.ic_wb_star))),
        Card("内容", R.color.ok, listOf(
            Tile("项目", R.drawable.ic_wb_folder), Tile("文档", R.drawable.ic_wb_doc),
            Tile("素材", R.drawable.ic_wb_image), Tile("知识库", R.drawable.ic_wb_book))),
        Card("AI", R.color.warn, listOf(
            Tile("AI 助手", R.drawable.ic_wb_spark), Tile("内容生成", R.drawable.ic_wb_pen),
            Tile("内容分析", R.drawable.ic_wb_chart)))
    )

    /** 摊平成一列表，带上各自所属的组名和图标色 */
    data class Row(val cap: String, val tint: Int, val tile: Tile)
    fun rows(ctx: Context): List<Row> =
        cards(ctx).flatMap { c -> c.tiles.map { Row(c.cap, ctx.getColor(c.tintRes), it) } }
}
