package org.liuym.flowerv1springboot.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.Tuple;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.RefundStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 触达侧的只读查询（U06/U10/U18/U20/U22/U23）。
 *
 * <p>订单、券、评价、商品、文章分属其它批次维护的 repository，本类只读地复用它们的表，
 * 不往别人的 repository 上加方法——与第一轮 {@code ReviewGuardQueryRepository} 同一做法。
 *
 * <p>扫描一律按「时间窗」取数、落库按 dedup_key 唯一索引兜底：任务每轮重扫是安全的，
 * 既不会漏发（窗口重叠），也不会重发（撞唯一索引即丢弃）。
 */
@Repository
@Transactional(readOnly = true)
public class NotificationSourceRepository {

    private final EntityManager em;

    public NotificationSourceRepository(EntityManager em) {
        this.em = em;
    }

    /** 交易事件投影：一条订单的一次状态变化，字段就是消息模板要用的全部变量 */
    public record TradeEvent(UUID orderId, String orderNo, UUID userId, String status,
                             LocalDateTime payTime, LocalDateTime shipTime, LocalDateTime deliverTime,
                             LocalDateTime refundedAt, String refundStatus, String expressCompany,
                             String expressNo, String receiverName, BigDecimal payAmount,
                             BigDecimal refundAmount, LocalDateTime expectedArriveAt, LocalDateTime finishTime) {
    }

    /** 事件扫描列白名单 */
    public enum TradeColumn {
        PAID, SHIPPED, DELIVERED, REFUNDED
    }

