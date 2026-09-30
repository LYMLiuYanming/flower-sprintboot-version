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
 * 工单侧的只读查询（U12/U16/U17/U25/U26）。
 *
 * <p>订单、评价表由其它批次维护，这里只读地复用它们：归属校验、关联订单快照、
 * 提单页可选订单都从这一个类出，不往 {@code OrderRepository} 上加方法。
 *
 * <p>客服（U26）能看到的关联订单信息也走这里，出网前统一在 VO 层按 {@code Masking} 脱敏。
 */
@Repository
@Transactional(readOnly = true)
public class TicketSourceRepository {

    private final EntityManager em;

    public TicketSourceRepository(EntityManager em) {
        this.em = em;
    }

    /** 订单归属校验（U12）：服务端判定，绝不信任前端传来的 orderId */
    public boolean orderBelongsTo(UUID orderId, UUID userId) {
        if (orderId == null || userId == null) {
            return false;
        }
        Long count = em.createQuery("SELECT COUNT(o.id) FROM Order o WHERE o.id = :id AND o.user.id = :userId",
                        Long.class)
                .setParameter("id", orderId)
                .setParameter("userId", userId)
                .getSingleResult();
        return count != null && count > 0;
    }

    /** 订单归属校验（U26）：客服只看得到自己工单关联的订单，判定同样落在这里 */
    public boolean orderOwnedByTicketUser(UUID orderId, UUID userId) {
        return orderBelongsTo(orderId, userId);
    }

    /**
     * 客服名录的登录名（U26 派单下拉）。
     *
     * <p>只取 username 一列，显示名走 ticket_agent.display_name：账号名可能带手机号，
     * 后台列表里默认展示花名，账号名只在括号里做二次确认。
     */
    public Map<UUID, String> usernamesOf(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        return em.createQuery("SELECT u.id, u.username FROM User u WHERE u.id IN :ids", Tuple.class)
                .setParameter("ids", userIds)
                .getResultList().stream()
                .collect(java.util.stream.Collectors.toMap(
                        t -> t.get(0, UUID.class), t -> String.valueOf(t.get(1)), (a, b) -> a));
    }

