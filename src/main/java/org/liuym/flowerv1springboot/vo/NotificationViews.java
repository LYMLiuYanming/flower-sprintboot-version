package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.Masked;
import org.liuym.flowerv1springboot.common.MessageKeys;
import org.liuym.flowerv1springboot.model.BroadcastCampaign;
import org.liuym.flowerv1springboot.model.MessageTemplate;
import org.liuym.flowerv1springboot.model.NotificationPref;
import org.liuym.flowerv1springboot.model.ScheduledMessage;
import org.liuym.flowerv1springboot.model.UserMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息通知中心的出参（U03/U04/U07/U08/U21/U23）。
 *
 * <p>出网字段里没有任何口令类列；手机号若出现一律走 {@link Masked} 口径。
 * 类别与事件的中文标签都由服务端给，页面不再维护第二套 code→文案映射。
 */
public final class NotificationViews {

    private NotificationViews() {
    }

    /** 类别中文名 */
    public static String categoryLabel(String category) {
        return switch (category == null ? "" : category) {
            case UserMessage.CATEGORY_TRADE -> "交易提醒";
            case UserMessage.CATEGORY_MARKETING -> "营销活动";
            case UserMessage.CATEGORY_SYSTEM -> "系统通知";
            case UserMessage.CATEGORY_TICKET -> "客服工单";
            default -> "其他";
        };
    }

    /** U04 列表项：linkUrl 已在入库前校验过，页面可以直接当链接用 */
    public record MessageView(UUID id, String category, String categoryLabel, String title, String content,
                              String linkUrl, String bizType, String bizLabel, Boolean unread,
                              LocalDateTime createdAt, UUID userId,
                              @Masked(Masked.Kind.PHONE) String userPhone) {

        public static MessageView of(UserMessage m) {
            // record 组件的零参访问器 categoryLabel() 会遮蔽外层同名有参静态方法，必须限定调用
            return new MessageView(m.getId(), m.getCategory(), NotificationViews.categoryLabel(m.getCategory()),
                    m.getTitle(),
                    m.getContent(), m.getLinkUrl(), m.getBizType(), MessageKeys.eventLabel(m.getBizType()),
                    m.isUnread(), m.getCreatedAt(), null, null);
        }

        /** 后台触达明细：带上收件人手机号（脱敏），运营找人才有用 */
        public static MessageView ofAdmin(UserMessage m, String phone) {
            return new MessageView(m.getId(), m.getCategory(), NotificationViews.categoryLabel(m.getCategory()),
                    m.getTitle(),
                    m.getContent(), m.getLinkUrl(), m.getBizType(), MessageKeys.eventLabel(m.getBizType()),
                    m.isUnread(), m.getCreatedAt(), m.getUserId(), phone);
        }
    }

    /** U03 角标 + 下拉最近若干条 */
    public record UnreadBadge(long unread, List<MessageView> recent, List<CategoryCount> byCategory) {
    }

    public record CategoryCount(String category, String label, long total, long unread) {
    }

    /** U08 偏好矩阵的一格：available=false 的渠道页面渲染成「未开通」，开关是灰的且提交也无效 */
    public record PrefCell(String category, String categoryLabel, String channel, String channelLabel,
                           boolean enabled, boolean available, String note) {
    }

    /** U08/U09 偏好页整体视图 */
    public record PreferenceView(List<PrefCell> matrix, boolean dndEnabled, String dndStart, String dndEnd,
                                 boolean marketingPaused, String dndHint, List<Map<String, String>> categories) {
    }

    public static PreferenceView ofPreference(List<NotificationPref> prefs, boolean dndEnabled,
                                              java.time.LocalTime start, java.time.LocalTime end,
                                              boolean marketingPaused) {
        List<PrefCell> matrix = List.of(
                UserMessage.CATEGORY_TRADE, UserMessage.CATEGORY_MARKETING,
                UserMessage.CATEGORY_SYSTEM, UserMessage.CATEGORY_TICKET)
                .stream()
                .flatMap(category -> java.util.List.of(
                        new PrefCell(category, categoryLabel(category), NotificationPref.CHANNEL_INBOX, "站内信",
                                enabledOf(prefs, category, NotificationPref.CHANNEL_INBOX), true,
                                "站内信即时落库，免打扰时段内会顺延"),
                        new PrefCell(category, categoryLabel(category), NotificationPref.CHANNEL_SMS, "短信",
                                false, false, "短信通道未开通，本站不外呼任何短信"),
                        new PrefCell(category, categoryLabel(category), NotificationPref.CHANNEL_EMAIL, "邮件",
                                false, false, "邮件通道未开通，本站不外呼任何邮件")).stream())
                .toList();
        return new PreferenceView(matrix, dndEnabled,
                start == null ? null : start.withSecond(0).withNano(0).toString(),
                end == null ? null : end.withSecond(0).withNano(0).toString(),
                marketingPaused,
                "默认 22:00-08:00 免打扰；交易与工单消息不受此时段推迟（延误提醒本身才最打扰人）",
                List.of(Map.of("code", UserMessage.CATEGORY_TRADE, "label", "交易提醒"),
                        Map.of("code", UserMessage.CATEGORY_MARKETING, "label", "营销活动"),
                        Map.of("code", UserMessage.CATEGORY_SYSTEM, "label", "系统通知"),
                        Map.of("code", UserMessage.CATEGORY_TICKET, "label", "客服工单")));
    }

