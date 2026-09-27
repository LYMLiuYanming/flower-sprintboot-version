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
 * 优惠券模板：满减（cash）或折扣（discount），领取/直发时条款会快照到 user_coupon
 */
@Data
@Entity
@Table(name = "coupon", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Coupon {

    /** 满减券：满 threshold 减 amount */
    public static final String TYPE_CASH = "cash";
    /** 折扣券：满 threshold 按 discountRate 折扣，最多抵 maxDiscount */
    public static final String TYPE_DISCOUNT = "discount";

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_CATEGORY = "category";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Column(name = "type", nullable = false, length = 20)
    private String type = TYPE_CASH;

    /** 使用门槛：适用金额满此数才可用，0 表示无门槛 */
    @Column(name = "threshold", nullable = false, precision = 10, scale = 2)
    private BigDecimal threshold = BigDecimal.ZERO;

    @Column(name = "amount", precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "discount_rate", precision = 4, scale = 2)
    private BigDecimal discountRate;

    @Column(name = "max_discount", precision = 10, scale = 2)
    private BigDecimal maxDiscount;

    /** 发行总量，0 表示不限量 */
    @Column(name = "total", nullable = false)
    private Integer total = 0;

    @Column(name = "issued", nullable = false)
    private Integer issued = 0;

    @Column(name = "per_user_limit", nullable = false)
    private Integer perUserLimit = 1;

    /** 领取窗口，空表示立即开始 */
    @Column(name = "start_time")
    private LocalDateTime startTime;

    /** 领取截止，空表示不限制 */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 领取后有效天数，>0 时优先于 validEndTime */
    @Column(name = "valid_days", nullable = false)
    private Integer validDays = 0;

    @Column(name = "valid_end_time")
    private LocalDateTime validEndTime;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope = SCOPE_ALL;

    @Column(name = "category_id", columnDefinition = "uuid")
    private UUID categoryId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 领取后何时过期：相对天数优先，其次固定到期时间，最后落到领取截止 */
    public LocalDateTime expireAt(LocalDateTime receivedAt) {
        if (validDays != null && validDays > 0) {
            return receivedAt.plusDays(validDays);
        }
        if (validEndTime != null) {
            return validEndTime;
        }
        return endTime != null ? endTime : receivedAt.plusYears(1);
    }
}
