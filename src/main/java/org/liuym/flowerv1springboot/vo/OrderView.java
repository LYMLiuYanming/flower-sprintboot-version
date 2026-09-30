package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.DeliveryPolicy;
import org.liuym.flowerv1springboot.common.OrderFlowPolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderItem;
import org.liuym.flowerv1springboot.model.OrderRefund;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.OrderTrace;
import org.liuym.flowerv1springboot.model.RefundStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 订单视图：状态以 code + label 双字段输出，并给出前端按钮可见性与下一步动作文案，
 * 避免页面各自硬编码状态集合，也避免用户在页面上算第二套账。
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
        /** 支付方式展示名（C04）：字典在服务端，页面不再维护第二套 code→中文映射 */
        String payMethodName,
        LocalDateTime payTime,
        LocalDateTime shipTime,
        LocalDateTime deliverTime,
        LocalDateTime finishTime,
        String expressCompany,
        String expressNo,
        String cancelReason,
        Integer pointsEarned,
        Integer pointsUsed,
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
        /** 支付截止时刻与剩余秒数（C08）：只有待付款订单有值，页面倒计时与关单任务同源 */
        LocalDateTime payDeadlineAt,
        Long paySecondsLeft,
        /** 倒计时提示：进入最后 5 分钟转为醒目文案，阈值来自 OrderFlowPolicy */
        String payWarnText,
        /** 提醒阈值秒数（C08）：页面实时走秒时要按它切换样式，不能再自己写一个 300 */
        Integer payWarnSeconds,
        /** 退款进度（C19/C20）：未申请过时为 null */
        RefundView refund,
        Boolean canApplyRefund,
        /** 在途申请可由用户自己撤掉（C19），撤掉后订单继续履约 */
        Boolean canRevokeRefund,
        /** 不能申请退款时的原因（C10），可申请时为 null */
        String refundHint,
        /** 引导评价（C26）：null 表示不提示；入口固定指向详情页评价区 */
        String reviewGuide,
        String reviewGuideTarget,
        /** 配送方式码与展示名：历史订单未选时为 null，页面按"标准配送"降级 */
        String deliveryMethod,
        String deliveryMethodName,
        BigDecimal deliveryWeight,
        String deliverySlot,
        /** 预约时段窗口（C15）：15:00–16:00，不把裸 ISO 串摊给用户 */
        String deliverySlotWindow,
        LocalDateTime expectedArriveAt,
        /** 送达偏好码/展示名/图标（C16）：图标取自 DeliveryPolicy 字典 */
        String deliveryPreference,
        String deliveryPreferenceName,
        String deliveryPreferenceIcon,
        String contactPreference,
        String contactPreferenceName,
        String contactPreferenceIcon,
        ShippingViews.CardView card,
        List<ShippingViews.TraceView> traces,
        List<Item> items,
        /** 详情页的取消原因候选（C06）：随详情一次下发，页面不必再打一次字典接口 */
        List<OrderFlowPolicy.Reason> cancelReasons,
        List<OrderFlowPolicy.Reason> refundReasons) {

    /** 建单时未配置支付超时的兜底口径，与 application.properties 的默认值一致 */
    public static final int DEFAULT_PAY_TIMEOUT_MINUTES = 30;

    /** 每束花一句话备注与包装标记（B01/B02 的订单侧快照） */
    public record Item(
            UUID id,
            UUID productId,
            ProductView product,
            String productName,
            String productImage,
            BigDecimal price,
            Integer quantity,
            BigDecimal subtotal,
            Boolean reviewed,
            String itemNote,
            Boolean giftWrap) {
    }

    /**
     * 退款进度（C19）：申请、受理、打款三段时间与结论一次给全，
     * hint 直接写下一步动作，用户不必猜门店在做什么。
     */
    public record RefundView(
            String status,
            String statusLabel,
            BigDecimal amount,
            String reason,
            String detail,
            LocalDateTime requestedAt,
            LocalDateTime acceptedAt,
            LocalDateTime settledAt,
            String reviewer,
            String reviewNote,
            String hint) {

        static RefundView of(Order order, OrderRefund record) {
            RefundStatus status = record != null ? record.getStatus() : order.getRefundStatus();
            if (status == null) {
                return null;
            }
            return new RefundView(
                    status.getCode(),
                    status.getLabel(),
                    record != null ? record.getAmount() : order.getRefundAmount(),
                    record != null ? record.getReason() : order.getRefundReason(),
                    record != null ? record.getDetail() : null,
                    record != null ? record.getCreatedAt() : order.getRefundRequestedAt(),
                    record == null ? null : record.getAcceptedAt(),
                    record != null ? record.getSettledAt() : order.getRefundedAt(),
                    record == null ? null : record.getReviewer(),
                    record == null ? null : record.getReviewNote(),
                    hint(status));
        }

        private static String hint(RefundStatus status) {
            return switch (status) {
                case PENDING -> "申请已提交，门店一般 1 个工作日内受理";
                case REVIEWING -> "门店审核中，通过后原路退回，到账约 1-3 个工作日";
                case REFUNDED -> "退款已到账，本单占用的库存、积分与优惠券均已回退";
                case REJECTED -> "申请已被驳回，可与门店确认后重新提交";
                case REVOKED -> "申请已撤销，订单继续按原计划配送";
            };
        }
    }

    public static OrderView of(Order order, Set<UUID> reviewedItemIds, boolean withUser) {
        return of(order, reviewedItemIds, withUser, List.of(), DEFAULT_PAY_TIMEOUT_MINUTES, null);
    }

    public static OrderView of(Order order, Set<UUID> reviewedItemIds, boolean withUser, List<OrderTrace> traces) {
        return of(order, reviewedItemIds, withUser, traces, DEFAULT_PAY_TIMEOUT_MINUTES, null);
    }

    /**
     * 详情/列表统一出口。支付超时由服务层把配置值传进来，倒计时才能与关单任务同口径；
     * refundRecord 只在详情页传（列表不给退款单原文，避免逐单多查一次表）
     */
    public static OrderView of(Order order, Set<UUID> reviewedItemIds, boolean withUser, List<OrderTrace> traces,
                               int payTimeoutMinutes, OrderRefund refundRecord) {
        Set<UUID> reviewed = reviewedItemIds == null ? Set.of() : reviewedItemIds;
        List<Item> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream()
                        .map(i -> toItem(i, reviewed.contains(i.getId())))
                        .toList();

        OrderStatus status = order.getStatus() == null ? OrderStatus.PENDING : order.getStatus();
        long unreviewed = items.stream().filter(i -> !Boolean.TRUE.equals(i.reviewed())).count();
        boolean hasUnreviewed = unreviewed > 0;
        ShippingPolicy.Method method = ShippingPolicy.find(order.getDeliveryMethod()).orElse(null);
        LocalDateTime now = LocalDateTime.now();

        LocalDateTime payDeadline = order.getCreatedAt() == null ? null
                : order.getCreatedAt().plusMinutes(Math.max(payTimeoutMinutes, 1));
        Long secondsLeft = OrderFlowPolicy.paySecondsLeft(status, payDeadline, now);
        String warn = secondsLeft == null ? null : (secondsLeft <= OrderFlowPolicy.PAY_WARN_SECONDS
                ? "支付即将超时，仅剩 " + OrderFlowPolicy.payCountdownText(secondsLeft)
                  + "，超时后订单自动关闭并回补库存"
                : null);

        RefundView refund = RefundView.of(order, refundRecord);
        boolean canApplyRefund = status.isRefundable()
                && (order.getRefundStatus() == null || !order.getRefundStatus().isOpen());
        String refundHint = refund != null ? null : (canApplyRefund ? null : status.refundDenial());

        // 引导评价的锚点：优先签收时间，历史单没有签收时间就退回完成时间
        LocalDateTime anchor = order.getDeliverTime() != null ? order.getDeliverTime() : order.getFinishTime();
        String reviewGuide = (status == OrderStatus.DELIVERED || status == OrderStatus.COMPLETED) && hasUnreviewed
                ? OrderFlowPolicy.reviewGuide(anchor, now, OrderFlowPolicy.REVIEW_GUIDE_DAYS, (int) unreviewed)
                : null;

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
                PayMethodView.name(order.getPayMethod()),
                order.getPayTime(),
                order.getShipTime(),
                order.getDeliverTime(),
                order.getFinishTime(),
                order.getExpressCompany(),
                order.getExpressNo(),
                order.getCancelReason(),
                order.getPointsEarned(),
                order.getPointsUsed(),
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
                status == OrderStatus.PENDING ? payDeadline : null,
                secondsLeft,
                warn,
                OrderFlowPolicy.PAY_WARN_SECONDS,
                refund,
                canApplyRefund,
                order.getRefundStatus() != null && order.getRefundStatus().isRevokable(),
                refundHint,
                reviewGuide,
                reviewGuide == null ? null : OrderFlowPolicy.REVIEW_TARGET,
                order.getDeliveryMethod(),
                method == null ? order.getDeliveryMethod() : method.name(),
                order.getDeliveryWeight(),
                order.getDeliverySlot(),
                slotWindow(order.getDeliverySlot()),
                order.getExpectedArriveAt(),
                order.getDeliveryPreference(),
                order.getDeliveryPreference() == null ? null : DeliveryPolicy.placementName(order.getDeliveryPreference()),
                order.getDeliveryPreference() == null ? null : DeliveryPolicy.placement(order.getDeliveryPreference()).icon(),
                order.getContactPreference(),
                order.getContactPreference() == null ? null : DeliveryPolicy.contactName(order.getContactPreference()),
                order.getContactPreference() == null ? null : DeliveryPolicy.contact(order.getContactPreference()).icon(),
                ShippingViews.CardView.of(order.getCardStyle(), order.getCardRecipient(),
                        order.getCardSignature(), order.getCardMessage()),
                ShippingViews.TraceView.from(traces),
                items,
                OrderFlowPolicy.CANCEL_REASONS,
                OrderFlowPolicy.REFUND_REASONS);
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
                wasReviewed,
                i.getItemNote(),
                Boolean.TRUE.equals(i.getGiftWrap()));
    }

    /** 预约时段窗口（C15）：库里存整点 ISO 串，展示成一小时窗口 */
    private static String slotWindow(String slot) {
        if (slot == null || slot.isBlank()) {
            return null;
        }
        try {
            return ShippingPolicy.slotHourText(LocalDateTime.parse(slot.trim()).getHour());
        } catch (RuntimeException e) {
            // 历史脏数据原样回显，不能因为一个时段串把整页打挂
            return slot;
        }
    }

    /**
     * 支付方式字典（C04）：与下单入参的 payMethod 校验集合一致；
     * 未支付或历史单没落支付方式时回 null，页面显示占位符
     */
    public record PayMethodView(String code, String name) {

        public static String name(String code) {
            if (code == null || code.isBlank()) {
                return null;
            }
            return switch (code.trim().toLowerCase()) {
                case "wechat" -> "微信支付";
                case "alipay" -> "支付宝";
                case "balance" -> "余额支付";
                case "offline" -> "门店线下支付";
                case "cod" -> "货到付款";
                default -> code;
            };
        }
    }
}