    private static boolean enabledOf(List<NotificationPref> prefs, String category, String channel) {
        return prefs.stream()
                .filter(p -> category.equals(p.getCategory()) && channel.equals(p.getChannel()))
                .findFirst()
                .map(p -> Boolean.TRUE.equals(p.getEnabled()))
                // 站内信没有行时默认开：新用户不必先进偏好页才能收到发货通知
                .orElse(NotificationPref.CHANNEL_INBOX.equals(channel));
    }

    /** U07 模板视图：slots 是服务端解析出来的槽位，后台据此核对 varKeys */
    public record TemplateView(UUID id, String code, String category, String categoryLabel,
                               String titleTpl, String contentTpl, String varKeys,
                               List<String> slots, Boolean enabled, String remark, LocalDateTime updatedAt) {

        public static TemplateView of(MessageTemplate t) {
            return new TemplateView(t.getId(), t.getCode(), t.getCategory(),
                    NotificationViews.categoryLabel(t.getCategory()),
                    t.getTitleTpl(), t.getContentTpl(), t.getVarKeys(),
                    List.copyOf(org.liuym.flowerv1springboot.common.MessageRenderPolicy.slotsOf(
                            t.getTitleTpl(), t.getContentTpl())),
                    Boolean.TRUE.equals(t.getEnabled()), t.getRemark(), t.getUpdatedAt());
        }
    }

    /** U21 延时消息视图：customised=false 时页面标注「未定制」，不留空（U22） */
    public record ScheduledView(UUID id, UUID userId, String category, String title, String content,
                                LocalDateTime dueAt, String status, String statusLabel, Integer attempts,
                                Integer maxAttempts, Boolean customised, String bizType, String bizLabel,
                                String lastError, LocalDateTime sentAt) {

        public static ScheduledView of(ScheduledMessage s) {
            return new ScheduledView(s.getId(), s.getUserId(), s.getCategory(), s.getTitle(), s.getContent(),
                    s.getDueAt(), s.getStatus(), statusLabel(s.getStatus()), s.getAttempts(), s.getMaxAttempts(),
                    Boolean.TRUE.equals(s.getCustomised()), s.getBizType(), MessageKeys.eventLabel(s.getBizType()),
                    s.getLastError(), s.getSentAt());
        }

        private static String statusLabel(String status) {
            return switch (status == null ? "" : status) {
                case ScheduledMessage.STATUS_PENDING -> "待投递";
                case ScheduledMessage.STATUS_SENDING -> "投递中";
                case ScheduledMessage.STATUS_SENT -> "已投递";
                case ScheduledMessage.STATUS_FAILED -> "投递失败";
                case ScheduledMessage.STATUS_CANCELLED -> "已取消";
                default -> status;
            };
        }
    }

    /** U23 群发台账 */
    public record CampaignView(UUID id, String title, String segmentCode, String segmentName, String status,
                               Integer targetCount, Integer sentCount, Integer skippedPref, Integer skippedDnd,
                               Integer skippedCap, Integer failedCount, String errorNote, String createdBy,
                               LocalDateTime createdAt, LocalDateTime finishedAt) {

        public static CampaignView of(BroadcastCampaign c) {
            return new CampaignView(c.getId(), c.getTitle(), c.getSegmentCode(), c.getSegmentName(), c.getStatus(),
                    c.getTargetCount(), c.getSentCount(), c.getSkippedPref(), c.getSkippedDnd(),
                    c.getSkippedCap(), c.getFailedCount(), c.getErrorNote(), c.getCreatedBy(),
                    c.getCreatedAt(), c.getFinishedAt());
        }
    }

    /** U23 群发预览/执行结果：dryRun 只回人数与拦截原因，不落库 */
    public record BroadcastResult(boolean dryRun, long targetCount, int sent, int skippedPref,
                                  int skippedDnd, int skippedCap, int skippedWeekend, int failed, String note) {

        /** 实发口径的「已触达」：免打扰顺延的那些也会到点投出去 */
        public long reached() {
            return sent + skippedDnd;
        }
    }

    /** U05 越权跳转计数：后台据此判断有没有人在拿站内信发钓鱼链接 */
    public record GuardStat(long total24h, long total7d, List<Map<String, Object>> byReason,
                            List<String> recentReasons) {
    }

    /** U10 保留期清理结果 */
    public record RetentionResult(int retentionDays, long deletedCount, long archivedBuckets,
                                  LocalDateTime cutoff, String note) {
    }

    /** U06 事件接入清单：后台一屏看清八类事件的近况 */
    public record EventIngestRow(String bizType, String label, long delivered24h, long skipped24h, String note) {
    }
}
