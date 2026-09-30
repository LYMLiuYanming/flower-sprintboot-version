package org.liuym.flowerv1springboot.common;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 通用「一次性凭证」防重放（L03）：支付、退款、领券、积分兑换这类**已经产生资金或库存变化**的动作，
 * 网络重试与用户连点都会造成二次执行。这里提供一个不依赖 Redis 的本地实现，
 * 与下单用的 {@link IdempotencyPolicy} 同一思路（先抢占、后落单），差别是它通用且带结果回吐。
 *
 * <p>语义：
 * <ol>
 *   <li>前端进页面先 {@link #issue} 拿一枚 token（一次性、不可猜）；</li>
 *   <li>提交时 {@link #acquire}：{@code FRESH} 才允许执行业务，{@code REPLAY} 直接把首次的执行结果回吐，
 *       {@code REJECTED} 表示凭证不属于本人或已过期；</li>
 *   <li>业务成功 {@link #complete} 记下结果摘要（回吐时原样返回），失败 {@link #release} 让凭证可再次使用，
 *       免得一次校验不通过就把用户锁死 2 小时。</li>
 * </ol>
 *
 * <p><b>单机限制</b>：状态在堆内，多实例部署时另一台不认这枚 token。真要横向扩容，
 * 按 {@code idempotency_token} 表那样把 key 落库加唯一约束即可（本批不建表，避免与订单批次抢迁移）。
 */
@Component
public class ReplayGuard {

    /** 业务动作分类：不同 scope 的 token 互相不通，防止「拿下单凭证去退款」 */
    public enum Scope {
        PAYMENT, REFUND, COUPON_CLAIM, COUPON_TRANSFER, POINT_EXCHANGE, ORDER_CREATE;

        public String code() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * 抢占结果。
     *
     * @param status  FRESH=可以继续执行；REPLAY=重复提交，payload 是首次结果；REJECTED=凭证无效/不属于本人
     * @param payload 首次执行留下的结果摘要（如订单号、退款单号），仅在 REPLAY 时非空
     */
    public record Verdict(Status status, String payload) {

        public boolean proceed() {
            return status == Status.FRESH;
        }

        public boolean replay() {
            return status == Status.REPLAY;
        }
    }

    public enum Status { FRESH, REPLAY, REJECTED }

    /** 凭证状态：claimed 后还没跑完业务，done 表示已有结果可回吐 */
    private enum Phase { ISSUED, CLAIMED, DONE }

    private record Entry(UUID owner, Phase phase, String payload) {
    }

    private static final int TOKEN_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Cache<String, Entry> store;
    private final long ttlMinutes;

    public ReplayGuard(@Value("${app.replay-guard.max-entries:50000}") long maxEntries,
                       @Value("${app.replay-guard.ttl-minutes:120}") long ttlMinutes) {
        this.ttlMinutes = Math.max(5, ttlMinutes);
        // 只按写入时间过期：一张凭证从签发到用完不该超过 TTL，被反复续期等于给刷单留口子
        this.store = Caffeine.newBuilder()
                .maximumSize(Math.max(1000, maxEntries))
                .expireAfterWrite(Duration.ofMinutes(this.ttlMinutes))
                .build();
    }

    /** 签发一次性凭证，绑定到具体用户 */
    public String issue(Scope scope, UUID userId) {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        String token = HexFormat.of().formatHex(buf);
        store.put(key(scope, token), new Entry(userId, Phase.ISSUED, null));
        return token;
    }

    /**
     * 原子抢占。用 asMap().compute 而不是 get + put：
     * 同一枚 token 的两个并发请求会在同一把桶锁上排队，只有一个能拿到 FRESH。
     */
    public Verdict acquire(Scope scope, String token, UUID userId) {
        if (token == null || token.isBlank() || userId == null) {
            return new Verdict(Status.REJECTED, null);
        }
        String key = key(scope, token.trim());
        Entry[] winner = new Entry[1];
        store.asMap().compute(key, (k, current) -> {
            if (current == null) {
                return null;
            }
            if (!current.owner().equals(userId)) {
                return current;
            }
            if (current.phase() == Phase.DONE) {
                winner[0] = current;
                return current;
            }
            if (current.phase() == Phase.ISSUED) {
                Entry claimed = new Entry(current.owner(), Phase.CLAIMED, null);
                winner[0] = claimed;
                return claimed;
            }
            // CLAIMED 说明上一次执行还没结束：按重复提交处理，宁可让用户等一下也别跑两遍
            winner[0] = new Entry(current.owner(), Phase.CLAIMED, "上一次提交仍在处理中");
            return current;
        });
        if (winner[0] == null) {
            return new Verdict(Status.REJECTED, null);
        }
        if (winner[0].phase() == Phase.DONE) {
            return new Verdict(Status.REPLAY, winner[0].payload());
        }
        if (winner[0].phase() == Phase.CLAIMED && winner[0].payload() != null) {
            return new Verdict(Status.REPLAY, winner[0].payload());
        }
        return new Verdict(Status.FRESH, null);
    }

    /** 业务成功：把结果摘要钉在凭证上，之后的重复提交都回吐这一份 */
    public void complete(Scope scope, String token, String payload) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = key(scope, token.trim());
        store.asMap().computeIfPresent(key, (k, current) -> new Entry(current.owner(), Phase.DONE, payload));
    }

    /** 业务失败：释放回 ISSUED，让用户能立刻重试而不是干等到过期 */
    public void release(Scope scope, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        String key = key(scope, token.trim());
        store.asMap().computeIfPresent(key, (k, current) ->
                current.phase() == Phase.DONE ? current : new Entry(current.owner(), Phase.ISSUED, null));
    }

    /** 排障用：这张凭证现在处于什么阶段（不回吐 payload，避免把结果摘要甩到日志里） */
    public String phaseOf(Scope scope, String token, UUID userId) {
        if (token == null || token.isBlank()) {
            return "missing";
        }
        Entry entry = store.getIfPresent(key(scope, token.trim()));
        if (entry == null) {
            return "unknown";
        }
        return entry.owner().equals(userId) ? entry.phase().name().toLowerCase(java.util.Locale.ROOT) : "foreign";
    }

    /** 当前在途凭证数（近似值），诊断接口用它看有没有被大量签发后不使用 */
    public long tracked() {
        return store.estimatedSize();
    }

    public long ttlMinutes() {
        return ttlMinutes;
    }

    /** 测试与演练用：清空全部在途凭证 */
    public void clear() {
        store.invalidateAll();
    }

    private static String key(Scope scope, String token) {
        return scope.code() + ':' + token;
    }
}
