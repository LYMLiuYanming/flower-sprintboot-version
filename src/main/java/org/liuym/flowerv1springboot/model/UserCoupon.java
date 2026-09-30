package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    /** E07：转赠挂起中，券暂时不属于任何人可用，收礼方凭码原子领取 */
    public static final String STATUS_GIFTING = "gifting";

    /** 发放来源 */
    public static final String SOURCE_CLAIM = "claim";
    public static final String SOURCE_ISSUE = "issue";
    /** E15：下单返券 */
    public static final String SOURCE_REBATE = "rebate";
    /** E16：邀请奖励 */
    public static final String SOURCE_INVITE = "invite";
    /** E07：他人转赠 */
    public static final String SOURCE_GIFT = "gift";

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

    /** E02：领取时快照的多品类 category.id 逗号串 */
    @Column(name = "category_ids", length = 500)
    private String categoryIds;

    /** E03：领取时快照的商品白名单 product.id 逗号串 */
    @Column(name = "product_ids", length = 1000)
    private String productIds;

    /** E01：领取时快照的满减阶梯条款 */
    @Column(name = "ladder_rule", length = 200)
    private String ladderRule;

    /** E07：领取时快照的转赠开关，模板后续改动不回溯已发出的券 */
    @Column(name = "allow_transfer", nullable = false)
    private Boolean allowTransfer = false;

    /** E04：是否受「每人每日限领」约束，只有为 true 的行进入每日限领的条件唯一索引 */
    @Column(name = "daily_limited", nullable = false)
    private Boolean dailyLimited = false;

    @Column(name = "claim_date")
    private LocalDate claimDate;

    /** 发放来源：claim/issue/rebate/invite/gift */
    @Column(name = "source", nullable = false, length = 20)
    private String source = SOURCE_CLAIM;

    /** 来源单据 id（返券=订单 id、邀请=邀请关系 id），与条件唯一索引一起保证同一笔业务只发一张 */
    @Column(name = "source_ref", columnDefinition = "uuid")
    private UUID sourceRef;

    /** E07：转赠凭证，收礼方凭它原子领取 */
    @Column(name = "transfer_token", length = 40)
    private String transferToken;

    /** E07：指定收礼人，为空表示凭码通用 */
    @Column(name = "transfer_to_user_id", columnDefinition = "uuid")
    private UUID transferToUserId;

    /** E07：转赠成功后的原持有人，用于券包展示「来自谁」 */
    @Column(name = "transfer_from_user_id", columnDefinition = "uuid")
    private UUID transferFromUserId;

    @Column(name = "transfer_created_at")
    private LocalDateTime transferCreatedAt;

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

    /** 转赠中的券对原持有人不可用，但到期时间照旧走，过期后由懒过期回收 */
    public boolean isGifting() {
        return STATUS_GIFTING.equals(status);
    }

    public boolean isTransferable() {
        return Boolean.TRUE.equals(allowTransfer) && STATUS_UNUSED.equals(status);
    }
}
