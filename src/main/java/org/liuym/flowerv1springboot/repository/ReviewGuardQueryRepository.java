package org.liuym.flowerv1springboot.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 评价资格校验专用的只读查询（F07）。
 * OrderRepository / OrderItemRepository 属于交易主链路且由其它批次维护，
 * 这里只读地复用它们的表，避免为了评价功能改动订单文件。
 */
@Repository
@Transactional(readOnly = true)
public class ReviewGuardQueryRepository {

    /** 可评价的订单状态：已签收与已完成，未签收的订单不允许提前评价 */
    public static final List<OrderStatus> REVIEWABLE_STATUSES = List.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED);

    private final EntityManager entityManager;

    public ReviewGuardQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * 订单明细的评价资格快照：一次查询取回归属校验、状态校验与重复校验需要的全部字段，
     * 服务端据此判定，完全不信任前端传来的 orderId。
     */
    public Optional<Guard> guardOf(UUID orderItemId) {
        List<Tuple> rows = entityManager.createQuery("""
                        SELECT i.id AS orderItemId, i.order.id AS orderId, o.orderNo AS orderNo,
                               o.status AS status, o.user.id AS userId, o.finishTime AS finishTime,
                               o.updatedAt AS updatedAt, i.quantity AS quantity,
                               p.id AS productId, p.name AS productName, p.mainImage AS productImage,
                               p.isActive AS productActive
                        FROM OrderItem i JOIN i.order o JOIN o.user u JOIN i.product p
                        WHERE i.id = :orderItemId
                        """, Tuple.class)
                .setParameter("orderItemId", orderItemId)
                .setMaxResults(1)
                .getResultList();
        return rows.stream().findFirst().map(Guard::of);
    }

    /**
     * 当前用户尚未评价的可评价明细（F07 写入口 + 详情页「写评价」表单数据源）。
     * productId 为空表示跨全部订单查，用于个人中心；非空用于详情页判断「你能不能评价这一款」。
     */
    public List<ReviewableItem> findReviewable(UUID userId, UUID productId, int limit) {
        String jpql = """
                SELECT i.id AS orderItemId, i.order.id AS orderId, o.orderNo AS orderNo,
                       p.id AS productId, p.name AS productName, p.mainImage AS productImage,
                       i.quantity AS quantity,
                       COALESCE(o.finishTime, o.updatedAt, o.createdAt) AS doneAt
                FROM OrderItem i JOIN i.order o JOIN o.user u JOIN i.product p
                WHERE u.id = :userId
                  AND o.status IN :statuses
                  AND i.id NOT IN (SELECT r.orderItem.id FROM Review r)
                  AND (:productId IS NULL OR p.id = :productId)
                ORDER BY COALESCE(o.finishTime, o.updatedAt, o.createdAt) DESC
                """;
        return entityManager.createQuery(jpql, Tuple.class)
                .setParameter("userId", userId)
                .setParameter("statuses", REVIEWABLE_STATUSES)
                .setParameter("productId", productId)
                .setMaxResults(Math.min(Math.max(limit, 1), 50))
                .getResultList()
                .stream()
                .map(t -> new ReviewableItem(
                        t.get("orderItemId", UUID.class),
                        t.get("orderId", UUID.class),
                        t.get("orderNo", String.class),
                        t.get("productId", UUID.class),
                        t.get("productName", String.class),
                        t.get("productImage", String.class),
                        t.get("quantity", Integer.class),
                        t.get("doneAt", LocalDateTime.class)))
                .toList();
    }

    /** 该明细是否已评价过：唯一性只以「订单明细 + 商品」判定，重复提交在这里被拦下 */
    public boolean alreadyReviewed(UUID orderItemId) {
        Long count = entityManager.createQuery(
                        "SELECT COUNT(r.id) FROM Review r WHERE r.orderItem.id = :orderItemId", Long.class)
                .setParameter("orderItemId", orderItemId)
                .getSingleResult();
        return count != null && count > 0;
    }

    /** 评价资格快照 */
    public record Guard(UUID orderItemId, UUID orderId, String orderNo, OrderStatus status, UUID userId,
                        LocalDateTime finishTime, LocalDateTime updatedAt, Integer quantity,
                        UUID productId, String productName, String productImage, Boolean productActive) {

        static Guard of(Tuple t) {
            return new Guard(t.get("orderItemId", UUID.class), t.get("orderId", UUID.class),
                    t.get("orderNo", String.class), t.get("status", OrderStatus.class), t.get("userId", UUID.class),
                    t.get("finishTime", LocalDateTime.class), t.get("updatedAt", LocalDateTime.class),
                    t.get("quantity", Integer.class), t.get("productId", UUID.class),
                    t.get("productName", String.class), t.get("productImage", String.class),
                    t.get("productActive", Boolean.class));
        }

        public boolean reviewable() {
            return status != null && REVIEWABLE_STATUSES.contains(status);
        }
    }

    public record ReviewableItem(UUID orderItemId, UUID orderId, String orderNo, UUID productId, String productName,
                                 String productImage, Integer quantity, LocalDateTime doneAt) {
    }
}
