package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.RefundStatus;
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

    /**
     * 我的订单组合筛选（C01/C02/C03）：状态分组、下单时间范围、订单号/收货人/手机号模糊词一次查完。
     * statuses 由服务层展开成非空集合（无筛选时给全量），因为 JPQL 的 IN 没法接受 null。
     * 这里不 fetch join：分页 + join fetch 会让 Hibernate 退化成内存分页，明细交给 findDetailByIds 批量补。
     */
    @Query(value = """
            SELECT o FROM Order o
            WHERE o.user.id = :userId
              AND o.status IN :statuses
              AND (:from IS NULL OR o.createdAt >= :from)
              AND (:to IS NULL OR o.createdAt <= :to)
              AND (:kw = '' OR o.orderNo LIKE %:kw% OR o.receiverName LIKE %:kw% OR o.receiverPhone LIKE %:kw%)
            """,
            countQuery = """
            SELECT COUNT(o) FROM Order o
            WHERE o.user.id = :userId
              AND o.status IN :statuses
              AND (:from IS NULL OR o.createdAt >= :from)
              AND (:to IS NULL OR o.createdAt <= :to)
              AND (:kw = '' OR o.orderNo LIKE %:kw% OR o.receiverName LIKE %:kw% OR o.receiverPhone LIKE %:kw%)
            """)
    Page<Order> searchForUser(@Param("userId") UUID userId,
                              @Param("statuses") List<OrderStatus> statuses,
                              @Param("from") LocalDateTime from,
                              @Param("to") LocalDateTime to,
                              @Param("kw") String keyword,
                              Pageable pageable);

    /** 分页拿到 ID 后一次取回明细，避免逐单 findDetailById 打成 N+1 */
    @Query("SELECT DISTINCT o FROM Order o LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.product "
            + "WHERE o.id IN :ids")
    List<Order> findDetailByIds(@Param("ids") List<UUID> ids);

    Optional<Order> findByOrderNo(String orderNo);

    /** CAS 失败后回读当前状态，用来说「订单刚被更新为已发货」而不是含糊的刷新重试（C10） */
    @Query("SELECT o.status FROM Order o WHERE o.id = :id")
    OrderStatus findStatusById(@Param("id") UUID id);

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

    /**
     * 退款到账（C19/C20）：把「订单状态 + 退款状态」一起按期望值条件更新，
     * 两个 CAS 合成一条语句，并发审核只会有一条成功，款项与库存不会被退两次
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = :target, o.refundStatus = :refundStatus, "
            + "o.refundAmount = :amount, o.cancelReason = :reason, o.refundedAt = :at, o.finishTime = :at, "
            + "o.updatedAt = CURRENT_TIMESTAMP WHERE o.id = :id AND o.status = :expected")
    int markRefunded(@Param("id") UUID id,
                     @Param("expected") OrderStatus expected,
                     @Param("target") OrderStatus target,
                     @Param("refundStatus") RefundStatus refundStatus,
                     @Param("amount") BigDecimal amount,
                     @Param("reason") String reason,
                     @Param("at") LocalDateTime at);

    /**
     * 提交退款申请（C19）：只允许「本人视角的可退状态」且当前没有 in-flight 申请，
     * 被驳回/已撤销后允许重开。返回 0 说明有人在同时改状态或已有一张在途申请。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.refundStatus = :target, o.refundAmount = :amount, o.refundReason = :reason, "
            + "o.refundRequestedAt = :at, o.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE o.id = :id AND o.status = :expectedStatus AND (o.refundStatus IS NULL OR o.refundStatus IN :reopenable)")
    int markRefundRequested(@Param("id") UUID id,
                            @Param("expectedStatus") OrderStatus expectedStatus,
                            @Param("target") RefundStatus target,
                            @Param("reopenable") List<RefundStatus> reopenable,
                            @Param("amount") BigDecimal amount,
                            @Param("reason") String reason,
                            @Param("at") LocalDateTime at);

    /** 退款申请被撤销/驳回后把镜像列清掉，订单回到正常售后口径 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.refundStatus = :target, o.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE o.id = :id AND o.refundStatus = :expected")
    int transitRefundStatus(@Param("id") UUID id,
                            @Param("expected") RefundStatus expected,
                            @Param("target") RefundStatus target);

    /**
     * 资源回退闸门（C20）：把 rollback_at 从 null 写成当前时间，影响行数 1 才允许回退库存/积分/券/运力。
     * 与订单状态跃迁分开，是因为取消与退款是两条独立路径，共用一次回退机会。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.rollbackAt = :at WHERE o.id = :id AND o.rollbackAt IS NULL")
    int markRollbackGate(@Param("id") UUID id, @Param("at") LocalDateTime at);

    /** 确认收货（用户确认与 15 天自动确认共用）：同时落签收时间，C26 的引导评价以它为锚点 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Order o SET o.status = :target, o.deliverTime = :at, o.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE o.id = :id AND o.status = :expected")
    int markReceived(@Param("id") UUID id,
                     @Param("expected") OrderStatus expected,
                     @Param("target") OrderStatus target,
                     @Param("at") LocalDateTime at);

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
     * 超时未付款订单 ID，交由定时任务逐笔取消并回补库存。
     * 只取 ID 不取实体：关单语句会清空一级缓存，提前加载的实体会在第二笔起变成游离对象
     */
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :deadline ORDER BY o.createdAt")
    List<UUID> findPayTimeoutIds(@Param("status") OrderStatus status, @Param("deadline") LocalDateTime deadline);

    /**
     * 发货后久未确认收货的订单 ID（C18）：同样只取 ID，逐单现取现处理。
     * 按 shipTime 升序，让最久的那批先被自动签收，limit 由服务层用 Pageable 传入
     */
    @Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.shipTime IS NOT NULL "
            + "AND o.shipTime < :deadline ORDER BY o.shipTime")
    List<UUID> findAutoReceiveIds(@Param("status") OrderStatus status, @Param("deadline") LocalDateTime deadline,
                                  Pageable pageable);

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

    /**
     * 销量地图的原料：一行一笔成交订单（地址文本、实付、枝数），城市归属留给服务层按城市字典判定。
     * 不在 SQL 里拆城市，是因为收货地址是自由文本，正则取「市」既会漏也会错，口径集中到 CityGeo 一处更好；
     * 也不在 SQL 里按地址分组，那样一笔订单会随明细行把金额重复累加。
     */
    @Query("SELECT o.receiverAddress, o.payAmount, SUM(i.quantity) "
            + "FROM Order o JOIN o.items i WHERE o.status IN :statuses AND o.createdAt >= :from "
            + "GROUP BY o.id, o.receiverAddress, o.payAmount")
    List<Object[]> salesByOrder(@Param("statuses") List<OrderStatus> statuses, @Param("from") LocalDateTime from);

    @Query("SELECT i.product.id, i.productName, SUM(i.quantity), SUM(i.subtotal) FROM OrderItem i "
            + "WHERE i.order.status IN :statuses GROUP BY i.product.id, i.productName "
            + "ORDER BY SUM(i.quantity) DESC")
    List<Object[]> topProducts(@Param("statuses") List<OrderStatus> statuses, Pageable pageable);
}
