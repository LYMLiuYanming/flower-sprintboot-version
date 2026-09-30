package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 营销叠加策略：满减阶梯取最优档（E01）、满减与券并存的优先级（E14）、
 * 会员等级折扣规则（E17）、券与积分的组合建议（E09）。
 *
 * <p>纯函数、无副作用：条款字符串在这里解析成档位，金额一律由服务端算，
 * 页面与下单链路共用同一套口径，避免「结算页显示省 60、下单只减 20」的两套账。
 */
public final class CampaignPolicy {

    /** 阶梯条款的分隔符：档间用逗号，档内门槛与减免用冒号，如 "199:20,399:60" */
    private static final String TIER_SEPARATOR = ",";
    private static final String FIELD_SEPARATOR = ":";

    /** 会员等级折扣字典：等级码 → (折扣率, 升级门槛) */
    public static final String LEVEL_ORDINARY = "ordinary";
    public static final String LEVEL_VIP = "vip";
    public static final String LEVEL_SVIP = "svip";

    /**
     * 一单可叠加的营销减免来源上限：满减 + 券 + 积分三项，超过三项说明条款配置重复
     */
    public static final int MAX_STACK_ITEMS = 3;

    /** E18：VIP 开通/续费价格，12 个月 = 1 年，按积分支付（100 积分 = 1 元，见 PointsPolicy） */
    public static final BigDecimal VIP_ANNUAL_PRICE = new BigDecimal("99.00");
    /** E18：会员权益里的包邮门槛减免（满 199 免运费之外，会员再低 20 元门槛） */
    public static final BigDecimal VIP_FREE_SHIPPING_THRESHOLD_DROP = new BigDecimal("20.00");
    /** E18：转赠/回访券之外，会员每月可多领的券张数，权益说明页展示用 */
    public static final int VIP_EXTRA_COUPONS_PER_MONTH = 2;

    private CampaignPolicy() {
    }

    /** 一档满减：满 threshold 减 reduce */
    public record Tier(BigDecimal threshold, BigDecimal reduce) {
    }

    /**
     * 一条减免来源：名称 + 金额 + 是否与其他减免叠加。
     * kind 取 reduction（满减）/ coupon（券）/ points（积分）/ member（会员折扣）
     */
    public record Benefit(String kind, String name, BigDecimal amount, boolean stackable, String note) {
    }

    /**
     * 最终优惠方案：命中的减免项清单 + 合计减免 + 人话说明
     */
    public record Plan(List<Benefit> benefits, BigDecimal totalSaved, String summary) {
    }

