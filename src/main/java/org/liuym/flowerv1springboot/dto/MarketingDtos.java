package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 优惠券后台维护与前台操作的入参补充：满减活动（E14）、促销位（E19）、邀请（E16）、会员（E18）
 */
public final class MarketingDtos {

    private MarketingDtos() {
    }

    /** E14：满减活动表单，阶梯与券共用 "199:20,399:60" 口径 */
    public record ReductionForm(
            @NotBlank(message = "活动名称必填") @Size(max = 60, message = "活动名称不超过 60 字") String name,
            @NotBlank(message = "适用范围必填") @Pattern(regexp = "^(all|category)$", message = "适用范围仅支持全场或指定分类") String scope,
            List<UUID> categoryIds,
            @NotBlank(message = "满减阶梯必填") @Size(max = 200, message = "满减阶梯条款过长") String ladderRule,
            Boolean stackWithCoupon,
            Integer priority,
            java.time.LocalDateTime startTime,
            java.time.LocalDateTime endTime,
            @Pattern(regexp = "^$|^(active|inactive)$", message = "状态不合法") String status) {
    }

    /** E19：促销位表单 */
    public record SlotForm(
            @NotBlank(message = "投放位置必填") @Pattern(regexp = "^(home|pdp|cart|center)$", message = "投放位置仅支持 home/pdp/cart/center") String position,
            @NotBlank(message = "位置名称必填") @Size(max = 60, message = "位置名称不超过 60 字") String name,
            UUID couponId,
            @NotBlank(message = "标题必填") @Size(max = 80, message = "标题不超过 80 字") String title,
            @Size(max = 160, message = "副标题不超过 160 字") String subtitle,
            @Size(max = 300, message = "跳转地址过长") String linkUrl,
            @Size(max = 300, message = "图片地址过长") String imageUrl,
            Integer sortOrder,
            @Pattern(regexp = "^$|^(active|inactive)$", message = "状态不合法") String status,
            java.time.LocalDateTime startTime,
            java.time.LocalDateTime endTime) {
    }

    /** E19：拖拽排序，ids 为从上到下的新顺序 */
    public record SlotSortRequest(@NotNull List<UUID> ids) {
    }

    /** E16：绑定邀请码 */
    public record BindRequest(
            @NotBlank(message = "请填写邀请码") @Size(max = 16, message = "邀请码过长") String code) {
    }

    /** E18：开通/续费会员的年限 */
    public record MembershipRequest(
            @NotNull(message = "请选择开通时长") Integer years) {
    }
}
