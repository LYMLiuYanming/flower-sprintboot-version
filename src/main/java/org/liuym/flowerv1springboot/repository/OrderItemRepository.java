package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderItem;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findByOrderId(UUID orderId);

    /** 商品是否被历史订单引用，决定删除还是下架归档 */
    boolean existsByProduct_Id(UUID productId);

    /** 一笔订单涉及的主产地（去重），路线规划据此决定起点，多产地订单取第一个 */
    @Query("SELECT DISTINCT p.originId FROM OrderItem i JOIN i.product p "
            + "WHERE i.order.id = :orderId AND p.originId IS NOT NULL")
    List<UUID> originIdsOfOrder(@Param("orderId") UUID orderId);

    /** 产地发货量：一行一个产地，统计窗口期内成交的枝数与单量 */
    @Query("SELECT p.originId, SUM(i.quantity), COUNT(DISTINCT o.id) FROM OrderItem i "
            + "JOIN i.product p JOIN i.order o "
            + "WHERE o.status IN :statuses AND o.createdAt >= :from AND p.originId IS NOT NULL "
            + "GROUP BY p.originId")
    List<Object[]> salesByOrigin(@Param("statuses") List<OrderStatus> statuses, @Param("from") LocalDateTime from);

    /**
     * 共购推荐：买过目标商品的订单里，其他商品的购买次数排行。
     * 只统计已成交状态，避免待付款/取消的单子把推荐带偏
     */
    @Query("""
            SELECT other.product.id, COUNT(other.id)
            FROM OrderItem me JOIN me.order o JOIN OrderItem other
              ON other.order = o AND other.product.id <> :productId
            WHERE o.id IN (SELECT DISTINCT i.order.id FROM OrderItem i WHERE i.product.id = :productId)
              AND o.status IN :statuses
            GROUP BY other.product.id
            ORDER BY COUNT(other.id) DESC
            """)
    List<Object[]> findCoPurchased(@Param("productId") UUID productId,
                                   @Param("statuses") List<OrderStatus> statuses, Pageable pageable);

    /** 近 N 天真实成交排行（全部分类）：首页畅销位用它，而不是静态 salesCount */
    @Query("""
            SELECT i.product.id, SUM(i.quantity)
            FROM OrderItem i JOIN i.product p JOIN i.order o
            WHERE o.status IN :statuses AND o.createdAt >= :since
            GROUP BY i.product.id
            ORDER BY SUM(i.quantity) DESC
            """)
    List<Object[]> findTopProductIds(@Param("since") LocalDateTime since,
                                     @Param("statuses") List<OrderStatus> statuses, Pageable pageable);

    /** 同上但限定分类集合；分两条而不是塞一个 :noCategory 开关，JPQL 没法为布尔参数做空列表兜底 */
    @Query("""
            SELECT i.product.id, SUM(i.quantity)
            FROM OrderItem i JOIN i.product p JOIN i.order o
            WHERE o.status IN :statuses AND o.createdAt >= :since AND p.category.id IN :categoryIds
            GROUP BY i.product.id
            ORDER BY SUM(i.quantity) DESC
            """)
    List<Object[]> findTopProductIdsInCategories(@Param("since") LocalDateTime since,
                                                 @Param("categoryIds") List<UUID> categoryIds,
                                                 @Param("statuses") List<OrderStatus> statuses, Pageable pageable);
}
