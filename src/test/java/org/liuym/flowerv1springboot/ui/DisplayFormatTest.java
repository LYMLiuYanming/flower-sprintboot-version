package org.liuym.flowerv1springboot.ui;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.DisplayFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * K14 金额千分位 / K15 相对时间：前端 site.js 与后端展示策略必须给出同一串文本，
 * 这里锁定边界值，防止后续有人把 .00 尾巴又写回列表页。
 */
class DisplayFormatTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 5, 20, 14, 30);

    @Test
    void moneyKeepsTwoDecimalsAndGroupsThousands() {
        assertEquals("0.00", DisplayFormat.money(BigDecimal.ZERO));
        assertEquals("199.00", DisplayFormat.money(new BigDecimal("199")));
        assertEquals("1,299.00", DisplayFormat.money(new BigDecimal("1299")));
        assertEquals("1,299.50", DisplayFormat.money(new BigDecimal("1299.5")));
        assertEquals("12,345,678.90", DisplayFormat.money(new BigDecimal("12345678.9")));
    }

    @Test
    void moneyRoundsHalfUpAndKeepsSign() {
        assertEquals("0.13", DisplayFormat.money(new BigDecimal("0.125")));
        assertEquals("1,000.01", DisplayFormat.money(new BigDecimal("1000.006")));
        assertEquals("-59.90", DisplayFormat.money(new BigDecimal("-59.9")));
        assertEquals("0.00", DisplayFormat.money(null));
    }

    @Test
    void moneyShortDropsTailZerosOnly() {
        assertEquals("1,299", DisplayFormat.moneyShort(new BigDecimal("1299.00")));
        assertEquals("1,299.5", DisplayFormat.moneyShort(new BigDecimal("1299.50")));
        assertEquals("0.05", DisplayFormat.moneyShort(new BigDecimal("0.05")));
        assertEquals("10", DisplayFormat.moneyShort(new BigDecimal("10")));
        assertEquals("1,000.1", DisplayFormat.moneyShort(new BigDecimal("1000.10")));
    }

    @Test
    void fromNowBucketizesByElapsedSeconds() {
        assertEquals("刚刚", DisplayFormat.fromNow(NOW.minusSeconds(5), NOW));
        assertEquals("30 秒前", DisplayFormat.fromNow(NOW.minusSeconds(30), NOW));
        assertEquals("59 秒前", DisplayFormat.fromNow(NOW.minusSeconds(59), NOW));
        assertEquals("1 分钟前", DisplayFormat.fromNow(NOW.minusMinutes(1), NOW));
        assertEquals("59 分钟前", DisplayFormat.fromNow(NOW.minusMinutes(59), NOW));
        assertEquals("1 小时前", DisplayFormat.fromNow(NOW.minusHours(1), NOW));
        assertEquals("23 小时前", DisplayFormat.fromNow(NOW.minusHours(23), NOW));
    }

    @Test
    void fromNowSwitchesToDayLabels() {
        // 昨天走「昨天 HH:mm」，一周内走「x 天前」
        assertEquals("昨天 09:05", DisplayFormat.fromNow(NOW.minusDays(1).withHour(9).withMinute(5), NOW));
        assertEquals("2 天前", DisplayFormat.fromNow(NOW.minusDays(2), NOW));
        assertEquals("6 天前", DisplayFormat.fromNow(NOW.minusDays(6), NOW));
    }

    @Test
    void fromNowFallsBackToAbsoluteBeyondWeekAndFuture() {
        assertEquals("05-12 14:30", DisplayFormat.fromNow(NOW.minusDays(8), NOW));
        assertEquals("2025-12-01 14:30", DisplayFormat.fromNow(NOW.minusDays(170), NOW));
        // 预约/未来时间不出现「-5 分钟前」，退化为绝对时间
        assertEquals("2026-05-20 16:30", DisplayFormat.fromNow(NOW.plusHours(2), NOW));
        assertEquals("", DisplayFormat.fromNow(null, NOW));
    }

    @Test
    void absoluteTimeIsStableAndPadsWithZero() {
        assertEquals("2026-05-09 08:07", DisplayFormat.abs(LocalDateTime.of(2026, 5, 9, 8, 7)));
        assertEquals("", DisplayFormat.abs(null));
    }

    @Test
    void groupThousandsRespectsLeadingGroupSize() {
        assertEquals("123", DisplayFormat.groupThousands("123"));
        assertEquals("1,234", DisplayFormat.groupThousands("1234"));
        assertEquals("12,345", DisplayFormat.groupThousands("12345"));
        assertEquals("123,456", DisplayFormat.groupThousands("123456"));
        assertEquals("1,234,567", DisplayFormat.groupThousands("1234567"));
    }
}
