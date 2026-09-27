package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 优惠券金额口径集中在此：门槛、满减、折扣封顶、范畴（全场/指定分类）基数。
 * 纯函数、无副作用，便于单测覆盖下单时的金额计算。
 */
public final class CouponPolicy {

    private CouponPolicy() {
    }

    /** 参与算价的行项目：分类 + 小计 */
    public record Line(UUID categoryId, BigDecimal amount) {
    }

    /** 券的适用基数：全场券取整单，分类券只取该分类的行项目小计 */
    public static BigDecimal baseAmount(UserCoupon coupon, List<Line> lines) {
        if (coupon == null || lines == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = BigDecimal.ZERO;
        boolean categoryScope = Coupon.SCOPE_CATEGORY.equals(coupon.getScope()) && coupon.getCategoryId() != null;
        for (Line line : lines) {
            if (categoryScope && !coupon.getCategoryId().equals(line.categoryId())) {
                continue;
            }
            base = base.add(line.amount() == null ? BigDecimal.ZERO : line.amount());
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    public static boolean meetsThreshold(UserCoupon coupon, BigDecimal base) {
        BigDecimal threshold = coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
        return base.compareTo(threshold) >= 0;
    }

    /**
     * 本券在这笔金额上能抵多少；不可用（不满足门槛/条款缺失/抵不动）返回 null
     */
    public static BigDecimal discountOf(UserCoupon coupon, BigDecimal base) {
        if (coupon == null || base == null || base.compareTo(BigDecimal.ZERO) <= 0 || !meetsThreshold(coupon, base)) {
            return null;
        }
        BigDecimal discount;
        if (Coupon.TYPE_CASH.equals(coupon.getType())) {
            if (coupon.getAmount() == null || coupon.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            discount = coupon.getAmount();
        } else if (Coupon.TYPE_DISCOUNT.equals(coupon.getType())) {
            BigDecimal rate = coupon.getDiscountRate();
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                return null;
            }
            discount = base.subtract(base.multiply(rate).setScale(2, RoundingMode.HALF_UP));
            if (coupon.getMaxDiscount() != null && coupon.getMaxDiscount().compareTo(BigDecimal.ZERO) > 0) {
                discount = discount.min(coupon.getMaxDiscount());
            }
        } else {
            return null;
        }
        // 抵扣不超过基数本身（分类券尤其如此，避免把整单金额抵穿）
        discount = discount.min(base).setScale(2, RoundingMode.HALF_UP);
        return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : null;
    }
}
