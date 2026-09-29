package com.chat.message.config;

import com.datastax.oss.driver.api.core.CqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时把 Cassandra 驱动热起来。
 *
 * 不预热的话，**每次重启后用户发的第一条消息要等 5.14 秒**（实测：
 * 冷实例第一次 5.144s、第二三次 5ms；而且这条和 MQ 通不通无关，
 * broker 正常时第一次同样是 5.1s）。这点代价没有理由摊在一条真实消息上。
 */
@Component
public class CassandraWarmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CassandraWarmup.class);

    private final CqlSession session;

    public CassandraWarmup(CqlSession session) {
        this.session = session;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 真正的元凶在这，不在写库：驱动第一次生成 timeuuid 时要算 node id，
        // 会逐个网卡做反向 DNS（Uuids.getAllLocalAddresses → getCanonicalHostName）。
        // 这台 WSL2 机器上有条 IPv6 没有 PTR 记录，那一下就是 5 秒，而且是一个静态容器
        // 里的一次性开销 —— 不在这儿热掉，就会精确地落在某个用户发的第一条消息上。
        long t0 = System.nanoTime();
        com.datastax.oss.driver.api.core.uuid.Uuids.timeBased();
        long uuidMs = (System.nanoTime() - t0) / 1_000_000;

        long t1 = System.nanoTime();
        try {
            session.execute("select conversation_id from messages limit 1");
            log.info("warmup ok: uuid {} ms, first query {} ms", uuidMs,
                    (System.nanoTime() - t1) / 1_000_000);
        } catch (Exception e) {
            // 热不起来不算启动失败：第一条消息照样会去建连接，只是慢一次
            log.warn("Cassandra warmup failed after uuid {} ms: {}", uuidMs, e.toString());
        }
    }
}
