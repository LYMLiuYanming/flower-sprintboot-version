package org.liuym.flowerv1springboot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
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
            Boolean clearCart) {
    }

    public record ItemRequest(
            @NotNull(message = "商品不能为空") UUID productId,
            @NotNull @Min(value = 1, message = "数量至少为 1") @Max(value = 999, message = "单次购买数量过大") Integer quantity) {
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
}
