package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Coupon;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID> {

    Optional<Coupon> findByCode(String code);

    List<Coupon> findByStatusOrderByCreatedAtDesc(String status);

    List<Coupon> findAllByOrderByCreatedAtDesc();

    /**
     * 抢占一张库存：总量为 0 表示不限量。单条 UPDATE 由行锁保证并发安全，
     * 返回 0 即代表已被抢完，调用方据此提示「已领完」
     */
    @Modifying
    @Query("""
            UPDATE Coupon c SET c.issued = c.issued + 1
            WHERE c.id = :id AND c.status = 'active'
              AND (c.total = 0 OR c.issued < c.total)
              AND (c.startTime IS NULL OR c.startTime <= :now)
              AND (c.endTime IS NULL OR c.endTime >= :now)
            """)
    int reserveOne(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 撤回发放（如领取后校验失败）：只回退已发放数，不会减成负数 */
    @Modifying
    @Query("UPDATE Coupon c SET c.issued = CASE WHEN c.issued > 0 THEN c.issued - 1 ELSE 0 END WHERE c.id = :id")
    int releaseOne(@Param("id") UUID id);
}
