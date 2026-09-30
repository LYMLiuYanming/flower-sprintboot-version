package org.liuym.flowerv1springboot.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 本地令牌桶限流（L04）：不引 Redis、不引 Guava，用现成的 Caffeine 存每个 key 的桶。
 *
 * <p>为什么是令牌桶而不是固定窗口计数：固定窗口在边界上会放行两倍流量（59 秒末与 01 秒初各放满额），
 * 而登录/下单这类接口的攻击恰好是「卡边界」。令牌桶按 refillPerSecond 连续放额，不需要额外对齐。
 *
 * <p>阈值语义：{@code capacity} 是允许的突发笔数（用户正常手速一次点两下不会触发），
 * {@code refillPerSecond} 是稳态速率。例如 capacity=5 + refill=0.1 表示「可突发 5 次，
 * 之后平均每 10 秒 1 次」——用来限领券这种「进页面连点」与「脚本刷」的分界。
 *
 * <p>并发：桶对象用 synchronized 保护，粒度是单个 key，不同 key 之间无竞争；
 * Caffeine 的 expireAfterAccess 让长期不用的 key 自动回收，不会因攻击者制造海量 key 而涨内存。
 */
public class TokenBucketRateLimiter {

    /**
     * 一次判定结果。
     *
     * @param allowed    是否放行
     * @param retryAfter 建议等待秒数（拒绝时 &gt; 0，放行时为 0），直接写进 Retry-After 头
     * @param remaining  拒绝时的剩余令牌数，用于日志与「还有多少额度」的提示
     */
    public record Decision(boolean allowed, long retryAfterSeconds, int remaining) {

        static Decision allow(int remaining) {
            return new Decision(true, 0, remaining);
        }

        static Decision deny(long retryAfterSeconds, int remaining) {
            return new Decision(false, retryAfterSeconds, remaining);
        }
    }

    /** 桶：tokens 为当前余额，updated 是上次结算的毫秒时间戳 */
    private static final class Bucket {
        private double tokens;
        private long updated;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.updated = now;
        }
    }

    private final Cache<String, Bucket> buckets;
    private final long idleNanosToKeep;
    private final LongSupplier clock;

    /**
     * @param maxSize     同时跟踪的 key 数量上限（超出按 LRU 淘汰，宁可少限也不能撑爆内存）
     * @param idleToKeep  key 空闲多久后可丢弃；必须大于任何规则的补能周期，否则突发额度会被清 0 侧效应放大
     */
    public TokenBucketRateLimiter(long maxSize, Duration idleToKeep) {
        this(maxSize, idleToKeep, System::nanoTime);
    }

    /** 测试注入时钟：把「时间」变成可控入参，单测不必 sleep */
    public TokenBucketRateLimiter(long maxSize, Duration idleToKeep, LongSupplier clock) {
        this.clock = clock;
        this.idleNanosToKeep = Math.max(1L, idleToKeep.toNanos());
        this.buckets = Caffeine.newBuilder()
                .maximumSize(Math.max(16, maxSize))
                .expireAfterAccess(Duration.ofNanos(this.idleNanosToKeep))
                .build();
    }

    /**
     * 取一枚令牌。
     *
     * @param key            桶标识，调用方负责拼（规则名 + IP/用户维度）
     * @param capacity       突发上限（也是初始余额）
     * @param refillPerSecond 稳态每秒补充数，必须 &gt; 0
     */
    public Decision tryAcquire(String key, int capacity, double refillPerSecond) {
        int cap = Math.max(1, capacity);
        double refill = Math.max(0.0001, refillPerSecond);
        long now = clock.getAsLong();
        Bucket bucket = buckets.get(key, k -> new Bucket(cap, now));
        if (bucket == null) {
            return Decision.allow(cap - 1);
        }
        synchronized (bucket) {
            long elapsed = now - bucket.updated;
            if (elapsed > 0) {
                bucket.tokens = Math.min(cap, bucket.tokens + elapsed / 1_000_000_000.0 * refill);
                bucket.updated = now;
            }
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0;
                return Decision.allow((int) Math.floor(bucket.tokens));
            }
            // 差多少令牌就等多久：(1 - tokens) / refill 秒，向上取整避免给出 0 让前端立刻重试打满
            double missing = 1.0 - bucket.tokens;
            long wait = (long) Math.ceil(missing / refill);
            return Decision.deny(Math.max(1, wait), 0);
        }
    }

    /** 只看不扣：诊断接口回答「这个 key 现在还剩多少额度」 */
    public int available(String key, int capacity, double refillPerSecond) {
        Bucket bucket = buckets.getIfPresent(key);
        if (bucket == null) {
            return Math.max(1, capacity);
        }
        synchronized (bucket) {
            long elapsed = clock.getAsLong() - bucket.updated;
            double tokens = elapsed > 0
                    ? Math.min(capacity, bucket.tokens + elapsed / 1_000_000_000.0 * Math.max(0.0001, refillPerSecond))
                    : bucket.tokens;
            return (int) Math.floor(Math.max(0, tokens));
        }
    }

    /** 当前跟踪的 key 数（近似值），用于确认攻击者没有把桶表撑爆 */
    public long trackedKeys() {
        return buckets.estimatedSize();
    }

    /** 手工清掉一个 key（管理员解封、或规则热更新后重置额度） */
    public void reset(String key) {
        buckets.invalidate(key);
    }

    /** 桶表整体清空，仅诊断/测试用 */
    public void clear() {
        buckets.invalidateAll();
    }
}
