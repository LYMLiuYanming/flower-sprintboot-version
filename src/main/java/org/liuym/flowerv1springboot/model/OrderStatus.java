package org.liuym.flowerv1springboot.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 订单状态机：待付款 → 已付款 →（处理中）→ 已发货 → 已签收 → 已完成；
 * 已付款/处理中可整单取消（回补库存），已签收/已完成可退款。
 * 非列中的跃迁一律拒绝，避免 completed→cancelled 这类非法改状态。
 */
public enum OrderStatus {

    PENDING("pending", "待付款"),
    PAID("paid", "已付款"),
    PROCESSING("processing", "处理中"),
    SHIPPED("shipped", "已发货"),
    DELIVERED("delivered", "已签收"),
    COMPLETED("completed", "已完成"),
    CANCELLED("cancelled", "已取消"),
    REFUNDED("refunded", "已退款");

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(PAID, CANCELLED),
            // 鲜花门店规模小，"处理中"是可选环节，已付款可直接发货
            PAID, EnumSet.of(PROCESSING, SHIPPED, CANCELLED, REFUNDED),
            PROCESSING, EnumSet.of(SHIPPED, CANCELLED, REFUNDED),
            SHIPPED, EnumSet.of(DELIVERED),
            DELIVERED, EnumSet.of(COMPLETED, REFUNDED),
            // 自动确认收货（C18）会把单推到已完成，售后窗口不能跟着一起关掉
            COMPLETED, EnumSet.of(REFUNDED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            REFUNDED, EnumSet.noneOf(OrderStatus.class));

    /** 取消/退款时需要把库存与销量回补的状态 */
    /** 已成交口径：计入销量/看板/推荐的订单状态，不含待付款与取消退款 */
    public static final List<OrderStatus> DEAL_STATUSES =
            List.of(PAID, PROCESSING, SHIPPED, DELIVERED, COMPLETED);

    private static final Set<OrderStatus> STOCK_HELD_BEFORE_ROLLBACK = EnumSet.of(PENDING, PAID, PROCESSING);

    /** 可申请退款的状态：已付款与签收/完成后的售后窗口；待付款没有可退金额，已发货需先签收 */
    public static final Set<OrderStatus> REFUNDABLE =
            EnumSet.of(PAID, PROCESSING, DELIVERED, COMPLETED);

    private final String code;
    private final String label;

    OrderStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public boolean canTransitTo(OrderStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isCancellable() {
        return this == PENDING;
    }

    public boolean releasesStockOnLeave() {
        return STOCK_HELD_BEFORE_ROLLBACK.contains(this);
    }

    public boolean isPaidOrLater() {
        return this != PENDING && this != CANCELLED && this != REFUNDED;
    }

    public boolean isRefundable() {
        return REFUNDABLE.contains(this);
    }

    /**
     * 非法跃迁的可执行原因（C10）。只回「不允许变更状态」用户不知道该干什么，
     * 这里按当前状态给出下一步动作，文案与前端提示同源。
     */
    public String transitionDenial(OrderStatus target) {
        if (target == null) {
            return "请指定要变更到的订单状态";
        }
        if (canTransitTo(target)) {
            return null;
        }
        if (this == target) {
            return "订单已经是「" + label + "」，无需重复操作";
        }
        if (this == CANCELLED || this == REFUNDED) {
            return "订单已终结于「" + label + "」，不能再变更为「" + target.label
                    + "」；如需重新下单请到商品页再买一次";
        }
        if (target == CANCELLED) {
            return switch (this) {
                case SHIPPED -> "花礼已在路上，无法再取消订单；签收后可在详情页申请退款";
                case PAID, PROCESSING -> "订单已进入「" + label + "」，取消需由门店确认；你可以直接申请退款";
                default -> "「" + label + "」的订单不能取消，如需退款请申请退款";
            };
        }
        if (target == REFUNDED) {
            return switch (this) {
                case PENDING -> "本单尚未支付，没有可退金额，取消订单即可";
                case SHIPPED -> "花礼正在配送中，请先确认收货再申请退款";
                case CANCELLED -> "订单已取消，款项与库存都已回退，无需退款";
                default -> "「" + label + "」的订单暂不支持退款，请联系门店";
            };
        }
        if (target == PAID) {
            return "订单状态已是「" + label + "」，不能退回「已付款」重复入账";
        }
        if (target == SHIPPED && this == PENDING) {
            return "订单还未支付，不能发货";
        }
        if (target == DELIVERED && this == COMPLETED) {
            return "订单已完成，签收记录不可回退";
        }
        return "不允许从「" + label + "」变更为「" + target.label + "」";
    }

    /** 退款申请被拒时的原因（C10/C19） */
    public String refundDenial() {
        return switch (this) {
            case PENDING -> "订单还在「待付款」，没有付款也就没有可退金额，直接取消订单即可";
            case SHIPPED -> "花礼正在配送中，签收后再申请退款更容易通过";
            case CANCELLED -> "订单已取消并回补库存，款项无需退回";
            case REFUNDED -> "订单已退款完成，无需再次申请";
            default -> "「" + label + "」的订单暂不支持在线退款，请联系门店客服";
        };
    }

    public static String labelOf(OrderStatus status) {
        return status == null ? "未知" : status.getLabel();
    }

    public static OrderStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equalsIgnoreCase(code) || s.name().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的订单状态：" + code));
    }
}
