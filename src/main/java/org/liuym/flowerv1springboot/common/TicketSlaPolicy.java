package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.Ticket;
import org.liuym.flowerv1springboot.model.TicketSlaRule;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * SLA 计时与逾期判定（U14/U25）：纯函数，跨月、跨年边界都有单测覆盖（TicketSlaTest）。
 *
 * <p>时限一律按「自然分钟」从建单时刻起算，不按工作时段裁剪：门店客服就是轮班制，
 * 真需要夜间停表的话得先有排班日历，届时改这里一处即可，页面与库表都不用动。
 */
public final class TicketSlaPolicy {

    /** 即将超时的提前量（U15 快捷筛选）：剩 60 分钟以内进「即将超时」 */
    public static final int WARNING_MINUTES = 60;

    /** 逾期升级后的目标优先级（U14）：urgent 已经是最高，只能保持 */
    public static final String ESCALATED_PRIORITY = Ticket.PRIORITY_URGENT;

    private TicketSlaPolicy() {
    }

    /**
     * 优先级阶梯：逾期升级按阶梯往上跳一格，已经是 urgent 就保持原值。
     */
    public static String nextPriority(String current) {
        return switch (current == null ? Ticket.PRIORITY_NORMAL : current) {
            case Ticket.PRIORITY_LOW -> Ticket.PRIORITY_NORMAL;
            case Ticket.PRIORITY_NORMAL -> Ticket.PRIORITY_HIGH;
            case Ticket.PRIORITY_HIGH, Ticket.PRIORITY_URGENT -> Ticket.PRIORITY_URGENT;
            default -> Ticket.PRIORITY_URGENT;
        };
    }

    /** 首响截止时刻：规则缺值时按 120 分钟兜底，绝不让工单变成「没有 SLA」 */
    public static LocalDateTime firstResponseDueAt(LocalDateTime createdAt, TicketSlaRule rule) {
        return createdAt.plusMinutes(minutes(rule == null ? null : rule.getFirstResponseMinutes(), 120));
    }

    /** 解决截止时刻：规则缺值时按 1440 分钟（24 小时）兜底 */
    public static LocalDateTime resolveDueAt(LocalDateTime createdAt, TicketSlaRule rule) {
        return createdAt.plusMinutes(minutes(rule == null ? null : rule.getResolveMinutes(), 1440));
    }

    /** 分钟数兜底：非正值按默认值处理，避免运营填 0 让所有工单建单即逾期 */
    private static long minutes(Integer raw, int fallback) {
        return raw == null || raw <= 0 ? fallback : raw;
    }

    /** 是否逾期：只有未结单工单参与判定，已结单的时限冻结在结论时刻 */
    public static boolean isOverdue(Ticket ticket, LocalDateTime now) {
        if (ticket == null || now == null || TicketFlowPolicy.isSettled(ticket.getStatus())) {
            return false;
        }
        LocalDateTime due = ticket.getResolveDueAt();
        return due != null && due.isBefore(now);
    }

    /** 首响是否逾期：客服已回复过（firstResponseAt 非空）就不再算逾期 */
    public static boolean isFirstResponseOverdue(Ticket ticket, LocalDateTime now) {
        if (ticket == null || now == null || ticket.getFirstResponseAt() != null
                || TicketFlowPolicy.isSettled(ticket.getStatus())) {
            return false;
        }
        LocalDateTime due = ticket.getFirstResponseDueAt();
        return due != null && due.isBefore(now);
    }

    /** 距解决时限还剩多少分钟；已逾期为负数，随时限缺失返回 null（页面显示「未设时限」） */
    public static Long minutesLeft(Ticket ticket, LocalDateTime now) {
        if (ticket == null || ticket.getResolveDueAt() == null || now == null) {
            return null;
        }
        return Duration.between(now, ticket.getResolveDueAt()).toMinutes();
    }

    /** 「即将超时」= 未结单 + 剩余时间在 (0, WARNING_MINUTES]；已逾期的走逾期口径 */
    public static boolean isApproachingDeadline(Ticket ticket, LocalDateTime now) {
        if (ticket == null || TicketFlowPolicy.isSettled(ticket.getStatus())) {
            return false;
        }
        Long left = minutesLeft(ticket, now);
        return left != null && left > 0 && left <= WARNING_MINUTES;
    }

    /** 队列展示用的时限状态码 */
    public static String deadlineState(Ticket ticket, LocalDateTime now) {
        Long left = minutesLeft(ticket, now);
        if (left == null) {
            return "none";
        }
        if (TicketFlowPolicy.isSettled(ticket == null ? null : ticket.getStatus())) {
            return "settled";
        }
        if (left <= 0) {
            return "overdue";
        }
        return left <= WARNING_MINUTES ? "warning" : "normal";
    }

    /** 逾期分钟数（看板与台账用），未逾期返回 0 */
    public static long overdueMinutes(Ticket ticket, LocalDateTime now) {
        Long left = minutesLeft(ticket, now);
        return left == null || left >= 0 ? 0 : -left;
    }

    /** 首响时长（分钟）：没有首响记录返回 null，分位统计要把它排除而不是当 0 */
    public static Long firstResponseMinutes(Ticket ticket) {
        if (ticket == null || ticket.getFirstResponseAt() == null || ticket.getCreatedAt() == null) {
            return null;
        }
        return Duration.between(ticket.getCreatedAt(), ticket.getFirstResponseAt()).toMinutes();
    }

    /** 解决时长（分钟）：只有已解决/已关闭的工单有值 */
    public static Long resolveMinutes(Ticket ticket) {
        if (ticket == null || ticket.getResolvedAt() == null || ticket.getCreatedAt() == null) {
            return null;
        }
        return Duration.between(ticket.getCreatedAt(), ticket.getResolvedAt()).toMinutes();
    }
}
