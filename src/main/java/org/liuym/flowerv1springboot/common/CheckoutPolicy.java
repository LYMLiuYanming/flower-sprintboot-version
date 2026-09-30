package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 结算与购物车的纯计价策略（B01/B03/B10/B12/B19-B23）。
 *
 * <p>所有金额都在此收敛为一个口径：结算页试算、下单建单、购物车合计三处必须拿到同一个数字，
 * 否则「页面显示 ¥199，提交变 ¥211」这类投诉无法定位。纯静态、不依赖 Spring，便于单测。
 */
public final class CheckoutPolicy {

    private CheckoutPolicy() {
    }

    /** 礼品包装：整单固定加价，同单多束只收一次（B01） */
    public static final BigDecimal GIFT_WRAP_FEE = new BigDecimal("12.00");

    /** 单个商品在购物车/订单一行中的数量上限，与库存取小（B03） */
    public static final int MAX_QUANTITY_PER_ITEM = 99;

    /** 每束一句话备注的字数上限（B02） */
    public static final int MAX_LINE_NOTE = 100;

    /** 订单实付金额下限（B23）：券 + 积分把金额抵到 0 时仍按此值收款 */
    public static final BigDecimal MIN_PAY_AMOUNT = new BigDecimal("0.01");

    /** 贺卡留言字数上限与超限文案（B16），页面计数与提交禁用都用这一句 */
    public static final int CARD_MESSAGE_LIMIT = GreetingCardPolicy.MAX_MESSAGE;
    public static final String CARD_MESSAGE_OVER = "贺卡留言已达 " + GreetingCardPolicy.MAX_MESSAGE
            + " 字上限，请删减后再提交";

    /** 积分抵扣口径提示（B10/B21），前端滑杆说明与后端截断原因共用 */
    public static final String POINTS_CAP_TEXT = "积分最多抵扣折后金额的 20%";

    /** 库存钳制提示（B03） */
    public static String stockClampedText(String productName, int max) {
        return "「" + productName + "」库存仅剩 " + max + " 件，已按 " + max + " 件计算";
    }

    /** 金额下限提示（B23） */
    public static String minPayText() {
        return "优惠券与积分抵扣后，订单实付金额不低于 ¥" + MIN_PAY_AMOUNT.toPlainString();
    }

    /** 备注常用语字典（B12）：文案会原样插入备注框，因此只放配送相关的短句 */
    public record Preset(String code, String label, String text) {
    }

    public static final List<Preset> REMARK_PRESETS = List.of(
            new Preset("door", "放门口", "请放门口，轻放勿按门铃，送达后拍照发我。"),
            new Preset("quiet", "勿打电话", "收花人上班中，请勿打电话，到了发短信即可。"),
            new Preset("front", "前台代收", "请先交前台/门卫代收，确认收花人在再电话联系。"),
            new Preset("fresh", "优先新鲜", "请挑选当日到港的最新花材，额外多留一支备用花。"),
            new Preset("time", "务必准时", "此单为纪念日消息，请务必在约定时段内送达。"),
            new Preset("card", "卡片手写", "贺卡请花艺师手写，勿用打印贴纸。"));

    /** 常用语插入：同一条不重复插，避免用户连点把备注填满（B12） */
    public static String appendPreset(String remark, String text) {
        String current = remark == null ? "" : remark.trim();
        if (text == null || text.isBlank() || current.contains(text)) {
            return current;
        }
        String merged = current.isEmpty() ? text : current + " " + text;
        return merged.length() > 500 ? merged.substring(0, 500) : merged;
    }

    /** 行备注清洗（B02）：去首尾空白、压平换行，超长截断而非报错，避免整单提交失败 */
    public static String cleanNote(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > MAX_LINE_NOTE ? text.substring(0, MAX_LINE_NOTE) : text;
    }

    /** 数量钳制结果（B03） */
    public record QuantityClamp(int quantity, int maxAllowed, boolean clamped) {
    }

    /**
     * 把请求数量钳到「库存」与单行上限的较小值：库存为 0 时返回 0，调用方按失效行处理。
     * 直接报错会让用户以为操作失败，钳制 + 回传真实可加数量更符合预期。
     */
    public static QuantityClamp clampQuantity(int requested, Integer stock) {
        int max = stock == null ? 0 : Math.max(stock, 0);
        max = Math.min(max, MAX_QUANTITY_PER_ITEM);
        int want = Math.max(requested, 1);
        if (max <= 0) {
            return new QuantityClamp(0, 0, false);
        }
        int quantity = Math.min(want, max);
        return new QuantityClamp(quantity, max, quantity != want);
    }

