package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.DeliveryPolicy;
import org.liuym.flowerv1springboot.common.GreetingCardPolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.OrderTrace;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 配送试算与轨迹视图：运费/时效由服务端算好后下发，前端只做展示与回填
 */
public final class ShippingViews {

    private ShippingViews() {
    }

    public record MethodView(
            String code,
            String name,
            String icon,
            String note,
            BigDecimal baseFee,
            BigDecimal feePerKg,
            BigDecimal freeOver,
            Integer etaHours,
            Boolean slotAware,
            /** 本单在该方式下的应付运费，0 表示免运费 */
            BigDecimal freight,
            /** 门槛让利前的原价运费，用于展示"已省 ¥xx" */
            BigDecimal originFreight,
            Boolean free,
            LocalDateTime expectedArriveAt,
            /** 时效说明文案，页面直接展示 */
            String etaText) {

        public static MethodView of(ShippingPolicy.Method method, BigDecimal goodsAmount, BigDecimal weight,
                                    LocalDateTime now, LocalDateTime slot) {
            BigDecimal freight = ShippingPolicy.freightOf(method, goodsAmount, weight);
            BigDecimal origin = ShippingPolicy.originalFreight(method, weight);
            LocalDateTime arriveAt = ShippingPolicy.arriveAt(method, now, slot);
            return new MethodView(method.code(), method.name(), method.icon(), method.note(),
                    method.baseFee(), method.feePerKg(), method.freeOver(), method.etaHours(),
                    method.slotAware(), freight, origin, freight.compareTo(BigDecimal.ZERO) <= 0,
                    arriveAt, etaText(method, arriveAt, now));
        }

        /** 预约方式给出绝对时刻，其他方式给出"约 x 小时内"的相对时效 */
        private static String etaText(ShippingPolicy.Method method, LocalDateTime arriveAt, LocalDateTime now) {
            if (arriveAt == null) {
                return method.slotAware() ? "选定送达时段后确认" : "";
            }
            if (method.slotAware()) {
                return "按预约时段送达";
            }
            long hours = Math.max(1, java.time.Duration.between(now, arriveAt).toHours());
            return hours >= 24 ? "约 " + (hours + 23) / 24 + " 天送达" : "约 " + hours + " 小时内送达";
        }
    }

    public record Quote(
            BigDecimal goodsAmount,
            BigDecimal weight,
            /** 结算页当前选中的方式（非法值已回落到默认方式） */
            String selectedMethod,
            BigDecimal freight,
            LocalDateTime expectedArriveAt,
            List<MethodView> options,
            List<GreetingCardPolicy.Style> cardStyles,
            /** 送达偏好字典：门店自提时页面隐藏这两组选项 */
            List<DeliveryPolicy.Placement> placements,
            List<DeliveryPolicy.Contact> contacts,
            /** 预约方式的运力日历；非预约方式为空列表 */
            List<SlotView> slots) {
    }

    /** 可约运力时段：页面按 remaining 置灰，closed 表示已错过备花提前量 */
    public record SlotView(
            java.time.LocalDate date,
            Integer hour,
            String text,
            Integer capacity,
            Integer remaining,
            Boolean closed,
            Boolean full) {

        public static SlotView of(java.time.LocalDate date, int hour, int capacity, int used, boolean closed) {
            int remaining = Math.max(capacity - used, 0);
            return new SlotView(date, hour, "%02d:00–%02d:00".formatted(hour, hour + 1),
                    capacity, remaining, closed, closed || remaining <= 0);
        }
    }

    public record TraceView(            String code,
            String title,
            String description,
            String operator,
            LocalDateTime createdAt) {

        public static TraceView from(OrderTrace t) {
            return new TraceView(t.getCode(), t.getTitle(), t.getDescription(), t.getOperator(), t.getCreatedAt());
        }

        public static List<TraceView> from(List<OrderTrace> list) {
            return list == null ? List.of() : list.stream().map(TraceView::from).toList();
        }
    }

    public record CardView(
            String style,
            String styleName,
            String recipient,
            String signature,
            String message) {

        public static CardView of(String style, String recipient, String signature, String message) {
            if (!GreetingCardPolicy.requested(recipient, message, signature)) {
                return null;
            }
            String code = GreetingCardPolicy.normalizeStyle(style);
            return new CardView(code, GreetingCardPolicy.styleName(code), recipient, signature, message);
        }
    }
}
