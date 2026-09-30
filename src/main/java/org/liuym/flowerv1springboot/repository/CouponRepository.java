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

    /**
     * 券码不再全表唯一（V21 改成「启用中同码唯一」的条件唯一索引），
     * 所以按码取模板要显式定优先级：启用中的那张优先，其次最近建的。
     * 花田奖励等按 code 发放的调用方因此永远拿到在售的那张。
     */
    @Query("""
            SELECT c FROM Coupon c
            WHERE c.code = :code
            ORDER BY CASE WHEN c.status = 'active' THEN 0 ELSE 1 END, c.createdAt DESC
            """)
    List<Coupon> findAllByCode(String code);

    default Optional<Coupon> findByCode(String code) {
        return findAllByCode(code).stream().findFirst();
    }

    List<Coupon> findByStatusOrderByCreatedAtDesc(String status);

    List<Coupon> findAllByOrderByCreatedAtDesc();

    /** 领券中心只放「可领」场景的模板：花田奖励、返券、邀请奖励由服务端按场景直发 */
    @Query("""
            SELECT c FROM Coupon c
            WHERE c.status = 'active' AND c.triggerScene = 'claim'
              AND (c.startTime IS NULL OR c.startTime <= :now)
              AND (c.endTime IS NULL OR c.endTime >= :now)
            ORDER BY c.endTime NULLS LAST, c.createdAt DESC
            """)
    List<Coupon> findClaimable(@Param("now") LocalDateTime now);

    /** 场景直发用：启用中的该场景模板，按门槛升序，返券取最低门槛的那张先满足 */
    @Query("""
            SELECT c FROM Coupon c
            WHERE c.status = 'active' AND c.triggerScene = :scene
            ORDER BY c.grantMinAmount ASC, c.createdAt ASC
            """)
    List<Coupon> findByScene(@Param("scene") String scene);

    List<Coupon> findByIdIn(List<UUID> ids);

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

    /**
     * 场景直发（返券 / 邀请奖励 / 后台直发）用的抢占：这类券不该被领取窗口挡在门外，
     * 只认「启用中 + 还有余量」，同样靠行锁保证并发安全
     */
    @Modifying
    @Query("""
            UPDATE Coupon c SET c.issued = c.issued + 1
            WHERE c.id = :id AND c.status = 'active' AND (c.total = 0 OR c.issued < c.total)
            """)
    int reserveForScene(@Param("id") UUID id);

    /** 撤回发放（如领取后校验失败）：只回退已发放数，不会减成负数 */
    @Modifying
    @Query("UPDATE Coupon c SET c.issued = CASE WHEN c.issued > 0 THEN c.issued - 1 ELSE 0 END WHERE c.id = :id")
    int releaseOne(@Param("id") UUID id);

    /** 复制新建时的券码占用检查：条件唯一索引只管启用中的券码，这里要连下架副本一起避开 */
    @Query("SELECT COUNT(c) FROM Coupon c WHERE c.code = :code")
    long countByCode(@Param("code") String code);
}
