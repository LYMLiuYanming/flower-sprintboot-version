package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.VipMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VipMembershipRepository extends JpaRepository<VipMembership, UUID> {

    Optional<VipMembership> findByUserId(UUID userId);

    /**
     * 续费抢占：以「读到的到期时间」为条件原子延长，同一账户并发点两次续费只会有一次生效，
     * 另一次返回 0 由调用方提示刷新，不会出现扣两份积分只延一份期或到期时间被回退
     */
    @Modifying
    @Query("""
            UPDATE VipMembership v SET v.expireAt = :newExpire, v.pointsCost = v.pointsCost + :cost,
                   v.renewCount = v.renewCount + 1, v.status = 'active', v.updatedAt = CURRENT_TIMESTAMP
            WHERE v.userId = :userId AND v.expireAt = :expected
            """)
    int renew(@Param("userId") UUID userId, @Param("expected") LocalDateTime expected,
              @Param("newExpire") LocalDateTime newExpire, @Param("cost") Integer cost);

    /** 到期后落状态，供权益说明页显示「已过期，可重新开通」 */
    @Modifying
    @Query("""
            UPDATE VipMembership v SET v.status = 'expired', v.updatedAt = CURRENT_TIMESTAMP
            WHERE v.userId = :userId AND v.status = 'active' AND v.expireAt < :now
            """)
    int markExpired(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("""
            UPDATE VipMembership v SET v.status = 'expired', v.updatedAt = CURRENT_TIMESTAMP
            WHERE v.status = 'active' AND v.expireAt < :now
            """)
    int markAllExpired(@Param("now") LocalDateTime now);

    /**
     * 会员资格判定要按成交口径统计累计实付（E18 权益说明页的「距开通还差多少」）。
     * 用跨实体 JPQL 一次取回，避免为了一个聚合方法去改别批次的 OrderRepository。
     */
    @Query("SELECT COALESCE(SUM(o.payAmount), 0) FROM Order o WHERE o.user.id = :userId AND o.status IN :statuses")
    BigDecimal sumPaidAmount(@Param("userId") UUID userId, @Param("statuses") List<OrderStatus> statuses);
}
