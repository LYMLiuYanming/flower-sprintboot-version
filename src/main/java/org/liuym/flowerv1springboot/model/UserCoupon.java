package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户持券：条款为领取时刻的模板快照，后台改模板不会影响已发出的券
 */
@Data
@Entity
@Table(name = "user_coupon", schema = "public")
public class UserCoupon {

    public static final String STATUS_UNUSED = "unused";
    public static final String STATUS_USED = "used";
    public static final String STATUS_EXPIRED = "expired";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "coupon_id", nullable = false, columnDefinition = "uuid")
    private UUID couponId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    @Column(name = "threshold", nullable = false, precision = 10, scale = 2)
    private BigDecimal threshold = BigDecimal.ZERO;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "discount_rate", precision = 4, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope = Coupon.SCOPE_ALL;

    @Column(name = "category_id", columnDefinition = "uuid")
    private UUID categoryId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_UNUSED;

    @Column(name = "expire_at", nullable = false)
    private LocalDateTime expireAt;

    @Column(name = "order_id", columnDefinition = "uuid")
    private UUID orderId;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public boolean isExpiredAt(LocalDateTime now) {
        return expireAt != null && expireAt.isBefore(now);
    }
}
