package org.liuym.flowerv1springboot.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 触达策略（U09/U23）：免打扰时段、营销周末停发、单用户每日营销上限的纯函数判定。
 *
 * <p>做成纯函数的理由：这三条都是「差一分钟就发错」的边界逻辑，必须能不依赖数据库单测覆盖
 * （跨午夜窗口、恰好落在 22:00 / 08:00 的端点、周六周日）。
 *
 * <p>免打扰默认 22:00-08:00，是一个<b>跨午夜</b>窗口：判定时不能简单写 {@code start <= now < end}，
 * 那种写法在 23:30 会判成「不在窗口内」。这里按「窗口是否越过零点」分两条路径。
 */
public final class NotificationPolicy {

    /** 默认免打扰起点（U09 口径） */
    public static final LocalTime DEFAULT_DND_START = LocalTime.of(22, 0);
    /** 默认免打扰终点 */
    public static final LocalTime DEFAULT_DND_END = LocalTime.of(8, 0);

    /** 单用户每日营销消息上限（U23）：鲜花营销一天一条足够，多了只会换来退订 */
    public static final int DAILY_MARKETING_CAP = 1;

    /** 交易与工单消息允许穿透免打扰：延误提醒本身就是打扰用户的元凶 */
    public static final boolean URGENT_BYPASS = true;

    private NotificationPolicy() {
    }

    public enum Action {
        /** 立即落库 */
        DELIVER,
        /** 顺延到免打扰窗口结束（进 scheduled_message） */
        DEFER,
        /** 不发送 */
        SUPPRESS
    }

    /** 被拦下的原因分类：群发台账按它分列计数，运营才知道该不该明天补发 */
    public enum Reason {
        NONE,
        /** 偏好关闭 / 退订 */
        PREF,
        /** 周末营销停发 */
        WEEKEND,
        /** 当日上限已满：次日可补 */
        DAILY_CAP,
        /** 免打扰时段内的营销消息 */
        QUIET_HOURS
    }

    /**
     * @param at     DEFER 时的投递时刻，其余为 null
     * @param reason 原因分类
     * @param note   人话说明，进台账与后台提示，绝不写「策略拒绝」这种查不出所以然的词
     */
    public record Decision(Action action, LocalDateTime at, Reason reason, String note) {

        public boolean deliverable() {
            return action == Action.DELIVER;
        }

        static Decision deliver() {
            return new Decision(Action.DELIVER, null, Reason.NONE, null);
        }

        static Decision suppress(Reason reason, String note) {
            return new Decision(Action.SUPPRESS, null, reason, note);
        }
    }

    /**
     * 当前时刻是否落在免打扰窗口内（支持跨午夜）。
     *
     * <p>端点口径：{@code start} 含、{@code end} 不含，即 22:00 整开始免打扰、08:00 整恢复正常。
     */
    public static boolean inQuietHours(LocalTime start, LocalTime end, LocalTime now) {
        LocalTime s = start == null ? DEFAULT_DND_START : start;
        LocalTime e = end == null ? DEFAULT_DND_END : end;
        LocalTime n = now == null ? LocalTime.now() : now;
        if (s.equals(e)) {
            // 起止相同按「全天免打扰」处理：运营真填出这种窗口时，宁可少发也不打扰
            return true;
        }
        if (s.isBefore(e)) {
            return !n.isBefore(s) && n.isBefore(e);
        }
        // 跨午夜：22:00-08:00 命中 [22:00,24:00) 与 [00:00,08:00) 两段
        return !n.isBefore(s) || n.isBefore(e);
    }

    /**
     * 免打扰窗口结束的时刻：跨午夜窗口结束于次日的 end，同一天内结束的（00:00-07:00）结束于当天。
     * 它的返回值直接写进 scheduled_message.due_at。
     */
    public static LocalDateTime quietHoursEndAt(LocalTime start, LocalTime end, LocalDateTime now) {
        LocalTime s = start == null ? DEFAULT_DND_START : start;
        LocalTime e = end == null ? DEFAULT_DND_END : end;
        LocalTime n = now.toLocalTime();
        boolean crossesMidnight = s.isAfter(e);
        // 起止相同的「全天免打扰」没有可用窗口，顺延到次日 08:00，避免这条永远发不出去
        LocalTime useEnd = s.equals(e) ? DEFAULT_DND_END : e;
        LocalDate day = s.equals(e) || (crossesMidnight && !n.isBefore(s))
                ? now.toLocalDate().plusDays(1) : now.toLocalDate();
        // 跨午夜且当前还在前半段（23:00）→ 结束于次日；当前在后半段（02:00）→ 结束于当天
        if (crossesMidnight && n.isBefore(s) && n.isBefore(useEnd)) {
            day = now.toLocalDate();
        }
        return day.atTime(useEnd);
    }

    /** 周末（周六、周日）：营销消息停发日 */
    public static boolean isMarketingBlackoutDay(LocalDate date) {
        DayOfWeek dow = date == null ? LocalDate.now().getDayOfWeek() : date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * 综合判定（U09/U23）：
     * <ol>
     *   <li>偏好关闭 → SUPPRESS(PREF)；</li>
     *   <li>营销 + 周末 → SUPPRESS(WEEKEND)（不顺延，促销改到下周一只会让活动过期）；</li>
     *   <li>营销 + 当日上限已满 → SUPPRESS(DAILY_CAP)；</li>
     *   <li>落在免打扰：营销 → SUPPRESS(QUIET_HOURS)；交易/工单 → 照常 DELIVER；
     *       其余（系统类）→ DEFER 到窗口结束。</li>
     * </ol>
     *
     * @param marketingToday 该用户今日已收到的营销条数
     * @param prefEnabled    该类别 × 站内信渠道的开关
     */
    public static Decision decide(boolean marketing, boolean prefEnabled, boolean dndEnabled,
                                  LocalTime dndStart, LocalTime dndEnd, LocalDateTime now,
                                  int marketingToday) {
        if (now == null) {
            now = LocalDateTime.now();
        }
        if (!prefEnabled) {
            return Decision.suppress(Reason.PREF, "该用户已关闭此类消息的站内信");
        }
        if (marketing && isMarketingBlackoutDay(now.toLocalDate())) {
            return Decision.suppress(Reason.WEEKEND, "营销消息周末停发");
        }
        if (marketing && marketingToday >= DAILY_MARKETING_CAP) {
            return Decision.suppress(Reason.DAILY_CAP,
                    "每位用户每天最多 " + DAILY_MARKETING_CAP + " 条营销消息，今日额度已用完");
        }
        if (dndEnabled && inQuietHours(dndStart, dndEnd, now.toLocalTime())) {
            if (marketing) {
                // 营销不做顺延：凌晨一条促销推送就算补发也只会招烦，直接压掉
                return Decision.suppress(Reason.QUIET_HOURS, "免打扰时段不发送营销消息");
            }
            if (URGENT_BYPASS) {
                return new Decision(Action.DELIVER, null, Reason.NONE, "交易/工单消息允许穿透免打扰");
            }
            return new Decision(Action.DEFER, quietHoursEndAt(dndStart, dndEnd, now), Reason.QUIET_HOURS,
                    "免打扰时段，顺延至窗口结束");
        }
        return Decision.deliver();
    }
}
