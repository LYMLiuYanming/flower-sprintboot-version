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
 * 邀请关系（E16）：invitee_id 上的唯一索引保证一个被邀请人只能被绑定一次，
 * 首单奖励用条件 UPDATE 把 pending 抢成 rewarded，重复回调只会发一次奖。
 */
@Data
@Entity
@Table(name = "invite_relation", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class InviteRelation {

    /** 已绑定，等待被邀请人首单 */
    public static final String STATUS_PENDING = "pending";
    /** 首单奖励已发放 */
    public static final String STATUS_REWARDED = "rewarded";
    /** 不满足奖励条件（如金额未达门槛），只留痕不再重试 */
    public static final String STATUS_SKIPPED = "skipped";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "invite_code_id", nullable = false, columnDefinition = "uuid")
    private UUID inviteCodeId;

    @Column(name = "inviter_id", nullable = false, columnDefinition = "uuid")
    private UUID inviterId;

    @Column(name = "invitee_id", nullable = false, columnDefinition = "uuid")
    private UUID inviteeId;

    /** 触发奖励的首单 */
    @Column(name = "first_order_id", columnDefinition = "uuid")
    private UUID firstOrderId;

    @Column(name = "reward_status", nullable = false, length = 16)
    private String rewardStatus = STATUS_PENDING;

    @Column(name = "rewarded_at")
    private LocalDateTime rewardedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
