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
 * 会员资格（E18）：积分开通/续费的到期记录。
 *
 * <p>只登记「积分开通」这一路：消费达标的自动升级仍由下单链路写 user.member_level（长期有效，不落本表）。
 * 判定顺序为「本表有未过期记录 → 限时会员」，否则看 member_level，两套来源不会互相覆盖。
 * user_id 上的唯一索引 + 到期时间的条件 UPDATE 保证并发点击开通只会成功一次。
 */
@Data
@Entity
@Table(name = "vip_membership", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class VipMembership {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_EXPIRED = "expired";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "level", nullable = false, length = 20)
    private String level = User.MEMBER_VIP;

    /** 开通来源：points 积分开通 */
    @Column(name = "source", nullable = false, length = 20)
    private String source = "points";

    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_ACTIVE;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    /** 续费次数，权益说明页展示「已续费 N 次」 */
    @Column(name = "renew_count", nullable = false)
    private Integer renewCount = 0;

    /** 累计花费的积分，便于对账 */
    @Column(name = "points_cost", nullable = false)
    private Integer pointsCost = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean validAt(LocalDateTime now) {
        return STATUS_ACTIVE.equals(status) && expireAt != null && expireAt.isAfter(now);
    }

    /** 未过期时长（天），向下取整；已过期返回 0 */
    public long remainingDays(LocalDateTime now) {
        if (!validAt(now)) {
            return 0;
        }
        return Math.max(0, java.time.Duration.between(now, expireAt).toDays());
    }
}
