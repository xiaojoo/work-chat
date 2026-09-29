package com.chat.message.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 选哪个检索后端。配置 {@code search.provider} 按 {@link SearchProvider#name()} 挑，
 * 挑不到或那个后端不可用就退回列表里第一个可用的，并把这件事记一条日志 ——
 * 静默退回会让"接了 WeKnora"这件事变成一个没人知道的假象。
 */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final List<SearchProvider> providers;
    private final String preferred;

    public SearchService(List<SearchProvider> providers,
                         @Value("${search.provider:keyword}") String preferred) {
        this.providers = providers;
        this.preferred = preferred;
    }

    public SearchProvider pick() {
        SearchProvider wanted = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(preferred)).findFirst().orElse(null);
        if (wanted != null && wanted.available()) {
            return wanted;
        }
        SearchProvider fallback = providers.stream().filter(SearchProvider::available).findFirst().orElse(null);
        if (fallback == null) {
            throw new IllegalStateException("没有可用的检索后端（配置 search.provider=" + preferred + "）");
        }
        log.warn("search provider {} unavailable, falling back to {}", preferred, fallback.name());
        return fallback;
    }

    public SearchProvider.Result search(SearchProvider.Request request) {
        return pick().search(request);
    }
}
