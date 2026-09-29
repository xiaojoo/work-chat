package com.chat.message.search;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 消息检索的插拔口。一期只有 {@link KeywordSearchProvider}（本机扫分区），
 * 后面接 WeKnora 就再写一个实现、按配置换掉，调用方一行不改 ——
 * 所以这里刻意不放"关键词/向量"这种实现细节 leak 出来的方法。
 */
public interface SearchProvider {

    /** 给界面和日志看的名字：keyword / weknora */
    String name();

    /** 这个后端现在能不能用（配置没给就是不能用，别到查询时才报错） */
    boolean available();

    Result search(Request request);

    /**
     * @param conversationId 会话 id，一期只做单会话内检索（跨会话要先把成员判定搬到这一层）
     * @param keyword        查询词，空串由实现自己决定是"全量倒序"还是拒绝
     * @param limit          最多回几条
     * @param days           往回扫多少个日分区：这是代价的闸门，不是相关性参数
     */
    record Request(String conversationId, String keyword, int limit, int days) {}

    /**
     * @param truncated 扫到窗口头还没扫完 —— 界面要如实说"这是最近 N 天里的结果"，
     *                  不能让用户以为"没有更多了"
     */
    record Result(List<Hit> hits, int scanned, boolean truncated, long tookMs, String provider) {}

    /** 一条命中。messageDate 是分区日期：客户端要点跳转就得拿它去定位分区 */
    record Hit(String conversationId, String messageId, LocalDate messageDate,
               Long senderId, String messageType, String content, Instant createTime) {}
}
