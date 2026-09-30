package org.liuym.flowerv1springboot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 优惠券相关入参：后台模板表单、后台直发、用户领券、转赠
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
            @Pattern(regexp = "^(active|inactive)$", message = "状态不合法") String status,
            /** E01：满减阶梯 "199:20,399:60"，非空时优先于单档门槛 */
            @Size(max = 200, message = "满减阶梯条款过长") String ladderRule,
            /** E02：多品类 category.id 列表 */
            List<UUID> categoryIds,
            /** E03：商品白名单 product.id 列表 */
            List<UUID> productIds,
            /** E04：每人每日限领张数，0 表示不按天限制 */
            @Min(value = 0, message = "每日限领不能为负") Integer perUserDailyLimit,
            /** E05：新客专享 */
            Boolean newUserOnly,
            /** E07：允许转赠 */
            Boolean allowTransfer,
            /** E18：会员专享 */
            Boolean memberOnly,
            /** E12：停用后已领券处理策略 */
            @Pattern(regexp = "^$|^(keep|void)$", message = "停用策略仅支持保留或作废") String disablePolicy,
            /** 发放场景 */
            @Pattern(regexp = "^$|^(claim|admin|after_pay|invite)$", message = "发放场景不合法") String triggerScene,
            /** E15：返券门槛 */
            @DecimalMin(value = "0", message = "发放门槛不能为负") BigDecimal grantMinAmount) {
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

    /** E08：下单前的可用性与失败原因检查 */
    public record CheckRequest(
            @NotNull(message = "请选择优惠券") UUID userCouponId,
            @Valid @NotEmpty(message = "请选择要购买的商品") List<OrderDtos.ItemRequest> items) {
    }

    /** E07：发起转赠，receiver 可空表示凭码通用 */
    public record TransferRequest(
            @NotNull(message = "请选择优惠券") UUID userCouponId,
            @Size(max = 50, message = "收礼人填写过长") String receiver) {
    }

    public record TransferCancelRequest(
            @NotNull(message = "请选择优惠券") UUID userCouponId) {
    }

    public record TransferAcceptRequest(
            @NotBlank(message = "请填写转赠码") @Size(max = 40, message = "转赠码不正确") String token) {
    }

    /** E12：下架时选择已领券的处理策略 */
    public record DisableRequest(
            @NotBlank(message = "请选择处理方式") @Pattern(regexp = "^(keep|void)$", message = "处理方式仅支持保留或作废") String policy) {
    }

    /** E09：券与积分的组合建议 */
    public record ComboRequest(
            @NotNull(message = "请填写订单金额") @DecimalMin(value = "0", message = "金额不能为负") BigDecimal amount,
            @Valid @NotEmpty(message = "请选择要购买的商品") List<OrderDtos.ItemRequest> items,
            UUID userCouponId) {
    }

    /** 领券中心按分类筛选：categoryId 为空表示全部 */
    public record CenterFilter(
            UUID categoryId) {
    }
}
