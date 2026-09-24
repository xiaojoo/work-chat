package com.chat.user.util;

import java.util.function.LongSupplier;

/**
 * 雪花 ID：41 位毫秒时间戳 | 4 位节点号 | 8 位同毫秒序列，纪元 2012-01-01。
 *
 * 三条取值不是随手抄的公开写法，理由如下：
 * - 位宽压到 53（上限 2^53-1）而不是雪花的 64：这些 ID 会以 JSON number 发给前端，
 *   前端还有 Number()/parseInt 的用法，超过 Number.MAX_SAFE_INTEGER 会被静默改写。
 * - 纪元取 2012 而不是项目起始年：历史主键是 毫秒×1000（当前约 1.79e15），本生成器
 *   从今天起产出约 1.90e15 并单调递增，永远不会落回老 ID 已经占用的那段区间。
 * - 节点号 4 位 = 16 个实例上限，按当前服务数量留了足够余量；分库或横向扩容超了要改位宽。
 */
public final class IdGenerator {

    static final long EPOCH_MILLIS = 1325376000000L;
    private static final int TIMESTAMP_BITS = 41;
    private static final int WORKER_BITS = 4;
    private static final int SEQUENCE_BITS = 8;

    public static final long MAX_WORKER_ID = (1L << WORKER_BITS) - 1;

    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_TIMESTAMP = (1L << TIMESTAMP_BITS) - 1;
    private static final int SHIFT = WORKER_BITS + SEQUENCE_BITS;

    static {
        // 改任何一位宽度都会把 ID 顶出 JS 安全整数，宁可类加载就炸，不要让前端静默拿到另一个数
        if (TIMESTAMP_BITS + WORKER_BITS + SEQUENCE_BITS > 53) {
            throw new ExceptionInInitializerError("ID 位宽合计 "
                    + (TIMESTAMP_BITS + WORKER_BITS + SEQUENCE_BITS) + " 超过 53，会超出 Number.MAX_SAFE_INTEGER");
        }
    }

    /** 回拨在这个幅度内自旋等它追上来（NTP 慢调）；超过就直接拒绝发号（手动改表/时区错乱）。 */
    private static final long MAX_BACKWARD_MILLIS = 5L;

    private final long workerId;
    private final LongSupplier clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public IdGenerator(long workerId) {
        this(workerId, System::currentTimeMillis);
    }

    IdGenerator(long workerId, LongSupplier clock) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId 必须在 0.." + MAX_WORKER_ID + " 之间，当前 " + workerId);
        }
        this.workerId = workerId;
        this.clock = clock;
    }

    public synchronized long nextId() {
        long now = clockMillis();

        if (now < lastTimestamp) {
            long backward = lastTimestamp - now;
            if (backward > MAX_BACKWARD_MILLIS) {
                throw new IllegalStateException("时钟回拨 " + backward + "ms，拒绝发号");
            }
            now = waitUntil(lastTimestamp);
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                now = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = now;
        return (now << SHIFT) | (workerId << SEQUENCE_BITS) | sequence;
    }

    /** 时间戳字段为 41 位，2138 年前后耗尽（纪元 2012 起算 69 年），届时换纪元而不是悄悄回绕。 */
    private long clockMillis() {
        long now = clock.getAsLong() - EPOCH_MILLIS;
        if (now < 0) {
            throw new IllegalStateException("系统时间早于 ID 纪元 " + EPOCH_MILLIS);
        }
        if (now > MAX_TIMESTAMP) {
            throw new IllegalStateException("ID 纪元已耗尽，需要更换 EPOCH_MILLIS");
        }
        return now;
    }

    private long waitUntil(long notBefore) {
        long now = clockMillis();
        while (now < notBefore) {
            now = clockMillis();
        }
        return now;
    }
}