    /**
     * 指定时间列落在窗口内的订单（U06 交易事件接入）。
     *
     * <p>列名走枚举白名单而不是字符串参数：拼进 JPQL 的片段绝不能来自请求，
     * 白名单同时把「按哪列扫」变成可枚举口径，验收时每个事件都能单独触发。
     */
    public List<TradeEvent> findTradeEvents(TradeColumn column, LocalDateTime from, LocalDateTime to, int limit) {
        String predicate = switch (column) {
            case PAID -> "o.payTime IS NOT NULL AND o.payTime BETWEEN :from AND :to";
            case SHIPPED -> "o.shipTime IS NOT NULL AND o.shipTime BETWEEN :from AND :to";
            case DELIVERED -> "o.deliverTime IS NOT NULL AND o.deliverTime BETWEEN :from AND :to";
            case REFUNDED -> "o.refundedAt IS NOT NULL AND o.refundedAt BETWEEN :from AND :to";
        };
        String jpql = "SELECT o.id AS orderId, o.orderNo AS orderNo, o.user.id AS userId, o.status AS status,"
                + " o.payTime AS payTime, o.shipTime AS shipTime, o.deliverTime AS deliverTime,"
                + " o.refundedAt AS refundedAt, o.refundStatus AS refundStatus,"
                + " o.expressCompany AS expressCompany, o.expressNo AS expressNo,"
                + " o.receiverName AS receiverName, o.payAmount AS payAmount,"
                + " o.refundAmount AS refundAmount, o.expectedArriveAt AS expectedArriveAt,"
                + " o.finishTime AS finishTime"
                + " FROM Order o WHERE " + predicate + " ORDER BY o.createdAt DESC";
        return em.createQuery(jpql, Tuple.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(clamp(limit, 500))
                .getResultList()
                .stream()
                .map(t -> new TradeEvent(
                        t.get("orderId", UUID.class), t.get("orderNo", String.class),
                        t.get("userId", UUID.class), code(t.get("status", OrderStatus.class)),
                        t.get("payTime", LocalDateTime.class), t.get("shipTime", LocalDateTime.class),
                        t.get("deliverTime", LocalDateTime.class), t.get("refundedAt", LocalDateTime.class),
                        code(t.get("refundStatus", RefundStatus.class)), t.get("expressCompany", String.class),
                        t.get("expressNo", String.class), t.get("receiverName", String.class),
                        t.get("payAmount", BigDecimal.class), t.get("refundAmount", BigDecimal.class),
                        t.get("expectedArriveAt", LocalDateTime.class), t.get("finishTime", LocalDateTime.class)))
                .toList();
    }

    private static String code(Enum<?> value) {
        if (value instanceof OrderStatus status) {
            return status.getCode();
        }
        if (value instanceof RefundStatus status) {
            return status.getCode();
        }
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    /** 即将到期的持券（U06 券到期）：只扫未使用、落在窗口内的行 */
    public record ExpiringCoupon(UUID userCouponId, UUID userId, UUID couponId, String name, String code,
                                 BigDecimal amount, LocalDateTime expireAt) {
    }

    public List<ExpiringCoupon> findCouponsExpiringBetween(LocalDateTime from, LocalDateTime to, int limit) {
        return em.createQuery("""
                        SELECT uc.id AS id, uc.userId AS userId, uc.couponId AS couponId, uc.name AS name,
                               uc.code AS code, uc.amount AS amount, uc.expireAt AS expireAt
                          FROM UserCoupon uc
                         WHERE uc.status = 'unused' AND uc.expireAt BETWEEN :from AND :to
                         ORDER BY uc.expireAt ASC
                        """, Tuple.class)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(clamp(limit, 1000))
                .getResultList()
                .stream()
                .map(t -> new ExpiringCoupon(t.get("id", UUID.class), t.get("userId", UUID.class),
                        t.get("couponId", UUID.class), t.get("name", String.class), t.get("code", String.class),
                        t.get("amount", BigDecimal.class), t.get("expireAt", LocalDateTime.class)))
                .toList();
    }

    /** 低分已发布评价（U18 自动开单）：已开过单的在 SQL 里就排掉，任务不必逐条去撞唯一索引 */
    public record LowRatingReview(UUID reviewId, UUID userId, UUID orderId, String orderNo, UUID productId,
                                  String productName, Integer rating, String content, LocalDateTime createdAt,
                                  String material) {
    }

    public List<LowRatingReview> findLowRatingReviewsWithoutTicket(int maxRating, LocalDateTime from, int limit) {
        return em.createQuery("""
                        SELECT r.id AS reviewId, r.user.id AS userId, r.order.id AS orderId,
                               r.order.orderNo AS orderNo, r.product.id AS productId,
                               r.product.name AS productName, r.rating AS rating, r.content AS content,
                               r.createdAt AS createdAt, r.product.material AS material
                          FROM Review r
                         WHERE r.visible = true AND r.rating <= :maxRating AND r.createdAt >= :from
                           AND NOT EXISTS (SELECT t.id FROM Ticket t
                                           WHERE t.sourceType = 'review' AND t.sourceId = CAST(r.id AS string))
                         ORDER BY r.createdAt ASC
                        """, Tuple.class)
                .setParameter("maxRating", maxRating)
                .setParameter("from", from)
                .setMaxResults(clamp(limit, 200))
                .getResultList()
                .stream()
                .map(t -> new LowRatingReview(t.get("reviewId", UUID.class), t.get("userId", UUID.class),
                        t.get("orderId", UUID.class), t.get("orderNo", String.class),
                        t.get("productId", UUID.class), t.get("productName", String.class),
                        t.get("rating", Integer.class), t.get("content", String.class),
                        t.get("createdAt", LocalDateTime.class), t.get("material", String.class)))
                .toList();
    }

    /** 订单行上的花材（U20/U22：养护提醒要先知道这单里是什么花） */
    public record OrderMaterial(UUID productId, String productName, String material) {
    }

    public List<OrderMaterial> findOrderMaterials(UUID orderId) {
        return em.createQuery("""
                        SELECT i.product.id AS productId, i.product.name AS productName,
                               i.product.material AS material
                          FROM OrderItem i JOIN i.product
                         WHERE i.order.id = :orderId
                         ORDER BY i.product.id ASC
                        """, Tuple.class)
                .setParameter("orderId", orderId)
                .getResultList()
                .stream()
                .map(t -> new OrderMaterial(t.get("productId", UUID.class),
                        t.get("productName", String.class), t.get("material", String.class)))
                .toList();
    }

    /** 养护知识库文章（第一轮 F15 的 article）：materials 命中即用它的标题 + 摘要做定制文案 */
    public record CareArticle(UUID articleId, String title, String summary, String materials) {
    }

    public List<CareArticle> findCareArticles(int limit) {
        return em.createQuery("""
                        SELECT a.id AS id, a.title AS title, a.summary AS summary, a.materials AS materials
                          FROM Article a
                         WHERE a.status = 'published' AND a.category = 'care'
                         ORDER BY a.isTop DESC, a.sortOrder DESC
                        """, Tuple.class)
                .setMaxResults(clamp(limit, 100))
                .getResultList()
                .stream()
                .map(t -> new CareArticle(t.get("id", UUID.class), t.get("title", String.class),
                        t.get("summary", String.class), t.get("materials", String.class)))
                .toList();
    }

    /**
     * 批量取收件人手机号（后台触达明细用）。
     *
     * <p>一次 IN 查询而不是逐行取：明细页 20 行就是 20 条 SQL，第一轮 L16 已经踩过这个坑。
     * 返回值出网前由 VO 按 {@code Masking} 脱敏。
     */
    public Map<UUID, String> phonesOf(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("""
                        SELECT u.id AS userId, u.phone AS phone FROM User u WHERE u.id IN :ids
                        """, Tuple.class)
                .setParameter("ids", userIds)
                .getResultList()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        t -> t.get("userId", UUID.class),
                        t -> t.get("phone", String.class) == null ? "" : t.get("phone", String.class),
                        (a, b) -> a));
    }

