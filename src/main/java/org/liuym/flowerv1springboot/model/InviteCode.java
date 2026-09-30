package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 邀请码（E16）：一人一码，uq_invite_code_user / uq_invite_code_value 两个条件唯一索引
 * 只约束未撤销的行，因此撤销后同一用户可以重新生成新码。
 */
@Data
@Entity
@Table(name = "invite_code", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class InviteCode {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    /** 码主人 */
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    /** 被邀请人首单奖励券模板 */
    @Column(name = "invitee_coupon_id", columnDefinition = "uuid")
    private UUID inviteeCouponId;

    /** 邀请人奖励券模板 */
    @Column(name = "inviter_coupon_id", columnDefinition = "uuid")
    private UUID inviterCouponId;

    /** 邀请人额外奖励积分 */
    @Column(name = "reward_points", nullable = false)
    private Integer rewardPoints = 0;

    /** 累计邀请人数（绑定即计数，与是否首单无关） */
    @Column(name = "invited_count", nullable = false)
    private Integer invitedCount = 0;

    /** 已发过首单奖励的人数 */
    @Column(name = "rewarded_count", nullable = false)
    private Integer rewardedCount = 0;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
