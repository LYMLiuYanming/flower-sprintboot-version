package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 退款申请单（C19）：申请、受理、打款、驳回都留痕，一张订单可以驳回后重新申请，
 * 因此用「在途唯一」的部分索引而不是 order_id 上的硬唯一约束。
 */
@Data
@Entity
@Table(name = "order_refund", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class OrderRefund {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 冗余存申请人：审核与统计不必每次都穿过订单再取用户，懒加载在只读事务外会炸 */
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    /** 退款金额一律取订单实付，页面与接口都不重新算（口径来自 CheckoutPolicy） */
    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "detail", length = 500)
    private String detail;

    @Convert(converter = RefundStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status = RefundStatus.PENDING;

    @Column(name = "reviewer", length = 60)
    private String reviewer;

    @Column(name = "review_note", length = 200)
    private String reviewNote;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
