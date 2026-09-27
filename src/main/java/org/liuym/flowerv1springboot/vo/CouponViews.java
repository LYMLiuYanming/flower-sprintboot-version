package org.liuym.flowerv1springboot.vo;

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
            LocalDateTime createdAt) {

        public static CouponView from(Coupon c) {
            return new CouponView(c.getId(), c.getCode(), c.getName(), c.getType(), c.getThreshold(),
                    c.getAmount(), c.getDiscountRate(), c.getMaxDiscount(), c.getTotal(), c.getIssued(),
                    c.getPerUserLimit(), c.getStartTime(), c.getEndTime(), c.getValidDays(), c.getValidEndTime(),
                    c.getScope(), c.getCategoryId(), c.getStatus(), CouponViews.ruleText(c.getType(),
                    c.getThreshold(), c.getAmount(), c.getDiscountRate(), c.getMaxDiscount()),
                    c.getCreatedAt());
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
            BigDecimal discount) {

        public static UserCouponView from(UserCoupon u, BigDecimal discount) {
            return new UserCouponView(u.getId(), u.getCouponId(), u.getCode(), u.getName(), u.getType(),
                    u.getThreshold(), u.getAmount(), u.getDiscountRate(), u.getMaxDiscount(), u.getScope(),
                    u.getCategoryId(), u.getStatus(), u.getExpireAt(), u.getReceivedAt(), u.getUsedAt(),
                    CouponViews.ruleText(u.getType(), u.getThreshold(), u.getAmount(), u.getDiscountRate(),
                            u.getMaxDiscount()), discount);
        }

        public static List<UserCouponView> from(List<UserCoupon> list, java.util.function.Function<UserCoupon, BigDecimal> discountOf) {
            return list.stream().map(u -> from(u, discountOf.apply(u))).toList();
        }
    }

    /** 券面文案：满150减50 / 满100打8.8折（封顶20）。不含适用范围，由列表单列展示 */
    private static String ruleText(String type, BigDecimal threshold, BigDecimal amount,
                                   BigDecimal discountRate, BigDecimal maxDiscount) {
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

    private static boolean isPositive(BigDecimal v) {
        return v != null && v.compareTo(BigDecimal.ZERO) > 0;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
