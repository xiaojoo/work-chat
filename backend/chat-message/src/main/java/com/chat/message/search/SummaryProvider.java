package com.chat.message.search;

import java.util.List;

/**
 * 检索结果的归纳口。和 {@link SearchProvider} 一样是插拔的：
 * 一期接本机 llama-server（OpenAI 兼容），后面换别家只改配置不改调用方。
 */
public interface SummaryProvider {

    String name();

    /** 没配模型服务就是不可用 —— 界面要拿到这句实话，把按钮置灰并说清为什么 */
    boolean available();

    Outcome summarize(List<Quote> quotes, String keyword);

    /** 一条要归纳的原文。content 由服务端自己从库里读，不接受客户端传上来的文本 */
    record Quote(String sender, String content) {}

    /** error 非空就是没成，text 此时无意义；model 是实际答话的那个模型名 */
    record Outcome(String text, String model, long tookMs, String error) {}
}
