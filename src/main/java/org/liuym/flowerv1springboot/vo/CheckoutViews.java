package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.CheckoutPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结算页视图：一次请求把「行、券、积分、配送、时段、天气、策略文案」全部算好下发。
 *
 * <p>页面上任何数字都不自己算，只渲染这里的值，保证试算与最终建单同口径（B08/B11/B21/B23）。
 */
public final class CheckoutViews {

    private CheckoutViews() {
    }

    /** 结算行：价格与库存取商品当前值，issue 非空表示该行不可成交（B19 的服务端同源提示） */
    public record Line(
            UUID cartItemId,
            UUID productId,
            String productName,
            String productImage,
            String unit,
            BigDecimal price,
            Integer quantity,
            BigDecimal subtotal,
            Integer stock,
            Integer maxQuantity,
            Boolean giftWrap,
            String note,
            BigDecimal addedPrice,
            BigDecimal priceDrop,
            String issue) {
    }

    /**
     * 券面板（B09）：discount 为服务端按本单成交行算出的可抵额，null 表示当前不可用；
     * best=true 即「本单最优券」，saving 为比次优券多省的钱
     */
    public record CouponOption(
            UUID id,
            String name,
            String ruleText,
            BigDecimal threshold,
            BigDecimal amount,
            BigDecimal discountRate,
            BigDecimal maxDiscount,
            String scope,
            UUID categoryId,
            LocalDateTime expireAt,
            BigDecimal discount,
            boolean best,
            BigDecimal saving,
            String reason) {
    }

    /** 积分抵扣（B10/B21）：滑杆范围 0..cap，deduction 是服务端收敛后的实际值 */
    public record PointsState(
            Integer ownedPoints,
            /** 持有积分折算成的可抵金额，滑杆不能超过它 */
            BigDecimal availableYuan,
            BigDecimal cap,
            BigDecimal deduction,
            int pointsToUse,
            boolean truncated,
            String text) {
    }

    /** 时段选择（B13）：date 为 yyyy-MM-dd，hour 为整点 */
    public record SlotPick(String date, Integer hour, String text, LocalDateTime slotAt) {
    }

    /** 天气改约建议（B15） */
    public record Advisory(String level, String text, String suggestMethod, SlotPick suggestSlot) {
    }

    /** 贺卡字数规则（B16）：上限与超限文案都来自服务端，页面不再硬编码 60 */
    public record CardRules(int messageLimit, String overText) {
    }

    public record Summary(
            boolean guest,
            String loginHint,
            List<Line> lines,
            Integer invalidCount,
            BigDecimal priceDropAmount,
            CheckoutPolicy.Amounts amounts,
            BigDecimal goodsAmount,
            BigDecimal giftWrapFee,
            Integer giftWrapKinds,
            BigDecimal weight,
            String deliveryMethod,
            String deliveryMethodName,
            LocalDateTime expectedArriveAt,
            /** 配送方式对比表（B11）：同一单六种方式的运费与时效并排 */
            List<ShippingViews.MethodView> shippingOptions,
            List<CouponOption> coupons,
            UUID suggestedCouponId,
            String suggestedCouponText,
            PointsState points,
            SlotPick earliestSlot,
            Advisory advisory,
            List<CheckoutPolicy.Preset> remarkPresets,
            CardRules card,
            /** 服务端文案集合（B19-B23），前端提示必须与此同源 */
            Map<String, String> messages) {
    }

    /** 再次购买（B24）：逐行回执，让用户知道哪些束加回来了、哪些被库存钳制、哪些已下架 */
    public record ReorderReport(
            Integer addedKinds,
            Integer addedUnits,
            List<String> adjusted,
            List<String> skipped,
            String notice,
            CartView cart) {
    }
}