    /** 收件人是否存在且可收：注销与禁用的账号不再产生新消息 */
    public boolean isRecipientActive(UUID userId) {
        if (userId == null) {
            return false;
        }
        Long count = em.createQuery("SELECT COUNT(u.id) FROM User u WHERE u.id = :id AND u.status = 'active'",
                        Long.class)
                .setParameter("id", userId)
                .getSingleResult();
        return count != null && count > 0;
    }

    /**
     * 分群 SQL：预览与实发共用同一段文本，避免「预览 100 人、实发 120 人」两套口径。
     *
     * <p>只用本仓库看得到的既有表（user / order / ticket）。
     * W24 的浏览画像标签表落地后在这里加一条 case 即可，页面与接口都不用改。
     */
    private static String audienceSql(String segmentCode, boolean countOnly) {
        String select = countOnly ? "SELECT COUNT(u.id)" : "SELECT u.id";
        String tail = countOnly ? "" : " ORDER BY u.createdAt DESC";
        String base = "u.status = 'active' AND u.userType = 'customer'";
        String where = switch (segmentCode == null ? "" : segmentCode) {
            case "all" -> base;
            case "vip" -> base + " AND u.memberLevel = 'vip'";
            case "ordinary" -> base + " AND u.memberLevel <> 'vip'";
            case "has_order" -> base + " AND EXISTS (SELECT o.id FROM Order o WHERE o.user.id = u.id)";
            // 近 N 天有成交：促销与新品通知的主力人群
            case "active_buyers" -> base
                    + " AND EXISTS (SELECT o.id FROM Order o WHERE o.user.id = u.id"
                    + " AND o.status IN ('paid','processing','shipped','delivered','completed')"
                    + " AND o.createdAt >= :windowStart)";
            // 下过单但 N 天没动静：唤醒人群
            case "dormant" -> base
                    + " AND EXISTS (SELECT o.id FROM Order o WHERE o.user.id = u.id)"
                    + " AND NOT EXISTS (SELECT o.id FROM Order o WHERE o.user.id = u.id AND o.createdAt >= :windowStart)";
            // 有在途工单的人群：服务通知用，不与营销混发
            case "ticket_open" -> "u.status = 'active' AND EXISTS (SELECT t.id FROM Ticket t"
                    + " WHERE t.userId = u.id AND t.status IN ('open','assigned','processing'))";
            default -> throw new IllegalArgumentException("不支持的人群分群：" + segmentCode);
        };
        return select + " FROM User u WHERE " + where + tail;
    }

    /** 只有带时间窗的分群需要绑定 windowStart，其它分群不绑参数，免得 Hibernate 报未使用参数 */
    private static boolean usesWindow(String segmentCode) {
        return "active_buyers".equals(segmentCode) || "dormant".equals(segmentCode);
    }

    /** 分群命中人数（U23 预览） */
    public long countAudience(String segmentCode, int windowDays) {
        TypedQuery<Long> query = em.createQuery(audienceSql(segmentCode, true), Long.class);
        if (usesWindow(segmentCode)) {
            query.setParameter("windowStart", LocalDateTime.now().minusDays(Math.max(1, windowDays)));
        }
        Long count = query.getSingleResult();
        return count == null ? 0 : count;
    }

    /** 分群用户 id 清单：与 countAudience 共用 SQL，预览数与实发数不可能对不上 */
    public List<UUID> audienceUserIds(String segmentCode, int windowDays, int limit) {
        TypedQuery<UUID> query = em.createQuery(audienceSql(segmentCode, false), UUID.class);
        if (usesWindow(segmentCode)) {
            query.setParameter("windowStart", LocalDateTime.now().minusDays(Math.max(1, windowDays)));
        }
        return query.setMaxResults(Math.min(Math.max(limit, 1), 20000)).getResultList();
    }

    /** 分群窗口天数：页面与实发共用，避免「预览按 30 天、实发按 60 天」 */
    public static int windowDaysOf(String segmentCode) {
        return "dormant".equals(segmentCode) ? 90 : 30;
    }

    public static final List<String> SEGMENTS =
            List.of("all", "vip", "ordinary", "has_order", "active_buyers", "dormant", "ticket_open");

    public static final Map<String, String> SEGMENT_LABELS = Map.of(
            "all", "全部正常账号",
            "vip", "VIP 会员",
            "ordinary", "普通会员",
            "has_order", "下过单的用户",
            "active_buyers", "近 30 天有成交",
            "dormant", "近 90 天未下单",
            "ticket_open", "有在途工单");

    /** 单轮取数上限：宁可在下一轮继续处理，也不把整表拉进内存 */
    private static int clamp(int limit, int max) {
        return Math.min(Math.max(limit, 1), max);
    }
}
