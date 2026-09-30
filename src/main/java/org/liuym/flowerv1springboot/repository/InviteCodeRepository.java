package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.InviteCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InviteCodeRepository extends JpaRepository<InviteCode, UUID> {

    Optional<InviteCode> findByUserIdAndRevokedAtIsNull(UUID userId);

    Optional<InviteCode> findByCodeAndRevokedAtIsNull(String code);

    List<InviteCode> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** 被邀请人首单成功后 +1，条件更新保证奖励与计数一起走完 */
    @Modifying
    @Query("""
            UPDATE InviteCode c SET c.rewardedCount = c.rewardedCount + 1, c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id
            """)
    int increaseRewardedCount(@Param("id") UUID id);

    /** 绑定新被邀请人时的计数：只在关系真的建成之后调用，失败的交易整笔回滚不会虚增 */
    @Modifying
    @Query("""
            UPDATE InviteCode c SET c.invitedCount = c.invitedCount + 1, c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id
            """)
    int increaseInvitedCount(@Param("id") UUID id);

    @Modifying
    @Query("""
            UPDATE InviteCode c SET c.revokedAt = :now, c.updatedAt = CURRENT_TIMESTAMP
            WHERE c.id = :id AND c.revokedAt IS NULL
            """)
    int revoke(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
