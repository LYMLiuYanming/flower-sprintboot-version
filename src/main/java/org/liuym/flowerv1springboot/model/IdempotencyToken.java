package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 下单幂等凭证：结算页进入时领取，提交时透传，一行只允许生成一张订单。
 *
 * <p>抢占走条件 UPDATE（claimed_at IS NULL），与建单同事务；校验失败回滚后凭证自动可再用，
 * 只有真正成单才被消费，因此用户改个数量重试不需要重新领号。
 */
@Data
@Entity
@Table(name = "idempotency_token", schema = "public",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_token", columnNames = {"token"}))
@EntityListeners(AuditingEntityListener.class)
public class IdempotencyToken {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 64)
    private String token;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    /** 凭证最终生成的订单，重放时按它回吐首次结果 */
    @Column(name = "order_id", columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
