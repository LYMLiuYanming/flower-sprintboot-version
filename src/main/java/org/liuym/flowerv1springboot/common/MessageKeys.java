package org.liuym.flowerv1springboot.common;

import java.util.Map;
import java.util.UUID;

/**
 * 触达幂等键（U06/U21/U23）：同一业务事件对同一收件人只允许产生一条站内信。
 *
 * <p>键的组成固定为「业务类型:业务主键:收件人」，三者都来自服务端已知的数据，
 * 不接受调用方传入整串——否则任何人都能构造出一个键把别人的通知挤掉。
 */
public final class MessageKeys {

    private static final int MAX_LENGTH = 140;

    private MessageKeys() {
    }

    /**
     * @param bizType 事件类型码，如 order_paid / coupon_expiring / ticket_replied
     * @param bizId   业务主键（订单 id、券 id、工单 id…）
     */
    public static String of(String bizType, Object bizId, UUID userId) {
        if (bizType == null || bizType.isBlank() || bizId == null || userId == null) {
            return null;
        }
        String key = bizType.trim() + ":" + bizId + ":" + userId;
        return key.length() <= MAX_LENGTH ? key : key.substring(0, MAX_LENGTH);
    }

    /** 带附加维度的键：群发要按「活动 + 用户」去重，养护提醒要按「订单 + 第几天」去重 */
    public static String of(String bizType, Object bizId, UUID userId, Object qualifier) {
        if (qualifier == null) {
            return of(bizType, bizId, userId);
        }
        return of(bizType, bizId + ":" + qualifier, userId);
    }

    /** 工单来源幂等键（U18）：一张评价只能自动开一张工单，靠 ticket 上的唯一索引落死 */
    public static String sourceKey(String sourceType, Object sourceId) {
        if (sourceType == null || sourceId == null) {
            return null;
        }
        String key = sourceType.trim() + ":" + sourceId;
        return key.length() <= MAX_LENGTH ? key : key.substring(0, MAX_LENGTH);
    }

    /** 事件类型字典（U06）：与模板 code 一一对应，后台按它筛选触达明细 */
    public static final Map<String, String> EVENT_LABELS = Map.ofEntries(
            Map.entry("order_paid", "支付成功"),
            Map.entry("order_shipped", "已发货"),
            Map.entry("order_delivered", "已签收"),
            Map.entry("refund_result", "退款结果"),
            Map.entry("coupon_expiring", "优惠券到期"),
            Map.entry("card_expiring", "礼品卡到期"),
            Map.entry("card_transferred", "礼品卡转赠"),
            Map.entry("subscription_due", "订阅提醒"),
            Map.entry("ticket_replied", "工单回复"),
            Map.entry("ticket_resolved", "工单解决"),
            Map.entry("care_reminder", "养护提醒"),
            Map.entry("broadcast", "后台群发"));

    public static String eventLabel(String bizType) {
        return EVENT_LABELS.getOrDefault(bizType, bizType == null ? "未知" : bizType);
    }
}
