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
 * 花田成熟去向流水（I12/I13）：一块养成的花最终变成谁的哪一张券，都在这里留一条不可回溯的记录。
 *
 * <p>条款是发券那一刻的快照（券名/面额/门槛/有效期），后台改模板不会让历史记录变形；
 * 券的当前状态（未用/已用/已过期）不入这张表，由只读查询按 {@code userCouponId} 现算，避免两处账不一致。
 */
@Data
@Entity
@Table(name = "plot_exchange", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class PlotExchange {

    public static final String TYPE_REDEEM = "redeem";
    public static final String TYPE_GIFT = "gift";

    public static final String STATE_PENDING = "pending";
    public static final String STATE_ACCEPTED = "accepted";
    public static final String STATE_DECLINED = "declined";
    public static final String STATE_CANCELED = "canceled";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "plot_id", nullable = false, columnDefinition = "uuid")
    private UUID plotId;

    /** 养成这块地的人，转赠时它是送礼方 */
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "slot_no", nullable = false)
    private Integer slotNo = 1;

    @Column(name = "seed_code", nullable = false, length = 30)
    private String seedCode;

    @Column(name = "seed_name", length = 60)
    private String seedName;

    @Column(nullable = false, length = 16)
    private String type;

    @Column(nullable = false, length = 16)
    private String state = STATE_ACCEPTED;

    @Column(name = "coupon_code", length = 30)
    private String couponCode;

    @Column(name = "coupon_name", length = 50)
    private String couponName;

    @Column(precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(precision = 10, scale = 2)
    private BigDecimal threshold;

    @Column(name = "valid_days")
    private Integer validDays;

    @Column(name = "user_coupon_id", columnDefinition = "uuid")
    private UUID userCouponId;

    @Column(name = "receiver_id", columnDefinition = "uuid")
    private UUID receiverId;

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(length = 200)
    private String message;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
