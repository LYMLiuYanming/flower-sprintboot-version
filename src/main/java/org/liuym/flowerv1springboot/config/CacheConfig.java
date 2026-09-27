package org.liuym.flowerv1springboot.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 读多写少数据的本地缓存策略：字典类（分类/轮播/公告）靠后台写操作主动失效，
 * 看板聚合只靠 TTL 兜底（允许分钟级延迟），Redis 接入后可整体切换。
 */
@Configuration
public class CacheConfig {

    public static final String CATEGORIES = "categories";
    public static final String BANNERS = "banners";
    public static final String NOTICES = "notices";
    public static final String STATS = "stats";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(5)));
        manager.registerCustomCache(CATEGORIES, ttlCache(200, Duration.ofMinutes(2)));
        manager.registerCustomCache(BANNERS, ttlCache(100, Duration.ofMinutes(1)));
        manager.registerCustomCache(NOTICES, ttlCache(100, Duration.ofMinutes(1)));
        manager.registerCustomCache(STATS, ttlCache(60, Duration.ofSeconds(60)));
        return manager;
    }

    private static Cache<Object, Object> ttlCache(long maxSize, Duration ttl) {
        return Caffeine.newBuilder().maximumSize(maxSize).expireAfterWrite(ttl).build();
    }
}
