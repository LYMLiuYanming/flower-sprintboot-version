package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 消息通知中心的入参（U04/U07/U08/U23）。
 *
 * <p>表单一律用 record + 注解校验：出问题的字段要能点名说清，
 * 而不是把 null 传到服务层再抛一句「请求参数不正确」。
 */
public final class NotificationDtos {

    private NotificationDtos() {
    }

    /** U08 单格偏好：类别 × 渠道，只有 channel=inbox 允许 enabled=true */
    public record PrefForm(
            @NotBlank(message = "缺少消息类别")
            @Pattern(regexp = "^(trade|marketing|system|ticket)$", message = "消息类别不合法") String category,
            @NotBlank(message = "缺少渠道")
            @Pattern(regexp = "^(inbox|sms|email)$", message = "渠道不合法") String channel,
            @NotNull(message = "请指定开关状态") Boolean enabled) {
    }

    /** U08 整表提交：偏好页一次保存全部开关，避免 12 个请求各成功一半 */
    public record PrefMatrixForm(
            List<PrefForm> prefs,
            Boolean dndEnabled,
            LocalTime dndStart,
            LocalTime dndEnd,
            Boolean marketingPaused) {
    }

    /** U09 免打扰时段单独改：账号设置页只调这一格，不必提交整张偏好矩阵 */
    public record QuietHoursForm(
            @NotNull(message = "请指定开关") Boolean dndEnabled,
            LocalTime dndStart,
            LocalTime dndEnd) {
    }

    /** U23 退订：类别固定 marketing，服务层仍要判一次，防止页面被改成别的类别 */
    public record UnsubscribeForm(
            @NotBlank(message = "缺少消息类别") String category,
            Boolean enabled) {
    }

    /** U07 模板表单：varKeys 是必填槽位声明，与标题/正文里的 {slot} 不匹配时服务端直接拒绝 */
    public record TemplateForm(
            @NotBlank(message = "请填写模板编码") @Size(max = 40, message = "模板编码不超过 40 字") String code,
            @NotBlank(message = "请选择消息类别")
            @Pattern(regexp = "^(trade|marketing|system|ticket)$", message = "消息类别不合法") String category,
            @NotBlank(message = "请填写标题模板") @Size(max = 200) String titleTpl,
            @NotBlank(message = "请填写正文模板") @Size(max = 4000) String contentTpl,
            @Size(max = 300) String varKeys,
            Boolean enabled,
            @Size(max = 200) String remark) {
    }

    /**
     * U23 群发表单。
     *
     * <p>{@code dryRun} 用来先看清人群规模与会被策略拦下的人数，再决定要不要真发；
     * 预览阶段不写 user_message，只回统计。
     */
    public record BroadcastForm(
            @NotBlank(message = "请填写消息标题") @Size(max = 60, message = "标题不超过 60 字") String title,
            @NotBlank(message = "请填写消息正文") @Size(max = 800, message = "正文不超过 800 字") String content,
            @NotBlank(message = "请选择人群") String segmentCode,
            @Size(max = 40) String linkUrl,
            Integer windowDays,
            Boolean dryRun) {
    }

    /** 后台触达明细筛选（U04/U06/U23 共用一套条件） */
    public record MessageQuery(
            String category,
            String bizType,
            UUID userId,
            String keyword,
            LocalDateTime from,
            LocalDateTime to,
            int page,
            int limit) {
    }
}
