package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 优惠券视图：模板（后台）与持券（前台/结算页）两套输出
 */
public final class CouponViews {

    private CouponViews() {
    }

    public record CouponView(
            UUID id,
            String code,
            String name,
            String type,
            BigDecimal threshold,
            BigDecimal amount,
            BigDecimal discountRate,
            BigDecimal maxDiscount,
            Integer total,
            Integer issued,
            Integer perUserLimit,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer validDays,
            LocalDateTime validEndTime,
            String scope,
            UUID categoryId,
            String status,
            String ruleText,
            LocalDateTime createdAt,
            /** E01 满减阶梯原文与自动生成的文案 */
            String ladderRule,
            String ladderText,
            /** E02/E03 适用范围：id 逗号串 + E20 自动文案 */
            List<UUID> categoryIds,
            List<UUID> productIds,
            String scopeText,
            /** E04/E05/E07/E18/E12 */
            Integer perUserDailyLimit,
            Boolean newUserOnly,
            Boolean allowTransfer,
            Boolean memberOnly,
            String disablePolicy,
            String triggerScene,
            BigDecimal grantMinAmount,
            /** E13 统计 */
            Long claimedCount,
            Long usedCount,
            BigDecimal useRate,
            /** 前台：给定用户此刻能不能领、不能领的原因（E10 卡片标记） */
            Boolean claimable,
            String claimBlockReason) {

        public static CouponView from(Coupon c) {
            return from(c, null, null);
        }

        /** scopeText 由服务层按库里的分类名/商品数生成（E20），这里只负责拼装输出 */
        public static CouponView from(Coupon c, String scopeText, String claimBlockReason) {
            return new CouponView(c.getId(), c.getCode(), c.getName(), c.getType(), c.getThreshold(),
                    c.getAmount(), c.getDiscountRate(), c.getMaxDiscount(), c.getTotal(), c.getIssued(),
                    c.getPerUserLimit(), c.getStartTime(), c.getEndTime(), c.getValidDays(), c.getValidEndTime(),
                    c.getScope(), c.getCategoryId(), c.getStatus(),
                    // record 自带同名零参访问器，需限定到外层类才能命中有参的文案函数
                    CouponViews.ruleText(c.getType(), c.getThreshold(), c.getAmount(), c.getDiscountRate(), c.getMaxDiscount(),
                            c.getLadderRule()),
                    c.getCreatedAt(),
                    c.getLadderRule(), CouponViews.ladderText(c.getLadderRule()),
                    CouponPolicy.parseIds(c.getCategoryIds()), CouponPolicy.parseIds(c.getProductIds()),
                    scopeText == null ? fallbackScopeText(c.getScope()) : scopeText,
                    c.getPerUserDailyLimit(), c.getNewUserOnly(), c.getAllowTransfer(), c.getMemberOnly(),
                    c.getDisablePolicy(), c.getTriggerScene(), c.getGrantMinAmount(),
                    null, null, null,
                    claimBlockReason == null, claimBlockReason);
        }

        /** E13：后台列表带上领取/核销/核销率 */
        public static CouponView withStats(Coupon c, String scopeText, long claimed, long used, BigDecimal useRate) {
            CouponView base = from(c, scopeText, null);
            return new CouponView(base.id(), base.code(), base.name(), base.type(), base.threshold(), base.amount(),
                    base.discountRate(), base.maxDiscount(), base.total(), base.issued(), base.perUserLimit(),
                    base.startTime(), base.endTime(), base.validDays(), base.validEndTime(), base.scope(),
                    base.categoryId(), base.status(), base.ruleText(), base.createdAt(), base.ladderRule(),
                    base.ladderText(), base.categoryIds(), base.productIds(), base.scopeText(),
                    base.perUserDailyLimit(), base.newUserOnly(), base.allowTransfer(), base.memberOnly(),
                    base.disablePolicy(), base.triggerScene(), base.grantMinAmount(),
                    claimed, used, useRate, true, null);
        }

        public static List<CouponView> from(List<Coupon> list) {
            return list.stream().map(CouponView::from).toList();
        }
    }