    /** 积分钳制结果（B10/B21）：truncated 为真表示用户想要更多但被上限截断 */
    public record PointsClamp(BigDecimal deduction, int points, BigDecimal cap, boolean truncated) {
    }

    /** 积分抵扣上限（B10 滑杆右端）：折后金额 × 20%，折后为负时按 0 计 */
    public static BigDecimal pointsCap(BigDecimal payableBeforePoints) {
        BigDecimal base = payableBeforePoints == null ? BigDecimal.ZERO : payableBeforePoints.max(BigDecimal.ZERO);
        return base.multiply(PointsPolicy.MAX_DEDUCTION_RATIO).setScale(2, RoundingMode.DOWN);
    }

    /** 积分抵扣三重收敛：用户请求 ≤ 折后金额 20% ≤ 持有积分可抵额。
     * 超出部分自动截断而不是报错，因为滑杆值可能已被并发下单/退款改变。
     */
    public static PointsClamp clampPoints(BigDecimal requested, BigDecimal payableBeforePoints, Integer ownedPoints) {
        BigDecimal cap = pointsCap(payableBeforePoints);
        BigDecimal owned = PointsPolicy.yuanFor(ownedPoints == null ? 0 : ownedPoints);
        BigDecimal want = requested == null ? BigDecimal.ZERO : requested.setScale(2, RoundingMode.HALF_UP);
        if (want.compareTo(BigDecimal.ZERO) <= 0) {
            return new PointsClamp(BigDecimal.ZERO.setScale(2), 0, cap, false);
        }
        BigDecimal deduction = want.min(cap).min(owned).setScale(2, RoundingMode.DOWN);
        boolean truncated = deduction.compareTo(want) < 0;
        return new PointsClamp(deduction, PointsPolicy.pointsFor(deduction), cap, truncated);
    }

    /**
     * 计价回执：合计之外把三项优惠分别摊开，页面直接渲染，
     * 不会出现「明细相加不等于总折扣」的两套账。
     * floored/payFloorText 承载 B23 的 ¥0.01 兜底与其提示文案，前端只负责显示
     */
    public record Amounts(
            BigDecimal goodsAmount,
            BigDecimal giftWrapFee,
            BigDecimal freight,
            BigDecimal vipDiscount,
            BigDecimal couponDiscount,
            BigDecimal pointsDeduction,
            BigDecimal discountTotal,
            BigDecimal payAmount,
            boolean floored,
            String payFloorText) {

        public BigDecimal payableBeforeFloor() {
            return goodsAmount.add(giftWrapFee).add(freight).subtract(discountTotal);
        }
    }

