package org.liuym.flowerv1springboot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 优惠券相关入参：后台模板表单、后台直发、用户领券
 */
public final class CouponDtos {

    private CouponDtos() {
    }

    public record Form(
            @NotBlank(message = "券码必填") @Pattern(regexp = "^[A-Za-z0-9_-]{3,30}$", message = "券码仅支持 3-30 位字母、数字、下划线与短横线") String code,
            @NotBlank(message = "券名称必填") @Size(max = 50, message = "券名称不超过 50 字") String name,
            @NotBlank(message = "券类型必填") @Pattern(regexp = "^(cash|discount)$", message = "券类型仅支持满减或折扣") String type,
            @NotNull(message = "使用门槛必填") @DecimalMin(value = "0", message = "使用门槛不能为负") BigDecimal threshold,
            @DecimalMin(value = "0", message = "满减金额不能为负") BigDecimal amount,
            @DecimalMin(value = "0", message = "折扣率不合法") @DecimalMax(value = "0.99", message = "折扣率需小于 1") BigDecimal discountRate,
            @DecimalMin(value = "0", message = "封顶金额不能为负") BigDecimal maxDiscount,
            @NotNull @Min(value = 0, message = "发行量不能为负") Integer total,
            @NotNull @Min(value = 1, message = "每人限领至少 1 张") Integer perUserLimit,
            LocalDateTime startTime,
            LocalDateTime endTime,
            @NotNull @Min(value = 0, message = "有效天数不能为负") Integer validDays,
            LocalDateTime validEndTime,
            @NotBlank(message = "适用范围必填") @Pattern(regexp = "^(all|category)$", message = "适用范围仅支持全场或指定分类") String scope,
            UUID categoryId,
            @Pattern(regexp = "^(active|inactive)$", message = "状态不合法") String status) {
    }

    /** 后台直发给指定用户 */
    public record IssueRequest(
            @NotNull(message = "请选择用户") UUID userId,
            @NotNull(message = "请选择优惠券") UUID couponId) {
    }

    public record ClaimRequest(
            @NotNull(message = "请选择优惠券") UUID couponId) {
    }

    /** 结算页试算：按当前购物明细给出各券可抵金额 */
    public record QuoteRequest(
            @Valid @NotEmpty(message = "请选择要购买的商品") List<OrderDtos.ItemRequest> items) {
    }
}
