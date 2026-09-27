package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.UserCoupon;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCouponRepository extends JpaRepository<UserCoupon, UUID> {

    List<UserCoupon> findByUserIdOrderByExpireAtAsc(UUID userId);

    long countByUserIdAndCouponId(UUID userId, UUID couponId);

    /** 可用券：未使用、未过期，按门槛升序（低门槛优先展示） */
    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.userId = :userId AND u.status = 'unused' AND u.expireAt >= :now
            ORDER BY u.threshold ASC, u.expireAt ASC
            """)
    List<UserCoupon> findUsable(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.userId = :userId AND u.status = 'unused' AND u.expireAt >= :now
            ORDER BY u.threshold ASC
            """)
    List<UserCoupon> findUsable(@Param("userId") UUID userId, @Param("now") LocalDateTime now, Pageable pageable);

    Optional<UserCoupon> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 核销：只有「本人 + 未使用 + 未过期」才扣得动，返回 0 表示券已被用掉或过期
     */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'used', u.usedAt = :now, u.orderId = :orderId
            WHERE u.id = :id AND u.userId = :userId AND u.status = 'unused' AND u.expireAt >= :now
            """)
    int consume(@Param("id") UUID id, @Param("userId") UUID userId,
                @Param("orderId") UUID orderId, @Param("now") LocalDateTime now);

    /** 订单取消/退款后回退为未使用，券仍在有效期内即可继续用 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'unused', u.usedAt = NULL, u.orderId = NULL
            WHERE u.orderId = :orderId AND u.status = 'used'
            """)
    int releaseByOrder(@Param("orderId") UUID orderId);

    /** 懒过期：读取前先落一次状态，避免前端把过期券当可用券展示 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'expired'
            WHERE u.userId = :userId AND u.status = 'unused' AND u.expireAt < :now
            """)
    int markExpired(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