    /**
     * 解析阶梯条款。非法档（金额非正、门槛为负、格式错）直接丢弃，
     * 保证后台填错一条也不会让下单链路抛异常。
     */
    public static List<Tier> parseLadder(String ladderRule) {
        if (ladderRule == null || ladderRule.isBlank()) {
            return List.of();
        }
        List<Tier> tiers = new ArrayList<>();
        for (String part : ladderRule.split(TIER_SEPARATOR)) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            int idx = text.indexOf(FIELD_SEPARATOR);
            if (idx <= 0 || idx == text.length() - 1) {
                continue;
            }
            try {
                BigDecimal threshold = new BigDecimal(text.substring(0, idx).trim());
                BigDecimal reduce = new BigDecimal(text.substring(idx + 1).trim());
                if (threshold.signum() >= 0 && reduce.signum() > 0) {
                    tiers.add(new Tier(threshold.setScale(2, RoundingMode.HALF_UP),
                            reduce.setScale(2, RoundingMode.HALF_UP)));
                }
            } catch (NumberFormatException ignored) {
                // 脏条款按无效档处理：宁可少减，也不让金额算错
            }
        }
        tiers.sort(Comparator.comparing(Tier::threshold));
        return List.copyOf(tiers);
    }

    /**
     * E01：在适用金额上取最优档 —— 先比减免额，减免额相同取门槛更低的那档（更容易复用）。
     * 没有任何档够得着时返回 null。
     */
    public static Tier bestTier(String ladderRule, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        Tier best = null;
        for (Tier tier : parseLadder(ladderRule)) {
            if (tier.threshold().compareTo(amount) > 0) {
                continue;
            }
            if (best == null || tier.reduce().compareTo(best.reduce()) > 0
                    || (tier.reduce().compareTo(best.reduce()) == 0
                    && tier.threshold().compareTo(best.threshold()) < 0)) {
                best = tier;
            }
        }
        return best;
    }

    /** 阶梯文案：满199减20 · 满399减60 · 满599减100（E20 的一部分，页面不再手写） */
    public static String ladderText(String ladderRule) {
        List<Tier> tiers = parseLadder(ladderRule);
        if (tiers.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Tier tier : tiers) {
            if (!text.isEmpty()) {
                text.append(" · ");
            }
            text.append("满").append(plain(tier.threshold())).append("减").append(plain(tier.reduce()));
        }
        return text.toString();
    }

    /** 阶梯里最低的可用门槛：券的入门槛以它为准，避免模板上漏填 threshold */
    public static BigDecimal lowestThreshold(String ladderRule) {
        List<Tier> tiers = parseLadder(ladderRule);
        return tiers.isEmpty() ? BigDecimal.ZERO : tiers.get(0).threshold();
    }

    /**
     * 下一档提示：还差多少够到下一档（够着后减免更多）。已经是最优档返回 null。
     */
    public static Tier nextTier(String ladderRule, BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        Tier best = bestTier(ladderRule, amount);
        for (Tier tier : parseLadder(ladderRule)) {
            if (tier.threshold().compareTo(amount) > 0
                    && (best == null || tier.reduce().compareTo(best.reduce()) > 0)) {
                return tier;
            }
        }
        return null;
    }

    /**
     * E14：满减活动与券并存时的优先级。
     *
     * <p>口径：① 两者都命中且金额相同 → 用券（券有有效期与发行量，先消耗存量，满减是长期条款）；
     * ② 满减可叠加（stackable=true）→ 先按门槛大的满减档减，再用券，券的基数按满减后的金额重算；
     * ③ 不可叠加 → 取金额更大的一方，另一条给出一句「为什么没用上」。
     */
    public static Plan combineReductionAndCoupon(Tier reduction, String reductionName, boolean reductionStackable,
                                                 BigDecimal couponDiscount, String couponName) {
        BigDecimal reductionAmount = reduction == null ? BigDecimal.ZERO : reduction.reduce();
        BigDecimal couponAmount = couponDiscount == null ? BigDecimal.ZERO : couponDiscount;
        List<Benefit> benefits = new ArrayList<>();

        if (reductionAmount.signum() > 0 && couponAmount.signum() > 0) {
            if (reductionStackable) {
                benefits.add(new Benefit("reduction", nullSafe(reductionName), reductionAmount, true,
                        "满减自动生效，券在满减后的金额上继续抵扣"));
                benefits.add(new Benefit("coupon", nullSafe(couponName), couponAmount, true, "与本单满减可叠加"));
                return new Plan(List.copyOf(benefits), money(reductionAmount.add(couponAmount)),
                        "满减已减 " + plain(reductionAmount) + "，再用券省 " + plain(couponAmount));
            }
            // 不可叠加：取更省的一条生效，落选的那条只作为解释项（不计入合计）
            boolean useCoupon = couponAmount.compareTo(reductionAmount) >= 0;
            BigDecimal saved = useCoupon ? couponAmount : reductionAmount;
            benefits.add(new Benefit(useCoupon ? "coupon" : "reduction",
                    useCoupon ? nullSafe(couponName) : nullSafe(reductionName), saved, true,
                    useCoupon ? "本单最优：券比满减更省" : "本单最优：满减比券更省，已自动生效"));
            benefits.add(new Benefit(useCoupon ? "reduction" : "coupon",
                    useCoupon ? nullSafe(reductionName) : nullSafe(couponName),
                    useCoupon ? reductionAmount : couponAmount, false,
                    useCoupon ? "与券不可叠加，已选券" : "与满减不可叠加，已自动满减"));
            return new Plan(List.copyOf(benefits), money(saved),
                    "本单可省 " + plain(saved) + (useCoupon ? "（用券，满减不叠加）" : "（自动满减，券留到下单）"));
        }

        if (reductionAmount.signum() > 0) {
            benefits.add(new Benefit("reduction", nullSafe(reductionName), reductionAmount, false,
                    "无需领券，自动减免"));
            return new Plan(List.copyOf(benefits), money(reductionAmount), "已满减 " + plain(reductionAmount));
        }
        if (couponAmount.signum() > 0) {
            benefits.add(new Benefit("coupon", nullSafe(couponName), couponAmount, false, "已使用优惠券"));
            return new Plan(List.copyOf(benefits), money(couponAmount), "已用券省 " + plain(couponAmount));
        }
        return new Plan(List.of(), BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), "本单暂无可用减免");
    }

    /**
     * E09：券与积分的组合建议。积分上限按「券抵扣后的金额」算，
     * 所以存在「券抵得多、积分上限被压低」的情况，把三种组合都算一遍才知道哪种更省。
     */
    public record ComboOption(String name, BigDecimal coupon, BigDecimal points, BigDecimal total, String note, boolean best) {
    }

    /**
     * E09：给出「券+积分 / 仅券 / 仅积分」三种方案的实际可省金额，按总省额倒序。
     */
    public static List<ComboOption> comboOptions(BigDecimal base, BigDecimal couponDiscount, int ownedPoints) {
        BigDecimal safeBase = money(base == null ? BigDecimal.ZERO : base);
        BigDecimal coupon = couponDiscount == null ? BigDecimal.ZERO
                : money(couponDiscount.min(safeBase).max(BigDecimal.ZERO));
        int points = Math.max(ownedPoints, 0);
        BigDecimal pointsYuan = PointsPolicy.yuanFor(points);

        BigDecimal pointsOnly = money(pointsCap(safeBase).min(pointsYuan));
        // 用券之后金额变小，积分上限跟着变小，这正是「先券还是先积分」需要比较的原因
        BigDecimal afterCoupon = safeBase.subtract(coupon).max(BigDecimal.ZERO);
        BigDecimal couponPoints = money(pointsCap(afterCoupon).min(pointsYuan));

        List<ComboOption> options = List.of(
                new ComboOption("券 + 积分", coupon, couponPoints, money(coupon.add(couponPoints)),
                        "先用券，积分在本单最多再抵 " + plain(couponPoints), false),
                new ComboOption("仅用券", coupon, BigDecimal.ZERO, money(coupon),
                        "把积分留给下一单，积分不会作废", false),
                new ComboOption("仅用积分", BigDecimal.ZERO, pointsOnly, money(pointsOnly),
                        "不用券，本单积分最多抵 " + plain(pointsOnly), false));
        BigDecimal best = options.stream().map(ComboOption::total).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        if (best.signum() <= 0) {
            return options;
        }
        List<ComboOption> marked = new ArrayList<>();
        for (ComboOption option : options) {
            marked.add(new ComboOption(option.name(), option.coupon(), option.points(), option.total(),
                    option.note(), option.total().compareTo(best) == 0));
        }
        return List.copyOf(marked);
    }

    /** E09：建议文案，页面直接展示 */
    public static String comboSummary(List<ComboOption> options) {
        if (options == null || options.isEmpty()) {
            return "本单暂无可用减免";
        }
        ComboOption best = options.stream().filter(ComboOption::best).findFirst().orElse(options.get(0));
        if (best.total().signum() <= 0) {
            return "本单暂无可用减免";
        }
        return "最划算：" + best.name() + "，可省 " + plain(best.total()) + "（" + best.note() + "）";
    }

    /** 积分抵扣上限：折后金额的 20%，与 PointsPolicy 保持同一口径 */
    private static BigDecimal pointsCap(BigDecimal amount) {
        return PointsPolicy.maxDeduction(amount.max(BigDecimal.ZERO));
    }

    /** E17：会员等级折扣率。未知等级按非会员处理，不抛异常。 */
    public static BigDecimal memberRate(String memberLevel) {
        if (LEVEL_SVIP.equalsIgnoreCase(memberLevel)) {
            return new BigDecimal("0.8800");
        }
        if (LEVEL_VIP.equalsIgnoreCase(memberLevel)) {
            return new BigDecimal("0.9500");
        }
        return BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP);
    }

    /** E17：会员折扣减免额；普通会员（或未知等级）返回 0 */
    public static BigDecimal memberDiscount(String memberLevel, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = memberRate(memberLevel);
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            return BigDecimal.ZERO;
        }
        return amount.subtract(amount.multiply(rate).setScale(2, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** E17：等级升级门槛（累计已支付金额），返回 null 表示已是最高档 */
    public static BigDecimal upgradeThreshold(String memberLevel) {
        if (LEVEL_VIP.equalsIgnoreCase(memberLevel)) {
            return new BigDecimal("3000.00");
        }
        if (LEVEL_SVIP.equalsIgnoreCase(memberLevel)) {
            return null;
        }
        // 其余（含 null/历史脏值）都按普通账号给升级路径，权益页不该出现「距下一档 —」
        return new BigDecimal("1000.00");
    }

    /** E17：按累计已支付金额判定应处等级（下单支付后的自动升级口径） */
    public static String levelForSpend(BigDecimal totalPaid) {
        BigDecimal paid = totalPaid == null ? BigDecimal.ZERO : totalPaid;
        if (paid.compareTo(new BigDecimal("3000.00")) >= 0) {
            return LEVEL_SVIP;
        }
        if (paid.compareTo(new BigDecimal("1000.00")) >= 0) {
            return LEVEL_VIP;
        }
        return LEVEL_ORDINARY;
    }

    /** E18：开通/续费需要的积分（100 积分 = 1 元） */
    public static int vipCostPoints(BigDecimal years) {
        BigDecimal safe = years == null || years.signum() <= 0 ? BigDecimal.ONE : years;
        return PointsPolicy.pointsFor(VIP_ANNUAL_PRICE.multiply(safe).setScale(2, RoundingMode.HALF_UP));
    }

    /** E18：会员权益清单，权益说明页直接渲染，不再由前端手写文案 */
    public static List<String> memberBenefits(String memberLevel) {
        BigDecimal rate = memberRate(memberLevel);
        if (rate.compareTo(BigDecimal.ONE) >= 0) {
            return List.of("累计支付满 " + plain(upgradeThreshold(LEVEL_ORDINARY)) + " 元自动开通会员",
                    "会员全场花礼 " + discountText(LEVEL_VIP) + " 结算",
                    "会员每月可多领 " + VIP_EXTRA_COUPONS_PER_MONTH + " 张会员专享券");
        }
        Set<String> benefits = new LinkedHashSet<>();
        benefits.add("全场花礼 " + discountText(memberLevel) + " 结算");
        if (LEVEL_SVIP.equalsIgnoreCase(memberLevel)) {
            benefits.add("每月多领 " + (VIP_EXTRA_COUPONS_PER_MONTH * 2) + " 张会员专享券");
            benefits.add("包邮门槛再降 " + plain(VIP_FREE_SHIPPING_THRESHOLD_DROP.multiply(BigDecimal.valueOf(2))) + " 元");
            benefits.add("专属客服优先排单，同日时段优先保留");
        } else if (LEVEL_VIP.equalsIgnoreCase(memberLevel)) {
            benefits.add("每月多领 " + VIP_EXTRA_COUPONS_PER_MONTH + " 张会员专享券");
            benefits.add("包邮门槛降低 " + plain(VIP_FREE_SHIPPING_THRESHOLD_DROP) + " 元");
        }
        return List.copyOf(benefits);
    }

    /** 折扣率转「9.5 折」文案：前端不再自己做乘除 */
    public static String discountText(String memberLevel) {
        BigDecimal tenths = memberRate(memberLevel).multiply(BigDecimal.TEN).setScale(1, RoundingMode.DOWN);
        return trimZero(tenths) + " 折";
    }

    private static String nullSafe(String value) {
        return value == null ? "活动减免" : value;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : trimZero(value.stripTrailingZeros());
    }

    private static String trimZero(BigDecimal value) {
        String text = value.toPlainString();
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** 供 E14 落库校验：把档位列表拼回条款字符串，去掉重复门槛 */
    public static String formatLadder(List<Tier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        tiers.stream().sorted(Comparator.comparing(Tier::threshold)).forEach(tier -> {
            String key = plain(tier.threshold());
            if (seen.add(key)) {
                if (!text.isEmpty()) {
                    text.append(TIER_SEPARATOR);
                }
                text.append(key).append(FIELD_SEPARATOR).append(plain(tier.reduce()));
            }
        });
        return text.toString();
    }
}
