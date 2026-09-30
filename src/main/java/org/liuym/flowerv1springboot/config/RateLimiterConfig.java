package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.common.TokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 限流桶单独成配置：原来这个 @Bean 写在 {@link RateLimitConfig} 里，而 RateLimitConfig 的构造器
 * 又依赖 RateLimitInterceptor，拦截器再回头要这个桶 —— 形成 config ⇄ interceptor 自环，
 * Spring 默认禁止循环引用，直接 APPLICATION FAILED TO START。拆出来依赖就变成单向。
 */
@Configuration
public class RateLimiterConfig {

    /**
     * 桶表容量默认 20000 个 key：按「同时在线 ~2000 人 × 每条规则各一个 key × 10 倍冗余」估的，
     * 超出按 LRU 淘汰，宁可少限也绝不能因为被攻击而涨爆堆内存。
     */
    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            @Value("${app.rate-limit.max-keys:20000}") long maxKeys,
            @Value("${app.rate-limit.key-idle-minutes:30}") long idleMinutes) {
        return new TokenBucketRateLimiter(maxKeys, Duration.ofMinutes(Math.max(1, idleMinutes)));
    }
}
