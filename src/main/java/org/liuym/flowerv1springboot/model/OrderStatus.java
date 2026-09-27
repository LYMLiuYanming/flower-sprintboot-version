package org.liuym.flowerv1springboot.model;

import java.util.Arrays;
import java.util.EnumSet;
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
            COMPLETED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            REFUNDED, EnumSet.noneOf(OrderStatus.class));

    /** 取消/退款时需要把库存与销量回补的状态 */
    private static final Set<OrderStatus> STOCK_HELD_BEFORE_ROLLBACK = EnumSet.of(PENDING, PAID, PROCESSING);

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

    public static OrderStatus fromCode(String code) {
        return Arrays.stream(values())
                .filter(s -> s.code.equalsIgnoreCase(code) || s.name().equalsIgnoreCase(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知的订单状态：" + code));
    }
}
