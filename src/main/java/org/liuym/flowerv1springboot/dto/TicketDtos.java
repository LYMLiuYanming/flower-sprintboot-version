package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 客服工单的入参（U12/U13/U14/U17/U19/U24/U26）。
 */
public final class TicketDtos {

    private TicketDtos() {
    }

    /**
     * U12 顾客提单。
     *
     * <p>{@code orderId} 可空（咨询类工单没有订单）；非空时服务端必须校验它属于当前用户，
     * 绝不相信前端传来的归属。{@code contactPhone} 不由页面填写，
     * 服务层按订单收货电话或账号手机号快照，出网一律脱敏（U16）。
     */
    public record CreateForm(
            @NotBlank(message = "请选择问题类型")
            @Pattern(regexp = "^(quality|delivery|refund|card|subscription|other)$", message = "问题类型不合法")
            String category,
            @NotBlank(message = "请填写问题标题") @Size(max = 60, message = "标题不超过 60 字") String title,
            @NotBlank(message = "请填写问题描述") @Size(min = 5, max = 1000, message = "描述请写 5-1000 字")
            String description,
            UUID orderId,
            @Size(max = 2000, message = "最多 9 张图片") String images,
            @Size(max = 40) String priority) {
    }

    /** U13 状态跃迁：to 用状态码，reason 只在驳回/关闭这类需要说明的动作上必填 */
    public record TransitForm(
            @NotBlank(message = "请选择目标状态") String to,
            @Size(max = 200) String reason,
            @Size(max = 2000) String solution) {
    }

    /** U26 派单/认领：assigneeId 为空表示认领给自己 */
    public record AssignForm(
            UUID assigneeId,
            @Size(max = 200) String note) {
    }

    /** U16 往返回复 */
    public record ReplyForm(
            @NotBlank(message = "请填写回复内容") @Size(min = 1, max = 2000, message = "回复内容请写 1-2000 字")
            String content,
            @Size(max = 2000) String attachments,
            Boolean internalNote) {
    }

    /**
     * U17 补偿。
     *
     * <p>{@code couponId} 只在 type=coupon 时必填；type=refund 必须带 orderId，
     * 服务层复用第一轮 C19 的退款申请链路，不在工单侧另算一套退款金额。
     */
    public record CompensationForm(
            @NotBlank(message = "请选择补偿方式")
            @Pattern(regexp = "^(coupon|refund)$", message = "补偿方式只支持发券或退款") String type,
            UUID couponId,
            @Size(max = 200) String note) {
    }

    /** U24 满意度：1-5 分一次性评分 */
    public record RateForm(
            @NotNull(message = "请给出评分") @Min(value = 1, message = "评分为 1-5 分")
            @Max(value = 5, message = "评分为 1-5 分") Integer score,
            @Size(max = 100, message = "备注不超过 100 字") String note) {
    }

    /** U14 SLA 规则表单 */
    public record SlaRuleForm(
            @NotBlank(message = "请选择问题类型")
            @Pattern(regexp = "^(quality|delivery|refund|card|subscription|other)$", message = "问题类型不合法")
            String category,
            @NotBlank(message = "请选择优先级")
            @Pattern(regexp = "^(low|normal|high|urgent)$", message = "优先级不合法") String priority,
            @NotNull(message = "请填写首响时限") @Min(value = 5, message = "首响时限不少于 5 分钟")
            @Max(value = 10080, message = "首响时限不超过 7 天") Integer firstResponseMinutes,
            @NotNull(message = "请填写解决时限") @Min(value = 15, message = "解决时限不少于 15 分钟")
            @Max(value = 43200, message = "解决时限不超过 30 天") Integer resolveMinutes,
            Boolean enabled,
            @Size(max = 200) String remark) {
    }

    /** U19 FAQ 表单 */
    public record FaqForm(
            @NotBlank(message = "请填写问题") @Size(max = 100, message = "问题不超过 100 字") String question,
            @NotBlank(message = "请填写答案") @Size(max = 2000, message = "答案不超过 2000 字") String answer,
            @NotBlank(message = "请填写关键词") @Size(max = 200, message = "关键词不超过 200 字") String keywords,
            @Pattern(regexp = "^$|^(quality|delivery|refund|card|subscription|other)$", message = "分类不合法")
            String category,
            Boolean enabled,
            @Min(0) @Max(9999) Integer sortOrder) {
    }

    /** U26 客服账号登记：主管可见全量，普通客服只看分配给自己的工单 */
    public record AgentForm(
            @NotNull(message = "请选择账号") UUID userId,
            @Size(max = 40) String displayName,
            Boolean supervisor,
            Boolean enabled) {
    }

    /** 后台队列筛选（U15/U26） */
    public record QueueQuery(
            String status,
            String category,
            String priority,
            UUID assigneeId,
            UUID orderId,
            String keyword,
            String deadlineState,
            int page,
            int limit) {
    }

    /** 看板时间窗（U25） */
    public record DashboardQuery(String range, java.time.LocalDateTime from, java.time.LocalDateTime to) {
    }

    /** 养护提醒的手工触发参数（后台排障用，只按订单号补发） */
    public record CareTriggerForm(@NotNull(message = "请选择订单") UUID orderId) {
    }

    /** 一次要标记已读的消息 id 集合（U04 批量已读） */
    public record ReadIdsForm(@Size(max = 200, message = "单次最多标记 200 条") List<UUID> ids) {
    }
}
