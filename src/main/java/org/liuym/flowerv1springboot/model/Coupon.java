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

    /** 停用后已领券保留可用 */
    public static final String DISABLE_KEEP = "keep";
    /** 停用后未领券立即作废 */
    public static final String DISABLE_VOID = "void";

    /** 领券中心可领 */
    public static final String SCENE_CLAIM = "claim";
    /** 仅后台直发或活动内部发放，不进领券中心 */
    public static final String SCENE_ADMIN = "admin";
    /** 支付成功后自动返券 */
    public static final String SCENE_AFTER_PAY = "after_pay";
    /** 邀请奖励券 */
    public static final String SCENE_INVITE = "invite";

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

    /** E02：限品类多选，逗号分隔的 category.id；与 categoryId 同时存在时取并集 */
    @Column(name = "category_ids", length = 500)
    private String categoryIds;

    /** E03：限定商品白名单，逗号分隔的 product.id；非空时只有名单内商品参与算价 */
    @Column(name = "product_ids", length = 1000)
    private String productIds;

    /** E01：满减阶梯 "199:20,399:60"，非空时优先于 threshold + amount 单档口径 */
    @Column(name = "ladder_rule", length = 200)
    private String ladderRule;

    /** E04：每人每日可领张数，0 表示不按天限制 */
    @Column(name = "per_user_daily_limit", nullable = false)
    private Integer perUserDailyLimit = 0;

    /** E05：新客专享，判定口径见 CouponService#isNewCustomer */
    @Column(name = "new_user_only", nullable = false)
    private Boolean newUserOnly = false;

    /** E07：是否允许转赠给其他用户 */
    @Column(name = "allow_transfer", nullable = false)
    private Boolean allowTransfer = false;

    /** E18：会员专享，非空时只有该等级及以上会员可领取 */
    @Column(name = "member_only", nullable = false)
    private Boolean memberOnly = false;

    /** E12：停用后已领券的处理策略 keep/void */
    @Column(name = "disable_policy", nullable = false, length = 10)
    private String disablePolicy = DISABLE_KEEP;

    /** 发放场景：claim/admin/after_pay/invite */
    @Column(name = "trigger_scene", nullable = false, length = 20)
    private String triggerScene = SCENE_CLAIM;

    /** E15：返券门槛，实付满此数才发 */
    @Column(name = "grant_min_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal grantMinAmount = BigDecimal.ZERO;

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