    public record UserCouponView(
            UUID id,
            UUID couponId,
            String code,
            String name,
            String type,
            BigDecimal threshold,
            BigDecimal amount,
            BigDecimal discountRate,
            BigDecimal maxDiscount,
            String scope,
            UUID categoryId,
            String status,
            LocalDateTime expireAt,
            LocalDateTime receivedAt,
            LocalDateTime usedAt,
            String ruleText,
            /** 结算页用：给定金额下本券可抵多少，null 表示当前金额不可用 */
            BigDecimal discount,
            /** E01/E02/E03/E20 快照条款 */
            String ladderText,
            String scopeText,
            /** E06 到期提醒 */
            String expireReminder,
            /** E07 转赠 */
            Boolean transferable,
            String transferToken,
            UUID transferToUserId,
            UUID transferFromUserId,
            /** E08：discount 为 null 时的明确原因与能否重试 */
            String unavailableReason,
            Boolean retryable,
            String source) {

        public static UserCouponView from(UserCoupon u, BigDecimal discount) {
            return from(u, discount, null, null);
        }

        public static UserCouponView from(UserCoupon u, BigDecimal discount, String scopeText, String reason) {
            LocalDateTime now = LocalDateTime.now();
            return new UserCouponView(u.getId(), u.getCouponId(), u.getCode(), u.getName(), u.getType(),
                    u.getThreshold(), u.getAmount(), u.getDiscountRate(), u.getMaxDiscount(), u.getScope(),
                    u.getCategoryId(), u.getStatus(), u.getExpireAt(), u.getReceivedAt(), u.getUsedAt(),
                    CouponViews.ruleText(u.getType(), u.getThreshold(), u.getAmount(), u.getDiscountRate(),
                            u.getMaxDiscount(), u.getLadderRule()),
                    discount,
                    CouponViews.ladderText(u.getLadderRule()),
                    scopeText == null ? fallbackScopeText(u.getScope()) : scopeText,
                    CouponPolicy.expireReminder(u, now),
                    u.isTransferable(), u.getTransferToken(), u.getTransferToUserId(), u.getTransferFromUserId(),
                    reason, CouponViews.retryable(reason), u.getSource());
        }

        public static List<UserCouponView> from(List<UserCoupon> list, java.util.function.Function<UserCoupon, BigDecimal> discountOf) {
            return list.stream().map(u -> from(u, discountOf.apply(u))).toList();
        }
    }

    /**
     * E13：单张券模板的使用统计
     */
    public record CouponStatView(
            UUID couponId,
            String code,
            String name,
            Integer total,
            Integer issued,
            Long claimedCount,
            Long usedCount,
            BigDecimal useRate,
            BigDecimal usedAmount) {
    }

    /**
     * E09：结算页「券 + 积分」组合建议的单条方案
     */
    public record ComboView(
            String name,
            BigDecimal coupon,
            BigDecimal points,
            BigDecimal total,
            String note,
            boolean best) {

        public static List<ComboView> of(List<CampaignPolicy.ComboOption> options) {
            return options.stream()
                    .map(o -> new ComboView(o.name(), o.coupon(), o.points(), o.total(), o.note(), o.best()))
                    .toList();
        }
    }

    /** E09：组合建议的整体输出，页面只渲染不再自己算 */
    public record ComboPlan(
            BigDecimal base,
            BigDecimal couponDiscount,
            String couponName,
            int points,
            String summary,
            List<ComboView> options) {
    }

    /** 券面文案：阶梯券列全部档位，单档满减满150减50，折扣满100打8.8折（封顶20）。不含适用范围 */
    private static String ruleText(String type, BigDecimal threshold, BigDecimal amount,
                                   BigDecimal discountRate, BigDecimal maxDiscount, String ladderRule) {
        String ladder = ladderText(ladderRule);
        if (!ladder.isEmpty()) {
            return ladder + "（自动取最优档）";
        }
        boolean hasThreshold = isPositive(threshold);
        if ("cash".equals(type)) {
            return hasThreshold ? "满" + plain(threshold) + "减" + plain(amount) : "无门槛减" + plain(amount);
        }
        if ("discount".equals(type) && isPositive(discountRate)) {
            String text = (hasThreshold ? "满" + plain(threshold) : "") + "打" + plain(discountRate.multiply(BigDecimal.TEN)) + "折";
            return isPositive(maxDiscount) ? text + "（封顶" + plain(maxDiscount) + "）" : text;
        }
        return hasThreshold ? "满" + plain(threshold) + "可用" : "";
    }

    private static String ladderText(String ladderRule) {
        return CampaignPolicy.ladderText(ladderRule);
    }

    /** 快照里没有分类名时给出的兜底文案，服务层会带上真实分类名覆盖它 */
    private static String fallbackScopeText(String scope) {
        return Coupon.SCOPE_CATEGORY.equals(scope) ? "指定分类可用" : "全场花礼通用";
    }

    /** 只有「门槛没凑够」「被人抢先核销」这两类值得再试一次，其余重试还是失败 */
    private static Boolean retryable(String reason) {
        if (reason == null) {
            return false;
        }
        return reason.contains("还差") || reason.contains("已被") || reason.contains("状态已变更");
    }

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
