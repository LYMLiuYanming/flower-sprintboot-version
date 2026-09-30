package org.liuym.flowerv1springboot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.liuym.flowerv1springboot.common.GreetingCardPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 下单入参：商品项与金额一律由服务端按数据库价格重算，前端只提交 productId + quantity，
 * 防止篡改价格/金额
 */
public class OrderDtos {

    public record CreateRequest(
            @Valid @NotEmpty(message = "请选择要购买的商品") List<ItemRequest> items,
            String addressId,
            @Size(max = 50, message = "收货人姓名不超过 50 字") String receiverName,
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确") String receiverPhone,
            @Size(max = 255, message = "收货地址不超过 255 字") String receiverAddress,
            @Size(max = 500, message = "备注不超过 500 字") String remark,
            @Pattern(regexp = "^$|^(wechat|alipay|balance|offline|cod)$", message = "不支持的支付方式") String payMethod,
            @DecimalMin(value = "0", message = "积分抵扣金额不能为负") BigDecimal pointsDeduction,
            /** 选用的优惠券（持券 id），服务端会重新校验归属/状态/门槛与抵扣额 */
            UUID userCouponId,
            /** 配送方式码：运费与时效由服务端按 ShippingPolicy 重算，前端传什么都不作数 */
            @Size(max = 20, message = "不支持的配送方式") String deliveryMethod,
            /** 预约定时达所选时段，ISO 本地时间文本 */
            @Size(max = 20, message = "预约时间格式不正确") String deliverySlot,
            /** 放置位置与联系方式码，见 DeliveryPolicy；不在字典内的值一律丢弃而非报错 */
            @Size(max = 20, message = "不支持的送达偏好") String deliveryPreference,
            @Size(max = 20, message = "不支持的联系偏好") String contactPreference,
            @Size(max = 20, message = "贺卡样式不正确") String cardStyle,
            @Size(max = 50, message = "贺卡称谓不超过 50 字") String cardRecipient,
            @Size(max = 50, message = "贺卡署名不超过 50 字") String cardSignature,
            @Size(max = GreetingCardPolicy.MAX_MESSAGE, message = "贺卡留言不超过 " + GreetingCardPolicy.MAX_MESSAGE + " 字") String cardMessage,
            /** 结算页领取的一次性凭证，缺它或它已被用掉都不再建单 */
            @Size(max = 64, message = "下单凭证不正确") String idempotencyKey,
            Boolean clearCart,
            /** 礼品包装（B01）：留空时由购物车行的标记推导，立即购买传 true 直接加包装费 */
            Boolean giftWrap) {
    }

    public record ItemRequest(
            @NotNull(message = "商品不能为空") UUID productId,
            @NotNull @Min(value = 1, message = "数量至少为 1") @Max(value = 999, message = "单次购买数量过大") Integer quantity,
            /** 购物车行 id（B02）：带上它时服务端直接取该行的备注与包装标记，客户端写的备注只作缺行时的兜底 */
            UUID cartItemId,
            @Size(max = 200, message = "每束备注不超过 200 字") String note) {
    }

    /**
     * 结算页试算入参（B08/B09/B10/B11/B13/B15/B21/B23）。
     * items 与 cartItemIds 都为空时按「购物车全部可购行」试算；游客必须显式传 items，
     * 金额一律由服务端按库中价格重算，这里只当成选择器用。
     */
    public record CheckoutRequest(
            @Valid List<ItemRequest> items,
            List<UUID> cartItemIds,
            @Size(max = 20, message = "不支持的配送方式") String deliveryMethod,
            @Size(max = 20, message = "预约时间格式不正确") String deliverySlot,
            /** 留空表示让服务端给出本单最优券（B09）；传 id 则按该券核算 */
            UUID userCouponId,
            @DecimalMin(value = "0", message = "积分抵扣金额不能为负") BigDecimal pointsDeduction,
            Boolean giftWrap,
            @Size(max = 255, message = "收货地址不超过 255 字") String receiverAddress) {
    }

    public record ShipRequest(
            @NotBlank(message = "请填写物流公司") @Size(max = 50) String expressCompany,
            @NotBlank(message = "请填写物流单号") @Size(max = 60) String expressNo) {
    }

    public record StatusRequest(
            @NotBlank(message = "目标状态不能为空") String status,
            @Size(max = 200, message = "原因不超过 200 字") String reason) {
    }

    public record PayRequest(
            @Pattern(regexp = "^$|^(wechat|alipay|balance|offline)$", message = "不支持的支付方式") String payMethod) {
    }

    /**
     * 取消订单（C06/C07）：原因必须二选一以上，"用户主动取消"这种无信息量的兜底不再接受。
     * reasonCode 命中 OrderFlowPolicy.CANCEL_REASONS 时用预设文案，reasonText 是用户的自由补充。
     */
    public record CancelRequest(
            @Size(max = 30, message = "取消原因选择不正确") String reasonCode,
            @Size(max = 120, message = "取消说明不超过 120 字") String reasonText) {

        public boolean isEmpty() {
            return (reasonCode == null || reasonCode.isBlank()) && (reasonText == null || reasonText.isBlank());
        }
    }

    /** 退款申请（C19）：金额不在这里传，服务端按订单实付核定 */
    public record RefundRequest(
            @Size(max = 30, message = "退款原因选择不正确") String reasonCode,
            @Size(max = 500, message = "退款说明不超过 500 字") String reasonText) {

        public boolean isEmpty() {
            return (reasonCode == null || reasonCode.isBlank()) && (reasonText == null || reasonText.isBlank());
        }
    }

    /**
     * 退款审核动作（C19）：accept 受理、reject 驳回、settle 确认打款、revoke 用户撤销。
     * 只有 settle 会触发资源回退，且回退本身由 order.rollback_at 闸门保证只执行一次。
     */
    public record RefundReviewRequest(
            @NotBlank(message = "请指定审核动作")
            @Pattern(regexp = "^(accept|reject|settle|revoke)$", message = "不支持的退款审核动作") String action,
            @Size(max = 200, message = "审核意见不超过 200 字") String note) {
    }

    /**
     * 我的订单列表查询（C01/C02/C03）：控制器把散装参数收拢成一条查询对象，
     * 状态用分组码（toship 这类）而不是让页面拼多个请求
     */
    public record UserOrderQuery(
            String status,
            LocalDateTime from,
            LocalDateTime to,
            String keyword,
            String sort,
            int page,
            int size) {
    }
}
