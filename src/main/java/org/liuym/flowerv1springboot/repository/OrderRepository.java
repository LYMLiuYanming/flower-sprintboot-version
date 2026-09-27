package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * 带明细与商品的订单查询（open-in-view 已关闭，需在事务内一次取全）
     */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.product WHERE o.id = :id")
    Optional<Order> findDetailById(@Param("id") UUID id);

    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.product "
            + "WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
    List<Order> findDetailByUserId(@Param("userId") UUID userId);

    Optional<Order> findByOrderNo(String orderNo);

    @Query("SELECT o FROM Order o WHERE o.user.id = :userId ORDER BY o.createdAt DESC")
    Page<Order> findByUserId(@Param("userId") UUID userId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    /**
     * 后台订单筛选：keyword 约定传空串而不是 null，null 会让 PostgreSQL 无法推断 LIKE 参数类型
     */
    @Query("""
            SELECT o FROM Order o
            WHERE (:status IS NULL OR o.status = :status)
              AND (:keyword = '' OR o.orderNo LIKE %:keyword% OR o.receiverName LIKE %:keyword% OR o.receiverPhone LIKE %:keyword%)
            ORDER BY o.createdAt DESC
            """)
    Page<Order> search(@Param("status") OrderStatus status,
                       @Param("keyword") String keyword,
                       Pageable pageable);

    /**
     * 状态跃迁采用条件更新（CAS）：只有当前状态与期望一致才写入，并发下不会互相覆盖
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = :target, o.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE o.id = :id AND o.status = :expected")
    int transitStatus(@Param("id") UUID id,
                      @Param("expected") OrderStatus expected,
                      @Param("target") OrderStatus target);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.payTime = :payTime, o.status = :target, o.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE o.id = :id AND o.status = :expected")
    int markPaid(@Param("id") UUID id,
                 @Param("expected") OrderStatus expected,
                 @Param("target") OrderStatus target,
                 @Param("payTime") LocalDateTime payTime);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.expressCompany = :company, o.expressNo = :no, o.shipTime = :shipTime, "
            + "o.status = :target, o.updatedAt = CURRENT_TIMESTAMP WHERE o.id = :id AND o.status = :expected")
    int markShipped(@Param("id") UUID id,
                    @Param("expected") OrderStatus expected,
                    @Param("target") OrderStatus target,
                    @Param("company") String company,
                    @Param("no") String no,
                    @Param("shipTime") LocalDateTime shipTime);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.cancelReason = :reason, o.finishTime = :finishTime, o.status = :target, "
            + "o.updatedAt = CURRENT_TIMESTAMP WHERE o.id = :id AND o.status = :expected")
    int markClosed(@Param("id") UUID id,
                   @Param("expected") OrderStatus expected,
                   @Param("target") OrderStatus target,
                   @Param("reason") String reason,
                   @Param("finishTime") LocalDateTime finishTime);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.pointsEarned = :points, o.updatedAt = CURRENT_TIMESTAMP WHERE o.id = :id")
    int updatePointsEarned(@Param("id") UUID id, @Param("points") Integer points);

    /**
     * 支付成功后的补充写入：不整体覆盖实体，避免把已跃迁的状态回写成旧值
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.payMethod = COALESCE(:payMethod, o.payMethod), o.pointsEarned = :points, "
            + "o.updatedAt = CURRENT_TIMESTAMP WHERE o.id = :id")
    int updateAfterPaid(@Param("id") UUID id,
                        @Param("payMethod") String payMethod,
                        @Param("points") Integer points);

    /**
     * 超时未付款订单，交由定时任务取消并回补库存
     */
    @Query("SELECT o FROM Order o WHERE o.status = :status AND o.createdAt < :deadline ORDER BY o.createdAt")
    List<Order> findPayTimeout(@Param("status") OrderStatus status, @Param("deadline") LocalDateTime deadline);

    long countByUserId(UUID userId);

    long countByStatus(OrderStatus status);

    long countByStatusIn(List<OrderStatus> statuses);

    @Query("SELECT COALESCE(SUM(o.payAmount), 0) FROM Order o WHERE o.status IN :statuses")
    BigDecimal sumPayAmount(@Param("statuses") List<OrderStatus> statuses);

    /**
     * 看板趋势：数据库侧按天聚合成交金额与单量，避免把明细拉回内存统计。
     * 口径与概览一致——只统计已支付及后续流转状态，取消/退款订单不计入成交额。
     */
    @Query(value = "SELECT to_char(pay_time, 'YYYY-MM-DD') AS day, COALESCE(SUM(pay_amount), 0) AS amount, COUNT(id) AS orders "
            + "FROM \"order\" WHERE pay_time IS NOT NULL AND pay_time >= :from AND status IN (:statuses) "
            + "GROUP BY 1 ORDER BY 1", nativeQuery = true)
    List<Object[]> sumDailyPaid(@Param("from") LocalDateTime from, @Param("statuses") List<String> statusCodes);

    @Query("SELECT o.user.id, u.username, u.fullName, COUNT(o.id), COALESCE(SUM(o.payAmount), 0) "
            + "FROM Order o JOIN o.user u WHERE o.status IN :statuses "
            + "GROUP BY o.user.id, u.username, u.fullName ORDER BY SUM(o.payAmount) DESC")
    List<Object[]> topSpenders(@Param("statuses") List<OrderStatus> statuses, Pageable pageable);

    @Query("SELECT i.product.id, i.productName, SUM(i.quantity), SUM(i.subtotal) FROM OrderItem i "
            + "WHERE i.order.status IN :statuses GROUP BY i.product.id, i.productName "
            + "ORDER BY SUM(i.quantity) DESC")
    List<Object[]> topProducts(@Param("statuses") List<OrderStatus> statuses, Pageable pageable);
}
