package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.UserCoupon;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 结算页专用只读查询（B09 读券、B24 读历史订单）。
 *
 * <p>刻意只继承 Repository 标记接口而不带任何写方法：结算流程不该有能力改动券、订单或商品，
 * 需要跨模块取数时走这里，避免往别人的 repository 上塞方法。
 */
public interface CheckoutQueryRepository extends Repository<UserCoupon, UUID> {

    /** 持券可用性判定必须同时看状态与到期时间，光看 status 会把没来得及懒过期的券算进来 */
    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.userId = :userId AND u.status = 'unused' AND u.expireAt >= :now
            ORDER BY u.expireAt ASC, u.threshold ASC
            """)
    List<UserCoupon> findUsableCoupons(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Query("SELECT u FROM UserCoupon u WHERE u.id = :id AND u.userId = :userId")
    UserCoupon findCouponOfUser(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * 分类专享券的适用范围：券绑定的分类本身 + 直接子分类。
     * 用原生 SQL 而不是借 CategoryRepository，结算批次不依赖分类模块的演化。
     */
    @Query(value = "SELECT id FROM category WHERE id = CAST(:root AS uuid) OR parent_id = CAST(:root AS uuid)",
            nativeQuery = true)
    List<UUID> categoryScopeIds(@Param("root") UUID root);

    /** 再次购买（B24）：历史订单明细摊平成纯值，避免懒加载商品在只读事务外炸开 */
    @Query("""
            SELECT p.id AS productId,
                   coalesce(i.productName, '') AS productName,
                   coalesce(i.productImage, '') AS productImage,
                   coalesce(i.quantity, 0) AS quantity,
                   i.price AS orderPrice,
                   coalesce(p.price, i.price) AS currentPrice,
                   coalesce(p.stock, 0) AS stock,
                   coalesce(p.isActive, false) AS active
            FROM OrderItem i LEFT JOIN i.product p
            WHERE i.order.id = :orderId
            ORDER BY i.id ASC
            """)
    List<ReorderLine> findReorderLines(@Param("orderId") UUID orderId);

    /** 订单归属校验：越权再次购买时在进入写路径之前就拒掉 */
    @Query("SELECT o.user.id FROM Order o WHERE o.id = :orderId")
    UUID findOrderOwnerId(@Param("orderId") UUID orderId);

    interface ReorderLine {
        UUID getProductId();

        String getProductName();

        String getProductImage();

        Integer getQuantity();

        BigDecimal getOrderPrice();

        BigDecimal getCurrentPrice();

        Integer getStock();

        Boolean getActive();
    }
}
