package com.chat.message.search;

import com.chat.message.model.Message;
import com.chat.message.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一期实现：按日分区往回扫、逐条比子串。
 * <p>
 * 为什么不是索引：Cassandra 这边没有全文索引，要真做得另起一张分词表（中文还得切二元组），
 * 写一条消息要多写几十行 —— 在 500 人这个量级上先把代价闸门做对更值：
 * 扫多少天、每天翻多少条，两个都是配置，超了就如实回 {@code truncated=true}，
 * 让界面说"这是最近 N 天里的结果"而不是"没有更多了"。
 */
@Component
public class KeywordSearchProvider implements SearchProvider {

    /** 一次最多往回多少天：再多就不是"搜一下"而是扫库了 */
    private static final int MAX_DAYS = 90;

    private final MessageRepository repository;
    private final int rowsPerDay;

    public KeywordSearchProvider(MessageRepository repository,
                                 @Value("${search.rows-per-day:500}") int rowsPerDay) {
        this.repository = repository;
        this.rowsPerDay = Math.max(10, Math.min(rowsPerDay, 2000));
    }

    @Override
    public String name() {
        return "keyword";
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Result search(Request request) {
        long t0 = System.nanoTime();
        String needle = request.keyword() == null ? "" : request.keyword().trim().toLowerCase(Locale.ROOT);
        int limit = Math.max(1, Math.min(request.limit(), 200));
        int days = Math.max(1, Math.min(request.days(), MAX_DAYS));

        List<Hit> hits = new ArrayList<>();
        LocalDate today = LocalDate.now();
        int scanned = 0;
        // 窗口用完了还没凑满 limit，或者凑满了但后面还有天没看 —— 都算截断，界面要说
        boolean truncated = false;

        for (int i = 0; i < days; i++) {
            LocalDate date = today.minusDays(i);
            List<Message> rows = repository.findByConversationIdAndDate(
                    request.conversationId(), date, rowsPerDay);
            scanned += rows.size();
            if (rows.size() >= rowsPerDay) {
                truncated = true;   // 这一天的分区被翻满了，更早的还没看
            }
            // 分区内是 message_id DESC（新→旧），正好是结果想要的顺序
            for (Message m : rows) {
                if (needle.isEmpty() || (m.getContent() != null
                        && m.getContent().toLowerCase(Locale.ROOT).contains(needle))) {
                    hits.add(toHit(m));
                    if (hits.size() >= limit) {
                        break;
                    }
                }
            }
            if (hits.size() >= limit) {
                truncated = truncated || i < days - 1;
                break;
            }
        }

        long ms = (System.nanoTime() - t0) / 1_000_000;
        return new Result(hits, scanned, truncated, ms, name());
    }

    private Hit toHit(Message m) {
        return new Hit(m.getConversationId(), String.valueOf(m.getMessageId()), m.getMessageDate(),
                m.getSenderId(), m.getMessageType(), m.getContent(),
                m.getCreateTime() == null ? Instant.EPOCH : m.getCreateTime());
    }
}
