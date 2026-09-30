package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserCouponRepository extends JpaRepository<UserCoupon, UUID> {

    List<UserCoupon> findByUserIdOrderByExpireAtAsc(UUID userId);

    Optional<UserCoupon> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndCouponId(UUID userId, UUID couponId);

    /** E04：按天计数，日限领判定用 */
    @Query("""
            SELECT COUNT(u) FROM UserCoupon u
            WHERE u.userId = :userId AND u.couponId = :couponId AND u.claimDate = :day
            """)
    long countByUserIdAndCouponIdOnDay(@Param("userId") UUID userId,
                                       @Param("couponId") UUID couponId,
                                       @Param("day") LocalDate day);

    /** E15/E16 幂等：同一笔来源单据是否已经发过这张券 */
    boolean existsByCouponIdAndSourceRef(UUID couponId, UUID sourceRef);

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

    /** E06：即将到期的未使用券，列表提醒与角标共用一次查询 */
    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.userId = :userId AND u.status = 'unused'
              AND u.expireAt >= :now AND u.expireAt <= :until
            ORDER BY u.expireAt ASC
            """)
    List<UserCoupon> findExpiringSoon(@Param("userId") UUID userId,
                                      @Param("now") LocalDateTime now,
                                      @Param("until") LocalDateTime until);

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

    /** 懒过期：读取前先落一次状态，避免前端把过期券当可用券展示；转赠中的券到期一并作废 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'expired', u.transferToken = NULL, u.transferToUserId = NULL
            WHERE u.userId = :userId AND u.status IN ('unused', 'gifting') AND u.expireAt < :now
            """)
    int markExpired(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** E12：模板停用且策略为作废时，把该模板下未使用的券一次性落过期，已用的不动 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'expired'
            WHERE u.couponId = :couponId AND u.status IN ('unused', 'gifting')
            """)
    int voidByCoupon(@Param("couponId") UUID couponId);

    /**
     * E07：发起转赠。条件更新把券锁成 gifting，只有本人持有的未使用未过期券能锁上，
     * 返回 0 表示这张券已经被用掉/转赠中/不属于本人
     */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'gifting', u.transferToken = :token,
                   u.transferToUserId = :toUserId, u.transferCreatedAt = :now
            WHERE u.id = :id AND u.userId = :userId AND u.status = 'unused'
              AND u.allowTransfer = true AND u.expireAt > :now
            """)
    int lockForTransfer(@Param("id") UUID id, @Param("userId") UUID userId, @Param("toUserId") UUID toUserId,
                        @Param("token") String token, @Param("now") LocalDateTime now);

    /** E07：撤销转赠，券回到本人手里 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'unused', u.transferToken = NULL,
                   u.transferToUserId = NULL, u.transferCreatedAt = NULL
            WHERE u.id = :id AND u.userId = :userId AND u.status = 'gifting'
            """)
    int cancelTransfer(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * E07：原子领取转赠。持有人从原主变更为目标用户，status 从 gifting 改成 unused，
     * 条件里带上 token 与原持有人，两个人同时接受时只有一个人能拿到 1 行
     */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.transferFromUserId = u.userId, u.userId = :toUserId,
                   u.status = 'unused', u.source = 'gift', u.transferToken = NULL,
                   u.transferToUserId = NULL, u.transferCreatedAt = NULL, u.receivedAt = :now
            WHERE u.transferToken = :token AND u.status = 'gifting'
            """)
    int acceptTransfer(@Param("token") String token, @Param("toUserId") UUID toUserId,
                       @Param("now") LocalDateTime now);

    /** 转赠超时未领取：把券退回原持有人，避免锁死在 gifting 状态 */
    @Modifying
    @Query("""
            UPDATE UserCoupon u SET u.status = 'unused', u.transferToken = NULL,
                   u.transferToUserId = NULL, u.transferCreatedAt = NULL
            WHERE u.status = 'gifting' AND u.transferCreatedAt < :deadline
            """)
    int releaseStaleTransfers(@Param("deadline") LocalDateTime deadline);

    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.transferToken = :token AND u.status = 'gifting'
            """)
    Optional<UserCoupon> findPendingTransfer(@Param("token") String token);

    /** 别人指名赠给我的、还没领的转赠 */
    @Query("""
            SELECT u FROM UserCoupon u
            WHERE u.transferToUserId = :userId AND u.status = 'gifting'
            ORDER BY u.transferCreatedAt DESC
            """)
    List<UserCoupon> findIncomingTransfers(@Param("userId") UUID userId);

    /** E13：按模板聚合领取数与核销数，后台统计页一次查完，不逐张券 count */
    @Query("""
            SELECT u.couponId, COUNT(u.id),
                   SUM(CASE WHEN u.status = 'used' THEN 1 ELSE 0 END),
                   COALESCE(SUM(CASE WHEN u.status = 'used' THEN u.amount ELSE 0 END), 0)
            FROM UserCoupon u
            GROUP BY u.couponId
            """)
    List<Object[]> aggregateByCoupon();

    /** 结算/下单校验时按 id 批量取券，避免逐张查库 */
    List<UserCoupon> findByIdInAndUserId(List<UUID> ids, UUID userId);

    /**
     * 新客判定（E05）与会员资格（E18）要按成交口径统计订单，
     * 这里用跨实体 JPQL 一次取回，避免为了两个统计方法去改别的批次的 OrderRepository
     */
    @Query("SELECT COUNT(o) FROM Order o WHERE o.user.id = :userId AND o.status IN :statuses")
    long countDealOrders(@Param("userId") UUID userId, @Param("statuses") List<OrderStatus> statuses);
}
