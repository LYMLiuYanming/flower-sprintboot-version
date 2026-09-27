package org.liuym.flowerv1springboot.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录防暴力破解：按「IP + 用户名」维度累计失败次数，超过阈值即锁定
 * 使用 Caffeine 本地缓存，多实例部署时切换为 Redis（见 docs 待办 F6）
 */
@Component
public class LoginAttemptService {

    private record Attempt(AtomicInteger failures, Long lockedUntil) {
    }

    private final Cache<String, Attempt> attempts;
    private final int maxFailures;
    private final Duration lockWindow;

    public LoginAttemptService(@Value("${security.login.max-fail:5}") int maxFailures,
                               @Value("${security.login.lock-minutes:15}") int lockMinutes) {
        this.maxFailures = maxFailures;
        this.lockWindow = Duration.ofMinutes(lockMinutes);
        this.attempts = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(lockMinutes, 5L) * 2))
                .maximumSize(100_000)
                .build();
    }

    private static String key(String ip, String username) {
        return (ip == null ? "-" : ip) + "::" + (username == null ? "-" : username.trim().toLowerCase());
    }

    /**
     * @return 剩余锁定秒数，0 表示未被锁定
     */
    public long remainingLockSeconds(String ip, String username) {
        Attempt attempt = attempts.getIfPresent(key(ip, username));
        if (attempt == null || attempt.lockedUntil() == null) {
            return 0;
        }
        long left = attempt.lockedUntil() - System.currentTimeMillis();
        if (left <= 0) {
            attempts.invalidate(key(ip, username));
            return 0;
        }
        return left / 1000;
    }

    public void recordFailure(String ip, String username) {
        String key = key(ip, username);
        Attempt attempt = attempts.get(key, k -> new Attempt(new AtomicInteger(), null));
        int failures = attempt.failures().incrementAndGet();
        if (failures >= maxFailures) {
            attempts.put(key, new Attempt(attempt.failures(), System.currentTimeMillis() + lockWindow.toMillis()));
        }
    }

    public void reset(String ip, String username) {
        attempts.invalidate(key(ip, username));
    }

    public int maxFailures() {
        return maxFailures;
    }
}
