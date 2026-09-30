package org.liuym.flowerv1springboot.engineering;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.TokenBucketRateLimiter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L04 令牌桶：突发额度、稳态补充、拒绝时的 Retry-After 建议值，以及「不同 key 互不影响」。
 * 时钟是注入进去的，所以这里不需要 sleep，也不会有时间抖动。
 */
class TokenBucketRateLimiterTest {

    /** 可控时钟：每次 tick 前进给定毫秒 */
    private static final class Clock {
        private final AtomicLong nanos = new AtomicLong(0);

        long read() {
            return nanos.get();
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }
    }

    private static TokenBucketRateLimiter limiter(Clock clock) {
        return new TokenBucketRateLimiter(1000, Duration.ofMinutes(5), clock::read);
    }

    @Test
    void 突发额度内全部放行超出即拒() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire("login:ip1", 3, 1).allowed(), "第 " + (i + 1) + " 次应在突发额度内");
        }
        var denied = limiter.tryAcquire("login:ip1", 3, 1);
        assertFalse(denied.allowed());
        assertEquals(0, denied.remaining());
        //  refill 1/s，余额从 0 补到 1 需要 1 秒
        assertEquals(1, denied.retryAfterSeconds());
    }

    @Test
    void 按补充速率随时间回血() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        // 容量 5、每 10 秒补 1 个：先花光
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("claim:u1", 5, 0.1).allowed());
        }
        assertFalse(limiter.tryAcquire("claim:u1", 5, 0.1).allowed());
        // 过 9 秒还补不满一枚
        clock.advanceMillis(9_000);
        assertFalse(limiter.tryAcquire("claim:u1", 5, 0.1).allowed());
        // 第 10 秒到位
        clock.advanceMillis(1_500);
        assertTrue(limiter.tryAcquire("claim:u1", 5, 0.1).allowed());
    }

    @Test
    void 长期不用不会把突发额度囤满() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        assertTrue(limiter.tryAcquire("order:u2", 4, 0.1).allowed());
        // 挂机一天：余额按容量封顶，回来仍然只有 4 次突发，而不是攒出几十次
        clock.advanceMillis(24L * 3600 * 1000);
        for (int i = 0; i < 4; i++) {
            assertTrue(limiter.tryAcquire("order:u2", 4, 0.1).allowed());
        }
        assertFalse(limiter.tryAcquire("order:u2", 4, 0.1).allowed());
    }

    @Test
    void 不同key各自独立() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        assertTrue(limiter.tryAcquire("a:1", 1, 0.1).allowed());
        assertFalse(limiter.tryAcquire("a:1", 1, 0.1).allowed());
        assertTrue(limiter.tryAcquire("a:2", 1, 0.1).allowed(), "换一个人不该被上一个人的额度连坐");
    }

    @Test
    void 拒绝时的等待秒数不会给出零让前端立刻重试() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        assertTrue(limiter.tryAcquire("k", 1, 0.5).allowed());
        var denied = limiter.tryAcquire("k", 1, 0.5);
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterSeconds() >= 1, "Retry-After 必须至少 1 秒：" + denied.retryAfterSeconds());
    }

    @Test
    void available只看不扣() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        assertEquals(3, limiter.available("watch", 3, 1));
        assertTrue(limiter.tryAcquire("watch", 3, 1).allowed());
        assertEquals(2, limiter.available("watch", 3, 1));
        assertTrue(limiter.tryAcquire("watch", 3, 1).allowed());
        assertTrue(limiter.tryAcquire("watch", 3, 1).allowed());
        assertEquals(0, limiter.available("watch", 3, 1));
        assertFalse(limiter.tryAcquire("watch", 3, 1).allowed(), "看过余额不该白送一次额度");
    }

    @Test
    void reset让被误伤的key立刻恢复() {
        Clock clock = new Clock();
        TokenBucketRateLimiter limiter = limiter(clock);
        assertTrue(limiter.tryAcquire("vip", 1, 0.1).allowed());
        assertFalse(limiter.tryAcquire("vip", 1, 0.1).allowed());
        limiter.reset("vip");
        assertTrue(limiter.tryAcquire("vip", 1, 0.1).allowed());
        assertTrue(limiter.trackedKeys() >= 1);
    }
}
