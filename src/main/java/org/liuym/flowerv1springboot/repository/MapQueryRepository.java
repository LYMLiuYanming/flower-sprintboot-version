package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Product;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 地图 / 花田批次的只读查询出口（I02/I12）。
 *
 * <p>刻意只继承 {@link Repository} 而不是 JpaRepository：这个接口没有任何保存与删除方法，
 * 地图功能要读别人的表（商品、券），但不该有任何一处能写回去，编译期就把它挡住。
 * 泛型参数取 Product 只是为了让 Spring Data 解析出实体元数据，本接口并不限定查询对象。
 */
public interface MapQueryRepository extends Repository<Product, UUID> {

    /**
     * 产地关联商品（I02）：下架商品也返回但排在后面，后台与产地卡片的「在售 / 已下架」都要能数出来
     */
    @Query("SELECT p.id, p.name, p.price, p.mainImage, p.salesCount, p.unit, p.stock, p.isActive "
            + "FROM Product p WHERE p.originId = :originId "
            + "ORDER BY p.isActive DESC, p.salesCount DESC, p.name ASC")
    List<Object[]> productsOfOrigin(@Param("originId") UUID originId);

    /** 节点被多少商品挂着：后台删除前用来给出「有 12 款商品引用，只能下架」的提示 */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.originId = :originId")
    long productCountOfOrigin(@Param("originId") UUID originId);

    /**
     * 券包联动（I12）：按兑换记录里的 user_coupon_id 反查券的当前状态。
     * 状态由券批次维护，这里只读不回写，避免花田去改别人的账。
     */
    @Query("SELECT u.id, u.status, u.expireAt, u.usedAt, u.orderId, u.name, u.amount, u.threshold "
            + "FROM UserCoupon u WHERE u.id IN :ids")
    List<Object[]> couponStates(@Param("ids") List<UUID> ids);

    /** 花田奖励券在本人券包里的总数，用于「已兑换 N 张」的口径核对 */
    @Query("SELECT COUNT(u) FROM UserCoupon u WHERE u.userId = :userId AND u.code LIKE 'GARDEN-%'")
    long gardenCouponCount(@Param("userId") UUID userId);

    /** 花田奖励券里还可用（未使用且未过期）的张数 */
    @Query("SELECT COUNT(u) FROM UserCoupon u WHERE u.userId = :userId AND u.code LIKE 'GARDEN-%' "
            + "AND u.status = 'unused' AND u.expireAt >= :now")
    long gardenCouponUsable(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
