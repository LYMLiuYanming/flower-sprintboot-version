package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.FlowerPlot;
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

public interface FlowerPlotRepository extends JpaRepository<FlowerPlot, UUID> {

    /** 当前在培育/待处理的那块地：一人每块地只有一株，靠 V23 的部分唯一索引保证 */
    Optional<FlowerPlot> findFirstByUserIdAndSlotNoAndStatusIn(UUID userId, int slotNo, List<String> statuses);

    /** 我手上所有还活着的植株（成长中 + 已成熟 + 转赠待确认），用于多块地总览 */
    List<FlowerPlot> findByUserIdAndStatusInOrderBySlotNoAsc(UUID userId, List<String> statuses);

    List<FlowerPlot> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<FlowerPlot> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndStatus(UUID userId, String status);

    long countByUserIdAndSlotNoAndStatus(UUID userId, int slotNo, String status);

    /** 收到的转赠：别人把养成的花送给了谁 */
    List<FlowerPlot> findByGiftUserIdAndStatusOrderByUpdatedAtDesc(UUID giftUserId, String status);

    long countByGiftUserId(UUID giftUserId);

    /** 待我确认的转赠（I13）：按更新时间倒序，最近收到的排前面 */
    List<FlowerPlot> findByGiftUserIdAndGiftStateOrderByUpdatedAtDesc(UUID giftUserId, String giftState,
                                                                     Pageable pageable);

    /**
     * 浇水落库（I11 并发安全）：成长值、阶段、次数一次性写完，并且把「今天还剩几次」放进 WHERE 里。
     * 返回 0 说明这次要么花已经开了、要么当日次数已被另一个请求用完，重复点击不会双发成长值。
     *
     * <p>matureOn 直接取传入值：还在 growing 的地块该列一定是 NULL，成熟时才有值，省掉一个 CASE。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.growth = :growth, p.stage = :stage, p.status = :status, "
            + "p.waterTimes = p.waterTimes + 1, "
            + "p.waterTimesToday = CASE WHEN p.wateredOn = :today THEN p.waterTimesToday + 1 ELSE 1 END, "
            + "p.wateredOn = :today, p.streakDays = :streak, p.matureOn = :matureOn, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.userId = :userId AND p.status = 'growing' "
            + "AND (p.wateredOn IS NULL OR p.wateredOn <> :today OR p.waterTimesToday < :perDay)")
    int waterConditional(@Param("id") UUID id, @Param("userId") UUID userId,
                         @Param("growth") int growth, @Param("stage") String stage,
                         @Param("status") String status, @Param("today") LocalDate today,
                         @Param("streak") int streak, @Param("matureOn") LocalDateTime matureOn,
                         @Param("perDay") int perDay, @Param("now") LocalDateTime now);

    /**
     * 闲置回落写回（I14 曲线要留下回落这一点）：只在成长值真的更低时才写，
     * 且要求仍处于成长中，避免把已成熟的地块又拉回幼苗。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.growth = :growth, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.status = 'growing' AND p.growth > :growth")
    int applyDecay(@Param("id") UUID id, @Param("growth") int growth, @Param("now") LocalDateTime now);

    /**
     * 成熟兑换第一步「占位」：mature → redeemed，只有没兑换过的才抢得到。
     * 抢到之后才去发券，券发失败整笔事务回滚，所以不会出现「券发了、花还挂着能再兑一次」。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.status = 'redeemed', p.updatedAt = :now "
            + "WHERE p.id = :id AND p.userId = :userId AND p.status = 'mature' AND p.redeemedCouponId IS NULL")
    int claimMature(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** 兑换第二步：把发出去的券回填到地块上 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.redeemedCouponId = :userCouponId, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.status = 'redeemed'")
    int attachRedeemedCoupon(@Param("id") UUID id, @Param("userCouponId") UUID userCouponId,
                             @Param("now") LocalDateTime now);

    /** 发起转赠：mature → gifted + 待确认，此时券还没发出去 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.status = 'gifted', p.giftState = 'pending', p.giftUserId = :receiverId, "
            + "p.giftMessage = :message, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.userId = :userId AND p.status = 'mature' "
            + "AND (p.giftState IS NULL OR p.giftState = 'canceled' OR p.giftState = 'declined')")
    int giftConditional(@Param("id") UUID id, @Param("userId") UUID userId, @Param("receiverId") UUID receiverId,
                        @Param("message") String message, @Param("now") LocalDateTime now);

    /** 收礼方确认：只有本人且仍处于 pending 才能落地，返回 0 就是已经处理过 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.giftState = 'accepted', p.redeemedCouponId = :userCouponId, p.updatedAt = :now "
            + "WHERE p.id = :id AND p.giftUserId = :receiverId AND p.giftState = 'pending'")
    int acceptGift(@Param("id") UUID id, @Param("receiverId") UUID receiverId,
                   @Param("userCouponId") UUID userCouponId, @Param("now") LocalDateTime now);

    /** 收礼方婉拒：花退回送礼人的花田（状态回到 mature），券从未发出所以没有额外账要平 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.giftState = 'declined', p.status = 'mature', p.updatedAt = :now "
            + "WHERE p.id = :id AND p.giftUserId = :receiverId AND p.giftState = 'pending'")
    int declineGift(@Param("id") UUID id, @Param("receiverId") UUID receiverId, @Param("now") LocalDateTime now);

    /** 送礼人撤回待确认的转赠 */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE FlowerPlot p SET p.giftState = 'canceled', p.status = 'mature', p.updatedAt = :now "
            + "WHERE p.id = :id AND p.userId = :userId AND p.giftState = 'pending'")
    int cancelGift(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
