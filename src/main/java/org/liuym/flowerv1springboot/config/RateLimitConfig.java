package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.common.TokenBucketRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 限流装配（L04）：单独一个 WebMvcConfigurer，不去动 {@link WebMvcConfig} 的既有拦截器顺序。
 *
 * <p>Spring MVC 支持多个 WebMvcConfigurer 并存，各自的拦截器按 configurer 顺序拼接；
 * 这里用 order 把自己排在鉴权拦截器之前——先把明显超频的请求挡掉，再去做会话读取与角色判断，
 * 顺序反了会让攻击者的每次重试都白跑一遍鉴权链。
 */
@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    public RateLimitConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /** 桶本身在 {@link RateLimiterConfig} 里定义：留在这里会让 config ⇄ interceptor 形成循环依赖 */

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/auth/login", "/auth/api/login", "/auth/api/register",
                        "/api/auth/captcha",
                        "/api/orders/create", "/api/orders/*/pay", "/api/orders/*/refund",
                        "/api/coupons/claim", "/api/coupons/transfer/accept",
                        "/api/reviews/uploads/image")
                .order(Ordered.HIGHEST_PRECEDENCE);
    }
}