    /**
     * 账号手机号（U12 提单时的联系电话兜底）。
     *
     * <p>只取这一列而不是整个 User：用户表由其它批次维护，往它的 Repository 上加方法会踩到并行改动。
     */
    public String phoneOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return em.createQuery("SELECT u.phone FROM User u WHERE u.id = :userId", String.class)
                .setParameter("userId", userId)
                .getResultList().stream().findFirst().orElse(null);
    }

    /**
     * 关联订单快照（U16/U26）：工单详情侧栏一次性取平要展示的列。
     *
     * <p>只取标量列而不是 Order 实体：工单详情要在多张工单之间来回跳，
     * 拿实体就等着在 {@code @Modifying(clearAutomatically)} 之后撞懒加载关联。
     */
    public record OrderSnapshot(UUID orderId, String orderNo, String status, String statusLabel,
                                BigDecimal payAmount, BigDecimal refundAmount, String refundStatus,
                                LocalDateTime createdAt, LocalDateTime payTime, LocalDateTime shipTime,
                                LocalDateTime expectedArriveAt, String receiverName, String receiverPhone,
                                String expressCompany, String expressNo) {
    }

    public OrderSnapshot findOrderSnapshot(UUID orderId) {
        if (orderId == null) {
            return null;
        }
        return em.createQuery("""
                        SELECT o.id AS orderId, o.orderNo AS orderNo, o.status AS status,
                               o.payAmount AS payAmount, o.refundAmount AS refundAmount,
                               o.refundStatus AS refundStatus, o.createdAt AS createdAt, o.payTime AS payTime,
                               o.shipTime AS shipTime, o.expectedArriveAt AS expectedArriveAt,
                               o.receiverName AS receiverName, o.receiverPhone AS receiverPhone,
                               o.expressCompany AS expressCompany, o.expressNo AS expressNo
                          FROM Order o WHERE o.id = :orderId
                        """, Tuple.class)
                .setParameter("orderId", orderId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(t -> {
                    OrderStatus status = t.get("status", OrderStatus.class);
                    RefundStatus refund = t.get("refundStatus", RefundStatus.class);
                    return new OrderSnapshot(t.get("orderId", UUID.class), t.get("orderNo", String.class),
                            code(status), OrderStatus.labelOf(status), t.get("payAmount", BigDecimal.class),
                            t.get("refundAmount", BigDecimal.class), code(refund),
                            t.get("createdAt", LocalDateTime.class), t.get("payTime", LocalDateTime.class),
                            t.get("shipTime", LocalDateTime.class), t.get("expectedArriveAt", LocalDateTime.class),
                            t.get("receiverName", String.class), t.get("receiverPhone", String.class),
                            t.get("expressCompany", String.class), t.get("expressNo", String.class));
                })
                .orElse(null);
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

    /** 提交工单时可选的订单（U12）：只列自己名下、已付款及之后的单 */
    public record OrderOption(UUID orderId, String orderNo, String status, String statusLabel,
                              BigDecimal payAmount, LocalDateTime createdAt, String sampleItemName,
                              Integer itemCount) {
    }

    public List<OrderOption> findSelectableOrders(UUID userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        TypedQuery<Tuple> query = em.createQuery("""
                        SELECT o.id AS orderId, o.orderNo AS orderNo, o.status AS status,
                               o.payAmount AS payAmount, o.createdAt AS createdAt,
                               COUNT(i.id) AS itemCount, MIN(i.productName) AS sampleItemName
                          FROM Order o LEFT JOIN o.items i
                         WHERE o.user.id = :userId
                           AND o.status IN ('paid','processing','shipped','delivered','completed','refunded')
                         GROUP BY o.id, o.orderNo, o.status, o.payAmount, o.createdAt
                         ORDER BY o.createdAt DESC
                        """, Tuple.class);
        query.setParameter("userId", userId);
        query.setMaxResults(Math.min(Math.max(limit, 1), 50));
        return query.getResultList().stream()
                .map(t -> {
                    OrderStatus status = t.get("status", OrderStatus.class);
                    Number itemCount = (Number) t.get("itemCount");
                    return new OrderOption(t.get("orderId", UUID.class), t.get("orderNo", String.class),
                            code(status), OrderStatus.labelOf(status), t.get("payAmount", BigDecimal.class),
                            t.get("createdAt", LocalDateTime.class), t.get("sampleItemName", String.class),
                            itemCount == null ? 0 : itemCount.intValue());
                })
                .toList();
    }

    /** 评价摘要（U17 差评补偿与 U18 自动开单的服务端判定） */
    public record ReviewBrief(UUID reviewId, UUID userId, UUID orderId, Integer rating, String content,
                              Boolean visible, String productName, String material) {
    }

    public ReviewBrief findReviewBrief(UUID reviewId) {
        if (reviewId == null) {
            return null;
        }
        return em.createQuery("""
                        SELECT r.id AS reviewId, r.user.id AS userId, r.order.id AS orderId, r.rating AS rating,
                               r.content AS content, r.visible AS visible, r.product.name AS productName,
                               r.product.material AS material
                          FROM Review r WHERE r.id = :reviewId
                        """, Tuple.class)
                .setParameter("reviewId", reviewId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst()
                .map(t -> new ReviewBrief(t.get("reviewId", UUID.class), t.get("userId", UUID.class),
                        t.get("orderId", UUID.class), t.get("rating", Integer.class), t.get("content", String.class),
                        t.get("visible", Boolean.class), t.get("productName", String.class),
                        t.get("material", String.class)))
                .orElse(null);
    }

    /**
     * 首响 / 解决时长的分位数（U25 看板）：P50 与 P90 用 percentile_cont 在库内算。
     *
     * <p>不把工单捞进内存排序取分位：看板要跑全量时间窗，拉实体是最容易把后台拖死的一种写法。
     *
     * @return {@code [p50Minutes, p90Minutes, averageMinutes, sampleCount]}，无样本时全为 null
     */
    public double[] firstResponsePercentiles(LocalDateTime from, LocalDateTime to) {
        return percentiles("""
                SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (m.first_reply_at - t.created_at)) / 60),
                       percentile_cont(0.90) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (m.first_reply_at - t.created_at)) / 60),
                       AVG(EXTRACT(EPOCH FROM (m.first_reply_at - t.created_at)) / 60),
                       COUNT(*)
                  FROM (SELECT t.id, t.created_at,
                               (SELECT MIN(a.created_at) FROM ticket_message a
                                 WHERE a.ticket_id = t.id AND a.author_type IN ('agent', 'system')) AS first_reply_at
                          FROM ticket t
                         WHERE t.created_at BETWEEN :from AND :to) t
                 WHERE t.first_reply_at IS NOT NULL
                """, from, to);
    }

    /** 解决时长分位：只统计已解决的单，重开后再次解决的按最终 resolved_at 计 */
    public double[] resolvePercentiles(LocalDateTime from, LocalDateTime to) {
        return percentiles("""
                SELECT percentile_cont(0.50) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 60),
                       percentile_cont(0.90) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 60),
                       AVG(EXTRACT(EPOCH FROM (t.resolved_at - t.created_at)) / 60),
                       COUNT(*)
                  FROM ticket t
                 WHERE t.created_at BETWEEN :from AND :to
                   AND t.resolved_at IS NOT NULL AND t.resolved_at >= t.created_at
                """, from, to);
    }

    private double[] percentiles(String sql, LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("from", from)
                .setParameter("to", to)
                .setMaxResults(1)
                .getResultList();
        if (rows.isEmpty()) {
            return new double[]{Double.NaN, Double.NaN, Double.NaN, 0};
        }
        Object[] row = rows.get(0);
        return new double[]{asDouble(row[0]), asDouble(row[1]), asDouble(row[2]), asDouble(row[3])};
    }

    private static double asDouble(Object value) {
        return value == null ? Double.NaN : ((Number) value).doubleValue();
    }
}
