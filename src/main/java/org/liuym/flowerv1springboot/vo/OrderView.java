package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderItem;
import org.liuym.flowerv1springboot.model.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 订单视图：状态以 code + label 双字段输出，并给出前端按钮可见性判断，避免页面各自硬编码状态集合
 */
public record OrderView(
        UUID id,
        String orderNo,
        BigDecimal totalAmount,
        BigDecimal discountAmount,
        BigDecimal couponAmount,
        BigDecimal freight,
        BigDecimal payAmount,
        String status,
        String statusLabel,
        String payMethod,
        LocalDateTime payTime,
        LocalDateTime shipTime,
        LocalDateTime finishTime,
        String expressCompany,
        String expressNo,
        String cancelReason,
        Integer pointsEarned,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String remark,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String userName,
        String userPhone,
        Boolean canPay,
        Boolean canCancel,
        Boolean canConfirmReceipt,
        Boolean canReview,
        List<Item> items) {

    public record Item(
            UUID id,
            UUID productId,
            ProductView product,
            String productName,
            String productImage,
            BigDecimal price,
            Integer quantity,
            BigDecimal subtotal,
            Boolean reviewed) {
    }

    public static OrderView of(Order order, Set<UUID> reviewedItemIds, boolean withUser) {
        Set<UUID> reviewed = reviewedItemIds == null ? Set.of() : reviewedItemIds;
        List<Item> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream()
                        .map(i -> toItem(i, reviewed.contains(i.getId())))
                        .toList();

        OrderStatus status = order.getStatus() == null ? OrderStatus.PENDING : order.getStatus();
        boolean hasUnreviewed = items.stream().anyMatch(i -> !Boolean.TRUE.equals(i.reviewed()));

        return new OrderView(
                order.getId(),
                order.getOrderNo(),
                order.getTotalAmount(),
                order.getDiscountAmount(),
                order.getCouponAmount(),
                order.getFreight(),
                order.getPayAmount(),
                status.getCode(),
                status.getLabel(),
                order.getPayMethod(),
                order.getPayTime(),
                order.getShipTime(),
                order.getFinishTime(),
                order.getExpressCompany(),
                order.getExpressNo(),
                order.getCancelReason(),
                order.getPointsEarned(),
                order.getReceiverName(),
                order.getReceiverPhone(),
                order.getReceiverAddress(),
                order.getRemark(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                withUser && order.getUser() != null ? order.getUser().getFullName() : null,
                withUser && order.getUser() != null ? order.getUser().getPhone() : null,
                status == OrderStatus.PENDING,
                status.isCancellable(),
                status == OrderStatus.SHIPPED,
                (status == OrderStatus.DELIVERED || status == OrderStatus.COMPLETED) && hasUnreviewed,
                items);
    }

    private static Item toItem(OrderItem i, boolean wasReviewed) {
        return new Item(
                i.getId(),
                i.getProduct() == null ? null : i.getProduct().getId(),
                i.getProduct() == null ? null : ProductView.from(i.getProduct()),
                i.getProductName(),
                i.getProductImage(),
                i.getPrice(),
                i.getQuantity(),
                i.getSubtotal(),
                wasReviewed);
    }
}
