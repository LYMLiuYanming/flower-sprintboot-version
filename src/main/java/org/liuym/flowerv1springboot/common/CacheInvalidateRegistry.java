package org.liuym.flowerv1springboot.common;

import com.github.benmanes.caffeine.cache.Cache;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 缓存失效清单（L09）：把「哪类写操作要主动清掉哪些缓存」从散落的注解里收出来，形成一份可核对的表。
 *
 * <p>现状核对（写入本表前逐条查过代码）：
 * <ul>
 *   <li>已缓存的只有 4 张：{@code categories} / {@code banners} / {@code notices} / {@code stats}；</li>
 *   <li>商品、券、促销位、评价**根本没有进 Spring 缓存**，每次直查库——所以它们没有失效问题，
 *       但也意味着这些列表接口是每请求必打库的那一类（见 L08 的慢查询台账）；</li>
 *   <li>分类/轮播/公告的写操作已在各自 Service 上打了 {@code @CacheEvict(allEntries = true)}，覆盖完整；</li>
 *   <li>{@code stats} 只靠 60 秒 TTL 兜底：商品改价、券改库存、促销位上下线都不会立刻反映到看板。
 *       这是**有意的**（看板允许分钟级延迟），如果某个运营动作要求立即可见，就调
 *       {@link #invalidate(String)} 传 {@link #OP_STATS_NOW}。</li>
 * </ul>
 *
 * <p>用法：Service 写完数据后 {@code registry.invalidate(OP_PRODUCT_WRITE)}；
 * 后台「刷新缓存」按钮用 {@link #invalidateAll()}。清单本身通过 {@link #plan()} 只读暴露，
 * 免得规则只活在注释里。
 */
@Component
public class CacheInvalidateRegistry {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidateRegistry.class);

    /* ---- 写操作标识：一行一类操作，命名与后台菜单对齐，方便运营听得懂 ---- */
    public static final String OP_CATEGORY_WRITE = "category.write";
    public static final String OP_BANNER_WRITE = "banner.write";
    public static final String OP_NOTICE_WRITE = "notice.write";
    public static final String OP_PRODUCT_WRITE = "product.write";
    public static final String OP_ORDER_WRITE = "order.write";
    public static final String OP_COUPON_WRITE = "coupon.write";
    public static final String OP_PROMOTION_WRITE = "promotion.write";
    public static final String OP_REVIEW_WRITE = "review.write";
    public static final String OP_USER_WRITE = "user.write";
    /** 立即让看板反映刚刚的改动（跳过 60 秒 TTL），给「刚改完就要看到」的运营动作用 */
    public static final String OP_STATS_NOW = "stats.now";
    /** 全量刷新：后台「刷新缓存」按钮 */
    public static final String OP_ALL = "*";

    /** 操作 → 受影响缓存；值里的名字必须是 CacheConfig 里的常量，写错会在日志里点名 */
    private static final Map<String, List<String>> PLAN = new LinkedHashMap<>();
    /** 缓存 → 谁负责清它（反向表，评审「有没有人管」时看这张） */
    private static final Map<String, String> OWNERS = new LinkedHashMap<>();

    static {
        PLAN.put(OP_CATEGORY_WRITE, List.of(CacheConfig.CATEGORIES, CacheConfig.STATS));
        PLAN.put(OP_BANNER_WRITE, List.of(CacheConfig.BANNERS));
        PLAN.put(OP_NOTICE_WRITE, List.of(CacheConfig.NOTICES));
        PLAN.put(OP_PRODUCT_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_ORDER_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_COUPON_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_PROMOTION_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_REVIEW_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_USER_WRITE, List.of(CacheConfig.STATS));
        PLAN.put(OP_STATS_NOW, List.of(CacheConfig.STATS));
        PLAN.put(OP_ALL, List.of(CacheConfig.CATEGORIES, CacheConfig.BANNERS, CacheConfig.NOTICES, CacheConfig.STATS));

        OWNERS.put(CacheConfig.CATEGORIES, "CategoryServiceImpl 的 @CacheEvict + 本表 " + OP_CATEGORY_WRITE);
        OWNERS.put(CacheConfig.BANNERS, "BannerServiceImpl 的 @CacheEvict + 本表 " + OP_BANNER_WRITE);
        OWNERS.put(CacheConfig.NOTICES, "NoticeServiceImpl 的 @CacheEvict + 本表 " + OP_NOTICE_WRITE);
        OWNERS.put(CacheConfig.STATS, "60 秒 TTL 兜底；需要立刻可见时调 " + OP_STATS_NOW);
    }

    private final CacheManager cacheManager;

    public CacheInvalidateRegistry(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * 按清单失效一组缓存。
     *
     * @return 实际清掉的缓存名（不存在的缓存名会被跳过并记 WARN，避免清单和 CacheConfig 悄悄跑偏）
     */
    public List<String> invalidate(String operation) {
        List<String> targets = PLAN.get(operation);
        if (targets == null) {
            log.warn("缓存失效清单里没有操作 {}，未做任何失效", operation);
            return List.of();
        }
        List<String> evicted = new ArrayList<>(targets.size());
        for (String name : targets) {
            org.springframework.cache.Cache cache = cacheManager.getCache(name);
            if (cache == null) {
                log.warn("缓存 {} 未在 CacheManager 中注册，清单需要更新（操作 {}）", name, operation);
                continue;
            }
            cache.clear();
            evicted.add(name);
        }
        return evicted;
    }

    /** 全清，后台「刷新缓存」按钮用 */
    public List<String> invalidateAll() {
        return invalidate(OP_ALL);
    }

    /** 清单（只读副本） */
    public Map<String, List<String>> plan() {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        PLAN.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return copy;
    }

    /** 每张缓存由谁负责失效 */
    public Map<String, String> owners() {
        return Map.copyOf(OWNERS);
    }

    /**
     * 各缓存当前条目数。
     *
     * <p>只回条目数不回内容：缓存里是商品与用户数据的投影，条目键名都可能带用户 id，
     * 诊断接口要能回答「有没有在长」就够了。
     */
    public Map<String, Object> sizes() {
        Map<String, Object> sizes = new LinkedHashMap<>();
        if (cacheManager instanceof CaffeineCacheManager caffeineManager) {
            for (String name : caffeineManager.getCacheNames()) {
                org.springframework.cache.Cache cache = caffeineManager.getCache(name);
                Object nativeCache = cache == null ? null : cache.getNativeCache();
                sizes.put(name, nativeCache instanceof Cache<?, ?> caffeineCache ? caffeineCache.estimatedSize() : -1);
            }
            return sizes;
        }
        for (String name : cacheManager.getCacheNames()) {
            sizes.put(name, -1);
        }
        return sizes;
    }

    public List<String> cacheNames() {
        List<String> names = new ArrayList<>();
        cacheManager.getCacheNames().forEach(names::add);
        return names;
    }
}
