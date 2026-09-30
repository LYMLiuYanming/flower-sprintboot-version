package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ContentDtos {

    /**
     * 轮播图表单（F13/F14）。
     * linkType/linkTarget 是新的跳转语义，linkUrl 保留给首页等旧页面读取，
     * 服务端按类型解析后回填，因此三者可以同时提交。
     */
    public record BannerForm(
            @NotBlank(message = "请填写标题") @Size(max = 100) String title,
            @NotBlank(message = "请填写图片地址") @Size(max = 500) String imageUrl,
            @Size(max = 500) String linkUrl,
            @Size(max = 500) String description,
            @NotNull(message = "请填写排序值") @Min(0) @Max(9999) Integer sortOrder,
            @Pattern(regexp = "^(active|inactive)$", message = "状态取值不合法") String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            @Pattern(regexp = "^$|^(none|product|category|url|page)$", message = "跳转类型不合法") String linkType,
            @Size(max = 100) String linkTarget,
            @Size(max = 500) String thumbUrl) {
    }

    /** 拖拽排序：ids 为页面上从上到下的新顺序 */
    public record BannerSortRequest(
            @NotEmpty(message = "请提交排序后的轮播列表") List<UUID> ids) {
    }

    /**
     * 公告表单（F11/F12）：publishAt 为空即立即上线，offlineAt 为空即长期有效；
     * 上下线由查询侧时间窗判定，不依赖定时任务。
     */
    public record NoticeForm(
            @NotBlank(message = "请填写公告标题") @Size(max = 100) String title,
            @NotBlank(message = "请填写公告内容") @Size(max = 20000, message = "公告内容过长") String content,
            @Pattern(regexp = "^$|^(system|activity|general)$", message = "公告类型不合法") String noticeType,
            @NotNull Boolean isTop,
            @Pattern(regexp = "^(active|inactive)$", message = "状态取值不合法") String status,
            LocalDateTime publishAt,
            LocalDateTime offlineAt,
            @Size(max = 500) String coverImage) {
    }

    /** 旧版提交体：order-detail（D 批次持有）仍按这五个字段 POST，字段含义不得变更 */
    public record ReviewForm(
            @NotNull(message = "请选择订单") UUID orderId,
            @NotNull(message = "请选择评价商品") UUID orderItemId,
            @NotNull(message = "请填写评分") @Min(value = 1, message = "评分为 1-5 星") @Max(5) Integer rating,
            @Size(max = 500, message = "评价内容不超过 500 字") String content,
            @Size(max = 2000) String images) {
    }

    /** F01/F02/F05 新版提交体：图片走 /api/reviews/uploads/image 上传后回传的站内路径 */
    public record ReviewSubmit(
            @NotNull(message = "请选择订单") UUID orderId,
            @NotNull(message = "请选择评价商品") UUID orderItemId,
            @NotNull(message = "请填写评分") @Min(value = 1, message = "评分为 1-5 星") @Max(5) Integer rating,
            @Size(max = 500, message = "评价内容不超过 500 字") String content,
            @Size(max = 9, message = "最多上传 9 张图片") List<@NotBlank String> images,
            @Size(max = 6, message = "标签最多选 6 个") List<@NotBlank String> tags,
            Boolean anonymous) {
    }

    /** F03 买家追评：一条评价只能追加一次 */
    public record ReviewAppendForm(
            @NotBlank(message = "请填写追评内容") @Size(max = 500, message = "追评不超过 500 字") String content,
            @Size(max = 6, message = "最多上传 6 张图片") List<@NotBlank String> images) {
    }

    /** F04 商家回复 */
    public record ReviewReplyForm(
            @NotBlank(message = "请填写回复内容") @Size(max = 500, message = "回复不超过 500 字") String content) {
    }

    /** F08 后台审核：隐藏与恢复共用一个动作，理由会同时写入评价行与审计日志 */
    public record ReviewModerationForm(
            @NotNull(message = "请指定显示或隐藏") Boolean visible,
            @Size(max = 200, message = "理由不超过 200 字") String reason) {
    }

    /** F15 知识库文章表单 */
    public record ArticleForm(
            @NotBlank(message = "请填写文章标题") @Size(max = 100) String title,
            @Size(max = 200) String summary,
            @Size(max = 500) String coverImage,
            @NotBlank(message = "请填写正文") @Size(max = 20000, message = "正文过长") String content,
            @Pattern(regexp = "^(care|language|story|guide|festival)$", message = "文章分类不合法") String category,
            @Size(max = 200) String tags,
            @Size(max = 300) String materials,
            List<UUID> relatedProductIds,
            @Pattern(regexp = "^(draft|published|offline)$", message = "状态取值不合法") String status,
            LocalDateTime publishAt,
            LocalDateTime offlineAt,
            Boolean isTop,
            @Min(0) @Max(9999) Integer sortOrder,
            @Size(max = 50) String authorName) {
    }

    /** 公告已读上报（F12）：由详情页在登录态下调用 */
    public record NoticeReadRequest(@NotNull UUID noticeId) {
    }
}
