package com.chat.message.search;

import com.chat.message.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 归纳这一档的调度：挑后端 + 把原文从库里读出来。
 * <p>
 * 原文一律由服务端按 {@code (conversationId, messageDate, messageId)} 去读，
 * 客户端只给这三个键 —— 提示词的内容必须来自库，不能让调用方想塞什么就塞什么。
 */
@Service
public class SummaryService {

    private static final Logger log = LoggerFactory.getLogger(SummaryService.class);
    /** 一次最多归纳多少条：再多既没必要，也在烧共享的模型上下文 */
    private static final int MAX_ITEMS = 30;

    private final List<SummaryProvider> providers;
    private final MessageRepository repository;
    private final String preferred;

    public SummaryService(List<SummaryProvider> providers, MessageRepository repository,
                          @Value("${summary.provider:llm}") String preferred) {
        this.providers = providers;
        this.repository = repository;
        this.preferred = preferred;
    }

    public SummaryProvider pick() {
        SummaryProvider wanted = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(preferred)).findFirst().orElse(null);
        if (wanted != null && wanted.available()) {
            return wanted;
        }
        SummaryProvider usable = providers.stream().filter(SummaryProvider::available).findFirst().orElse(null);
        if (usable != null) {
            if (!usable.name().equalsIgnoreCase(preferred)) {
                log.warn("summary provider {} unavailable, falling back to {}", preferred, usable.name());
            }
            return usable;
        }
        // 一个可用的都没有：回那个配了的，让它把"没配模型服务"这句实话讲出来，而不是在这里 NPE
        return wanted != null ? wanted : providers.get(0);
    }

    public SummaryProvider.Outcome summarize(String conversationId, String keyword,
                                             List<Map<String, String>> items) {
        List<SummaryProvider.Quote> quotes = new ArrayList<>();
        if (items != null) {
            for (Map<String, String> it : items) {
                if (quotes.size() >= MAX_ITEMS) {
                    break;
                }
                String id = it.get("id");
                String date = it.get("date");
                if (id == null || date == null) {
                    continue;
                }
                try {
                    repository.findById(conversationId, LocalDate.parse(date), UUID.fromString(id))
                            .ifPresent(m -> quotes.add(new SummaryProvider.Quote(
                                    String.valueOf(m.getSenderId()), m.getContent())));
                } catch (IllegalArgumentException ignore) {
                    // 客户端给的 id 或日期不合法：这一条丢掉，不拿它去问模型
                }
            }
        }
        return pick().summarize(quotes, keyword);
    }
}
