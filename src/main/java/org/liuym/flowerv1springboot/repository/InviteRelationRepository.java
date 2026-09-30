package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.InviteRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteRelationRepository extends JpaRepository<InviteRelation, UUID> {

    Optional<InviteRelation> findByInviteeId(UUID inviteeId);

    List<InviteRelation> findByInviterIdOrderByCreatedAtDesc(UUID inviterId);

    long countByInviterIdAndRewardStatus(UUID inviterId, String rewardStatus);

    long countByInviterId(UUID inviterId);

    /**
     * 首单奖励抢占：只有还挂在 pending 的关系才发得出奖，返回 0 表示已经发过或已判定跳过
     */
    @Modifying
    @Query("""
            UPDATE InviteRelation r SET r.rewardStatus = :target, r.firstOrderId = :orderId,
                   r.rewardedAt = :now, r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.rewardStatus = 'pending'
            """)
    int markRewarded(@Param("id") UUID id, @Param("target") String target,
                     @Param("orderId") UUID orderId, @Param("now") LocalDateTime now);

    /** 金额没到奖励门槛时留痕，避免下一单又被当成首单重试 */
    @Modifying
    @Query("""
            UPDATE InviteRelation r SET r.rewardStatus = 'skipped', r.firstOrderId = :orderId,
                   r.rewardedAt = :now, r.updatedAt = CURRENT_TIMESTAMP
            WHERE r.id = :id AND r.rewardStatus = 'pending'
            """)
    int markSkipped(@Param("id") UUID id, @Param("orderId") UUID orderId, @Param("now") LocalDateTime now);
}