    /**
     * 结算计价唯一出口：实付 = 商品 + 礼品包装 + 运费 − (会员折扣 + 券 + 积分)，再兜底到 ¥0.01。
     * 包装费与运费并入实付但不参与券与积分的抵扣基数，避免「券抵包装费」。
     */
    public static Amounts price(BigDecimal goods, BigDecimal giftWrapFee, BigDecimal freight,
                                BigDecimal vipDiscount, BigDecimal couponDiscount, BigDecimal pointsDeduction) {
        BigDecimal goodsAmount = money(goods);
        BigDecimal wrap = money(giftWrapFee);
        BigDecimal ship = money(freight);
        BigDecimal vip = money(vipDiscount);
        BigDecimal coupon = money(couponDiscount);
        BigDecimal points = money(pointsDeduction);
        BigDecimal discount = vip.add(coupon).add(points);
        // 折扣不得把货款抵成负数：券与积分都按货款上限收敛，包装与运费始终要付
        BigDecimal payableGoods = goodsAmount.subtract(discount);
        if (payableGoods.compareTo(BigDecimal.ZERO) < 0) {
            // 按比例回收超额部分，保证明细相加仍等于 discountTotal，页面不会显示出负货款
            BigDecimal ratio = discount.compareTo(BigDecimal.ZERO) > 0
                    ? goodsAmount.divide(discount, 6, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            vip = money(vip.multiply(ratio));
            coupon = money(coupon.multiply(ratio));
            points = money(goodsAmount.subtract(vip).subtract(coupon).max(BigDecimal.ZERO));
            discount = money(vip.add(coupon).add(points));
            payableGoods = BigDecimal.ZERO;
        }
        BigDecimal raw = payableGoods.add(wrap).add(ship);
        boolean floored = raw.compareTo(MIN_PAY_AMOUNT) < 0;
        return new Amounts(goodsAmount, wrap, ship, vip, coupon, points, money(discount),
                floored ? MIN_PAY_AMOUNT : money(raw), floored, floored ? minPayText() : null);
    }

    /** 礼品包装费（B01）：本单有任一行勾选包装就收一次整单费用 */
    public static BigDecimal giftWrapFee(long wrappedKinds) {
        return wrappedKinds > 0 ? GIFT_WRAP_FEE : BigDecimal.ZERO.setScale(2);
    }

    /** 逐行下单校验问题（B19） */
    public record LineIssue(String product, String reason) {
    }

    /**
     * 错误汇总：一次提交把所有不合法行都列出来，而不是让用户改一行报一行。
     * 上限 4 条，再多用户也读不完，剩余条数用「等 N 项」收尾。
     */
    public static String lineErrorText(List<LineIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (LineIssue issue : issues.stream().limit(4).toList()) {
            parts.add("「" + issue.product() + "」" + issue.reason());
        }
        if (issues.size() > parts.size()) {
            parts.add("等 " + issues.size() + " 项");
        }
        return "以下商品暂不可购买：" + String.join("；", parts);
    }

    /**
     * 跨分类券拒绝（B20）：分类专享券在本单成交行里一件都没命中时给出专属原因，
     * 否则用户只会看到含糊的「未达门槛」
     */
    public static Optional<String> couponMismatch(String couponName, boolean categoryScope, long matchedLines) {
        if (categoryScope && matchedLines <= 0) {
            return Optional.of("「" + couponName + "」仅限指定分类的花礼使用，本单没有符合条件的商品");
        }
        return Optional.empty();
    }

    /** 最快可约时段候选（B13） */
    public record SlotCandidate(LocalDate date, int hour, boolean bookable) {
    }

    public static Optional<SlotCandidate> firstBookable(List<SlotCandidate> slots) {
        if (slots == null) {
            return Optional.empty();
        }
        return slots.stream().filter(SlotCandidate::bookable).findFirst();
    }

    /** 天气改约建议（B15） */
    public record Advisory(String level, String text, String suggestDeliveryMethod, String suggestSlotAfter) {
    }

    /**
     * 高风险天气 → 改约建议。只在 risk 级或「雨/雪 + 预约单」时给建议：
     * 正常天气弹提示只会让用户以为下单出错。
     */
    public static Optional<Advisory> weatherAdvisory(String level, String weather, Integer temperature,
                                                     String deliveryMethod, LocalDateTime slot) {
        boolean rainy = FlowerCarePolicy.isRainy(weather);
        boolean hot = temperature != null && temperature >= FlowerCarePolicy.HOT_TEMP;
        boolean cold = temperature != null && temperature <= FlowerCarePolicy.COLD_TEMP;
        boolean risky = FlowerCarePolicy.LEVEL_RISK.equals(level) || (rainy && slot != null);
        if (!risky) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        if (hot) {
            text.append("送达城市当前 ").append(temperature).append("℃ 高温，鲜花离水易失水，");
        } else if (cold) {
            text.append("送达城市当前 ").append(temperature).append("℃ 低温，花瓣遇冷会发暗，");
        } else if (rainy) {
            text.append("送达城市有").append(weather == null ? "降雨" : weather).append("，配送可能迟到 10-20 分钟，");
        } else {
            text.append("送达城市天气较差，");
        }
        if (slot != null) {
            text.append("建议把 ").append(ShippingPolicy.slotDateText(slot)).append(' ')
                    .append(ShippingPolicy.slotHourText(slot.getHour()))
                    .append(" 的预约改约到次日同时段或改用即时配送");
        } else {
            text.append("建议选择预约定时达，把送达时间挪到天气好转的时段");
        }
        String suggestMethod = hot ? ShippingPolicy.AIR_COLD.code()
                : (slot != null ? ShippingPolicy.SAME_CITY.code() : ShippingPolicy.SCHEDULED.code());
        // 改约锚点给「次日同小时」，前端按运力日历自行校验是否仍可约
        String suggestSlotAfter = slot == null ? null
                : slot.plusDays(1).withSecond(0).withNano(0).toString();
        return Optional.of(new Advisory(level, text.toString(), suggestMethod, suggestSlotAfter));
    }

    /**
     * 下单失败字段定位（B18）：把出错字段与逐项原因带在异常里，控制器展平成 field/errors，
     * 页面据此标红并滚动到该字段，用户不必在整页表单里找哪一项没填
     */
    public static class CheckoutValidation extends BusinessException {
        private final String field;
        private final List<String> issues;

        public CheckoutValidation(String field, String message) {
            this(field, message, List.of());
        }

        public CheckoutValidation(String field, String message, List<String> issues) {
            super(message);
            this.field = field;
            this.issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public CheckoutValidation(String field, int code, String message, List<String> issues) {
            super(code, message);
            this.field = field;
            this.issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public String field() {
            return field;
        }

        public List<String> issues() {
            return issues;
        }
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
