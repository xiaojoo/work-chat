package com.chat.message.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 本机模型服务那一档：OpenAI 兼容的 {@code /chat/completions}。
 * <p>
 * 三条硬约束，都是踩过才知道要的：
 * <ul>
 *   <li>{@code llm.base-url} 默认留空。留空就是不可用 —— 不把这台机器上跑着什么烘进版本库，
 *       也不让"没模型"退化成一句看起来像答案的假话。</li>
 *   <li>喂进去的原文只从库里读（{@code Quote} 由服务端组装），客户端传上来的字符串一律不进提示词。</li>
 *   <li>提示词里写死"只用给出的原文"。检索归纳最容易出的事故是模型自己补内容，
 *       补出来的东西会以"AI 摘要"的名义被当成聊天记录读。</li>
 * </ul>
 */
@Component
public class LlmSummaryProvider implements SummaryProvider {

    private static final Logger log = LoggerFactory.getLogger(LlmSummaryProvider.class);
    /**
     * 一次最多喂多少条、每条截多长。这两个数不是估的，是在本机引擎上试出来的：
     * 8 条 × 200 字时它光在 reasoning 里复述清单就烧完 1500 token（finish=length、content 空）；
     * 5 条 × 120 字 + 那句"不要复述清单"才在 1300 token 内答完。
     */
    private static final int MAX_QUOTES = 5;
    private static final int MAX_CHARS_PER_QUOTE = 120;

    private final ObjectMapper json = JsonMapper.builder().build();
    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final int maxTokens;
    private final long requestTimeoutMs;

    public LlmSummaryProvider(@Value("${llm.base-url:}") String baseUrl,
                              @Value("${llm.model:}") String model,
                              @Value("${llm.timeout-ms:60000}") int timeoutMs,
                              @Value("${llm.max-tokens:1500}") int maxTokens) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.model = model == null ? "" : model.trim();
        this.maxTokens = Math.max(32, maxTokens);
        this.requestTimeoutMs = Math.max(5000, timeoutMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(Math.min(this.requestTimeoutMs, 8000))).build();
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public boolean available() {
        return !baseUrl.isEmpty() && !model.isEmpty();
    }

    @Override
    public Outcome summarize(List<Quote> quotes, String keyword) {
        if (!available()) {
            return new Outcome(null, "", 0, "没配模型服务（llm.base-url / llm.model 为空）");
        }
        if (quotes == null || quotes.isEmpty()) {
            return new Outcome(null, "", 0, "没有可归纳的内容");
        }
        long t0 = System.nanoTime();
        StringBuilder body = new StringBuilder();
        int n = 0;
        for (Quote q : quotes) {
            if (++n > MAX_QUOTES) {
                break;
            }
            String text = q.content() == null ? "" : q.content();
            if (text.length() > MAX_CHARS_PER_QUOTE) {
                text = text.substring(0, MAX_CHARS_PER_QUOTE) + "…";
            }
            body.append(n).append(") ").append(q.sender() == null ? "?" : q.sender())
                    .append("：").append(text).append('\n');
        }
        String user = "检索词：" + (keyword == null || keyword.isBlank() ? "（无）" : keyword)
                + "\n以下是这条会话里命中的原文，按时间从新到旧：\n" + body
                + "\n请用不超过 60 个中文字说清这批消息在讲什么。只用上面出现的原文，缺信息就说缺，不要猜。";

        try {
            String payload = json.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content",
                                    "你在归纳聊天软件的检索结果。只允许复述给到的原文，不得补充事实。"),
                            Map.of("role", "user", "content", user)),
                    "max_tokens", maxTokens,
                    "temperature", 0.2,
                    "stream", false));
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (resp.statusCode() != 200) {
                log.warn("llm summarize http {}: {}", resp.statusCode(), snippet(resp.body()));
                return new Outcome(null, model, ms, "模型服务回了 HTTP " + resp.statusCode());
            }
            var root = json.readTree(resp.body());
            var content = root.path("choices").path(0).path("message").path("content");
            String text = content.isString() ? content.asString() : "";
            if (text.isBlank()) {
                return new Outcome(null, model, ms, "模型回了空内容");
            }
            return new Outcome(text.trim(), root.path("model").isString() ? root.path("model").asString() : model, ms, null);
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.warn("llm summarize failed: {}", e.toString());
            return new Outcome(null, model, ms, "模型服务没答上：" + e.getClass().getSimpleName());
        }
    }

    private static String trimSlash(String u) {
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    private static String snippet(String s) {
        return s == null ? "" : s.substring(0, Math.min(s.length(), 200));
    }
}
