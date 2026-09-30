package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * 后台批量改价策略（G06）：百分比 / 固定增减 / 统一价 三种口径，外加取整位数与上下限钳制。
 * 单独成类是为了让「预览」与「落库」共用同一套算价逻辑——两处各写一遍必然算出两个价。
 *
 * @param mode      percent / delta / set
 * @param value     percent 为百分数（-10 表示降价 10%）；delta 为增减金额；set 为目标价
 * @param roundTo   取整小数位：0=整元、1=到角、2=到分
 * @param minPrice  下限（钳制后低于它直接抬到它，避免批量降价把商品改成 0 元）
 * @param maxPrice  上限
 */
public record BatchPricePolicy(String mode, BigDecimal value, Integer roundTo, BigDecimal minPrice, BigDecimal maxPrice) {

    public static final String MODE_PERCENT = "percent";
    public static final String MODE_DELTA = "delta";
    public static final String MODE_SET = "set";

    private static final Set<String> MODES = Set.of(MODE_PERCENT, MODE_DELTA, MODE_SET);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 降价幅度上限：超过这个比例说明多半是手滑，直接拒收而不是把商品改成一分钱 */
    private static final BigDecimal MAX_PERCENT_DOWN = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_PERCENT_UP = BigDecimal.valueOf(500);

    /** 售价列是 numeric(10,2)，上限取能写进去的最大值 */
    public static final BigDecimal PRICE_CEILING = new BigDecimal("99999999.99");

    public static BatchPricePolicy of(String mode, BigDecimal value, Integer roundTo, BigDecimal minPrice) {
        Integer digits = roundTo == null ? 2 : roundTo;
        BigDecimal floor = minPrice == null || minPrice.compareTo(BigDecimal.ZERO) <= 0
                ? new BigDecimal("0.01") : minPrice;
        return new BatchPricePolicy(mode == null ? null : mode.trim().toLowerCase(Locale.ROOT),
                value, digits, floor, PRICE_CEILING);
    }

    /**
     * 校验失败抛业务异常：批量端点拿到的是「参数不合法」而不是把 null 一路带进 SQL
     */
    public BatchPricePolicy requireValid() {
        if (mode == null || !MODES.contains(mode)) {
            throw new BusinessException("改价方式只支持百分比、加减金额、统一价三种");
        }
        if (value == null) {
            throw new BusinessException("请填写调整幅度");
        }
        if (roundTo == null || roundTo < 0 || roundTo > 2) {
            throw new BusinessException("取整位数只能是 0（元）、1（角）或 2（分）");
        }
        if (minPrice == null || minPrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("价格下限不能为负");
        }
        if (MODE_PERCENT.equals(mode)
                && (value.compareTo(MAX_PERCENT_DOWN) < 0 || value.compareTo(MAX_PERCENT_UP) > 0)) {
            throw new BusinessException("百分比调整幅度需介于 -90% 与 500% 之间");
        }
        if ((MODE_DELTA.equals(mode) || MODE_SET.equals(mode)) && value.compareTo(PRICE_CEILING) > 0) {
            throw new BusinessException("金额超出可维护范围");
        }
        return this;
    }

    /**
     * @return 调整后价格；当前价为空时返回 null（无价可算，调用方按失败处理）
     */
    public BigDecimal applyTo(BigDecimal currentPrice) {
        if (currentPrice == null) {
            return null;
        }
        BigDecimal raw = switch (mode) {
            case MODE_PERCENT -> currentPrice.multiply(HUNDRED.add(value)).divide(HUNDRED, 4, RoundingMode.HALF_UP);
            case MODE_DELTA -> currentPrice.add(value);
            default -> value;
        };
        // 先钳上下限再按目标位数取整：反过来取整后可能又越界（比如 0.004 进位成 0.00 低于下限）
        BigDecimal clamped = raw.max(minPrice).min(maxPrice == null ? PRICE_CEILING : maxPrice);
        return clamped.setScale(roundTo, RoundingMode.HALF_UP);
    }

    /** 是否真的需要改价：同价商品不占一次 UPDATE，也不在明细里刷成「成功」 */
    public boolean changes(BigDecimal currentPrice) {
        BigDecimal next = applyTo(currentPrice);
        return next != null && currentPrice.compareTo(next) != 0;
    }

    /**
     * 审计摘要文案：只描述规则，不带商品明细也不带任何口令类字段
     */
    public String describe() {
        String body = switch (mode) {
            case MODE_PERCENT -> "百分比 " + value.toPlainString() + "%";
            case MODE_DELTA -> "增减 ¥" + value.toPlainString();
            default -> "统一价 ¥" + value.toPlainString();
        };
        return body + "，取整到" + switch (roundTo) {
            case 0 -> "元";
            case 1 -> "角";
            default -> "分";
        } + "，下限 ¥" + minPrice.toPlainString();
    }

    /**
     * 划线价跟随规则：改价后划线价必须仍然高于售价，否则留着就是「假折扣」（与 A19 同一口径）
     */
    public BigDecimal nextOriginalPrice(BigDecimal originalPrice, BigDecimal newPrice) {
        if (originalPrice == null || newPrice == null) {
            return null;
        }
        return originalPrice.compareTo(newPrice) > 0 ? originalPrice : null;
    }
}
