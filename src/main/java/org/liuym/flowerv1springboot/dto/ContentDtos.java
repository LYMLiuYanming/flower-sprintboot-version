package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ContentDtos {

    public record BannerForm(
            @NotBlank(message = "请填写标题") @Size(max = 100) String title,
            @NotBlank(message = "请填写图片地址") @Size(max = 500) String imageUrl,
            @Size(max = 500) String linkUrl,
            @Size(max = 500) String description,
            @NotNull(message = "请填写排序值") @Min(0) @Max(9999) Integer sortOrder,
            @Pattern(regexp = "^(active|inactive)$", message = "状态取值不合法") String status,
            LocalDateTime startTime,
            LocalDateTime endTime) {
    }

    /** 拖拽排序：ids 为页面上从上到下的新顺序 */
    public record BannerSortRequest(
            @NotEmpty(message = "请提交排序后的轮播列表") List<UUID> ids) {
    }

    public record NoticeForm(            @NotBlank(message = "请填写公告标题") @Size(max = 100) String title,
            @NotBlank(message = "请填写公告内容") @Size(max = 20000, message = "公告内容过长") String content,
            @Pattern(regexp = "^$|^(system|activity|general)$", message = "公告类型不合法") String noticeType,
            @NotNull Boolean isTop,
            @Pattern(regexp = "^(active|inactive)$", message = "状态取值不合法") String status) {
    }

    public record ReviewForm(
            @NotNull(message = "请选择订单") UUID orderId,
            @NotNull(message = "请选择评价商品") UUID orderItemId,
            @NotNull(message = "请填写评分") @Min(value = 1, message = "评分为 1-5 星") @Max(5) Integer rating,
            @Size(max = 500, message = "评价内容不超过 500 字") String content,
            @Size(max = 2000) String images) {
    }
}
