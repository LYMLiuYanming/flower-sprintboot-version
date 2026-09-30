package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.Ticket;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单状态机（U13）：{@code open → assigned → processing → resolved → closed}，
 * 允许 {@code closed → open} 重开<b>仅一次</b>。
 *
 * <p>与第一轮 {@code OrderFlowPolicy} 同写法：非法跃迁不只返回「不允许」，
 * 而是按当前状态给出下一步动作（「工单已关闭且已重开过一次，请新建工单」），
 * 顾客与客服看到才知道该点什么。
 */
public final class TicketFlowPolicy {

    private TicketFlowPolicy() {
    }

    /** 允许跃迁表：closed 只回 open，resolved 可关可被重开，终态 closed 之后仅一次重开 */
    private static final Map<String, Set<String>> ALLOWED = new LinkedHashMap<>();

    static {
        ALLOWED.put(Ticket.STATUS_OPEN, Set.of(Ticket.STATUS_ASSIGNED, Ticket.STATUS_PROCESSING,
                Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED));
        ALLOWED.put(Ticket.STATUS_ASSIGNED, Set.of(Ticket.STATUS_PROCESSING, Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED));
        ALLOWED.put(Ticket.STATUS_PROCESSING, Set.of(Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED));
        ALLOWED.put(Ticket.STATUS_RESOLVED, Set.of(Ticket.STATUS_CLOSED, Ticket.STATUS_OPEN));
        ALLOWED.put(Ticket.STATUS_CLOSED, Set.of(Ticket.STATUS_OPEN));
    }

    /** 状态字典：页面与后台都用它渲染中文，避免出现第二套 code→文案映射 */
    public static final Map<String, String> STATUS_LABELS = Map.of(
            Ticket.STATUS_OPEN, "待受理",
            Ticket.STATUS_ASSIGNED, "已派单",
            Ticket.STATUS_PROCESSING, "处理中",
            Ticket.STATUS_RESOLVED, "已解决",
            Ticket.STATUS_CLOSED, "已关闭");

    public static final Map<String, String> PRIORITY_LABELS = Map.of(
            Ticket.PRIORITY_LOW, "低",
            Ticket.PRIORITY_NORMAL, "普通",
            Ticket.PRIORITY_HIGH, "高",
            Ticket.PRIORITY_URGENT, "紧急");

    public static final Map<String, String> CATEGORY_LABELS = Map.of(
            Ticket.CATEGORY_QUALITY, "花材与品质",
            Ticket.CATEGORY_DELIVERY, "配送问题",
            Ticket.CATEGORY_REFUND, "退款售后",
            Ticket.CATEGORY_CARD, "礼品卡与储值",
            Ticket.CATEGORY_SUBSCRIPTION, "订阅与周期送",
            Ticket.CATEGORY_OTHER, "其他咨询");

    /** 顾客能自己发起的动作只有「重开」，其余跃迁属于后台 */
    public static final Set<String> CUSTOMER_ACTIONS = Set.of(Ticket.STATUS_OPEN);

    public static String statusLabel(String status) {
        return STATUS_LABELS.getOrDefault(status, status == null ? "未知" : status);
    }

    public static String priorityLabel(String priority) {
        return PRIORITY_LABELS.getOrDefault(priority, priority == null ? "未知" : priority);
    }

    public static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category == null ? "未知" : category);
    }

    public static boolean isKnownStatus(String status) {
        return status != null && STATUS_LABELS.containsKey(status);
    }

    public static boolean isKnownPriority(String priority) {
        return priority != null && PRIORITY_LABELS.containsKey(priority);
    }

    public static boolean isKnownCategory(String category) {
        return category != null && CATEGORY_LABELS.containsKey(category);
    }

    public static List<String> statusCodes() {
        return List.of(Ticket.STATUS_OPEN, Ticket.STATUS_ASSIGNED, Ticket.STATUS_PROCESSING,
                Ticket.STATUS_RESOLVED, Ticket.STATUS_CLOSED);
    }

    public static boolean canTransit(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * 非法跃迁的原因（U13）：返回 null 表示允许。
     *
     * <p>重开次数在这里一起判：状态上 closed→open 是允许的，但第 2 次重开必须给出「请新建工单」，
     * 否则页面只能看到一句「不允许变更状态」，顾客会一直点。
     *
     * @param reopenCount 当前已重开次数
     * @param rateable    是否已评分（评分后结单，重开需先撤销评价的提示口径）
     */
    public static String denial(String from, String to, int reopenCount) {
        if (to == null || to.isBlank()) {
            return "请指定要流转到的工单状态";
        }
        if (!isKnownStatus(to)) {
            return "工单状态不存在：" + to;
        }
        if (from != null && from.equals(to)) {
            return "工单已经是「" + statusLabel(to) + "」，无需重复操作";
        }
        if (!canTransit(from, to)) {
            if (Ticket.STATUS_CLOSED.equals(from) || Ticket.STATUS_RESOLVED.equals(from)) {
                return "「" + statusLabel(from) + "」的工单不能直接变为「" + statusLabel(to)
                        + "」；如需继续处理请先重新打开工单";
            }
            return "不允许从「" + statusLabel(from) + "」变更为「" + statusLabel(to) + "」";
        }
        // 到这里状态是允许的，只剩「重开只允许一次」这一道闸
        if (Ticket.STATUS_OPEN.equals(to) && Ticket.STATUS_CLOSED.equals(from)
                && reopenCount >= Ticket.MAX_REOPEN) {
            return "这张工单已经重新打开过一次并再次关闭，不能二次重开；"
                    + "请针对同一订单新建工单，历史处理记录会在新工单里一并列出";
        }
        if (Ticket.STATUS_OPEN.equals(to) && Ticket.STATUS_RESOLVED.equals(from)
                && reopenCount >= Ticket.MAX_REOPEN) {
            return "重开次数已用完（每张工单限一次），请新建工单继续跟进";
        }
        return null;
    }

    /** 该跃迁是否算「重开」：需要累加 reopen_count 并校验上限 */
    public static boolean isReopen(String from, String to) {
        return Ticket.STATUS_OPEN.equals(to)
                && (Ticket.STATUS_CLOSED.equals(from) || Ticket.STATUS_RESOLVED.equals(from));
    }

    /** 结单类状态：SLA 计时与逾期扫描都要排除它们 */
    public static boolean isSettled(String status) {
        return Ticket.STATUS_RESOLVED.equals(status) || Ticket.STATUS_CLOSED.equals(status);
    }
}
