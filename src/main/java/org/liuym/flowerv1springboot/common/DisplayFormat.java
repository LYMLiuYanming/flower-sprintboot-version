package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 展示层格式化策略（K14 金额千分位 / K15 时间相对化）：
 * 页面、邮件、接口回传文案共用同一套规则，避免 "¥1299.0" 与 "¥1,299.00" 并存。
 * 与前端 site.js 中的 huMoney / huFromNow 保持逐条对齐，改这里请同步改那边。
 */
public final class DisplayFormat {

    /** 绝对时间：列表与详情页 tooltip 统一用这一格式 */
    public static final DateTimeFormatter ABS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private DisplayFormat() {
    }

    /** 金额：千分位 + 固定两位小数，四舍五入。null 视为 0 */
    public static String money(BigDecimal value) {
        BigDecimal v = (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
        String src = v.abs().toPlainString();
        int dot = src.indexOf('.');
        String intPart = groupThousands(src.substring(0, dot));
        return (v.signum() < 0 ? "-" : "") + intPart + src.substring(dot);
    }

    /** 金额简写：整元去尾零（1,299 / 199.5），用于卡片与列表 */
    public static String moneyShort(BigDecimal value) {
        String full = money(value);
        if (!full.endsWith(".00")) {
            return full.endsWith("0") ? full.substring(0, full.length() - 1) : full;
        }
        return full.substring(0, full.length() - 3);
    }

    /** 相对时间：刚刚 / x 分钟前 / x 小时前 / 昨天 HH:mm / x 天前 / 日期 */
    public static String fromNow(LocalDateTime time, LocalDateTime now) {
        if (time == null) {
            return "";
        }
        LocalDateTime ref = (now == null ? LocalDateTime.now() : now);
        long seconds = Duration.between(time, ref).getSeconds();
        if (seconds < 0) {
            // 预约/未来时间不写「-5 分钟前」，直接退化为绝对时间
            return ABS.format(time);
        }
        if (seconds < 10) {
            return "刚刚";
        }
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " 分钟前";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " 小时前";
        }
        if (hours < 48 && time.toLocalDate().isEqual(ref.toLocalDate().minusDays(1))) {
            return "昨天 " + DateTimeFormatter.ofPattern("HH:mm").format(time);
        }
        long days = Duration.between(time, ref).toDays();
        if (days < 7) {
            return days + " 天前";
        }
        return time.getYear() == ref.getYear()
                ? DateTimeFormatter.ofPattern("MM-dd HH:mm").format(time)
                : ABS.format(time);
    }

    public static String fromNow(LocalDateTime time) {
        return fromNow(time, LocalDateTime.now());
    }

    /** 绝对时间，用于 tooltip 与打印场景 */
    public static String abs(LocalDateTime time) {
        return time == null ? "" : ABS.format(time);
    }

    /** 把数字串每三位加一个逗号，供 JS 侧行为对照与单测覆盖 */
    public static String groupThousands(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        StringBuilder out = new StringBuilder();
        int first = digits.length() % 3;
        if (first == 0) {
            first = 3;
        }
        out.append(digits, 0, first);
        for (int i = first; i < digits.length(); i += 3) {
            out.append(',').append(digits, i, i + 3);
        }
        return out.toString();
    }
}
