package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 会员与积分规则集中在此，便于后续按运营口径调整
 */
public final class PointsPolicy {

    /** VIP 会员折扣 */
    public static final BigDecimal VIP_DISCOUNT_RATE = new BigDecimal("0.95");

    /** 100 积分抵扣 1 元 */
    public static final int POINTS_PER_YUAN = 100;

    /** 单笔订单最多用积分抵扣应付金额的 20% */
    public static final BigDecimal MAX_DEDUCTION_RATIO = new BigDecimal("0.20");

    private PointsPolicy() {
    }

    public static BigDecimal vipDiscount(BigDecimal totalAmount) {
        return totalAmount.subtract(totalAmount.multiply(VIP_DISCOUNT_RATE)).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal maxDeduction(BigDecimal amountAfterVip) {
        return amountAfterVip.multiply(MAX_DEDUCTION_RATIO).setScale(2, RoundingMode.DOWN);
    }

    public static int pointsFor(BigDecimal yuan) {
        return yuan.multiply(BigDecimal.valueOf(POINTS_PER_YUAN)).setScale(0, RoundingMode.DOWN).intValue();
    }

    public static BigDecimal yuanFor(int points) {
        return BigDecimal.valueOf(points).divide(BigDecimal.valueOf(POINTS_PER_YUAN), 2, RoundingMode.DOWN);
    }

    /** 支付成功后发放积分：1 元 1 分 */
    public static int earnedPoints(BigDecimal payAmount) {
        return payAmount.setScale(0, RoundingMode.DOWN).intValue();
    }
}
