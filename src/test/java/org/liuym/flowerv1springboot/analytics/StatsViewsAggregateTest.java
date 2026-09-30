package org.liuym.flowerv1springboot.analytics;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.liuym.flowerv1springboot.vo.StatsViews.GeoRow;
import org.liuym.flowerv1springboot.vo.StatsViews.RegionSale;
import org.liuym.flowerv1springboot.vo.StatsViews.TrendPoint;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 看板聚合口径的纯逻辑单测：时间桶补齐与上卷、城市归并、比率换算都不依赖数据库，
 * 起 Spring 上下文跑它们只是浪费时间。
 */
class StatsViewsAggregateTest {

    private static TrendPoint point(String label, String amount, long orders) {
        return new TrendPoint(label, new BigDecimal(amount), orders, null);
    }

    @Test
    void 缺失日期补零且客单价按单量还原() {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        LocalDate from = LocalDate.of(2026, 9, 1);
        // 只写入 9/1 与 9/3，模拟数据库 GROUP BY 不会返回零成交日
        amounts.put("2026-09-01", new BigDecimal("300.00"));
        counts.put("2026-09-01", 2L);
        amounts.put("2026-09-03", new BigDecimal("120.00"));
        counts.put("2026-09-03", 1L);

        List<TrendPoint> daily = StatsViews.fillDailyGaps(from, from.plusDays(3), amounts, counts);

        assertEquals(4, daily.size());
        assertEquals(new BigDecimal("300.00"), daily.get(0).amount());
        assertEquals(new BigDecimal("150.00"), daily.get(0).avgOrderAmount());
        assertEquals(BigDecimal.ZERO, daily.get(1).amount());
        // 空档的单量是 0，客单价必须留空而不是 0 元，否则会被读成「免费送」
        assertNull(daily.get(1).avgOrderAmount());
        assertEquals(0L, daily.get(1).orders());
        assertEquals("2026-09-04", daily.get(3).label());
    }

    @Test
    void 日桶上卷到周取ISO周一() {
        LocalDate monday = LocalDate.of(2026, 9, 21).with(DayOfWeek.MONDAY);
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            amounts.put(monday.plusDays(i).toString(), new BigDecimal("100.00"));
            counts.put(monday.plusDays(i).toString(), 1L);
        }
        amounts.put(monday.plusDays(7).toString(), new BigDecimal("50.00"));
        counts.put(monday.plusDays(7).toString(), 2L);

        List<TrendPoint> daily = StatsViews.fillDailyGaps(monday, monday.plusDays(7), amounts, counts);
        List<TrendPoint> weekly = StatsViews.rollupTrend(daily, StatsViews.UNIT_WEEK);

        assertEquals(2, weekly.size());
        assertEquals(monday.toString(), weekly.get(0).label());
        assertEquals(new BigDecimal("700.00"), weekly.get(0).amount());
        assertEquals(7L, weekly.get(0).orders());
        assertEquals(new BigDecimal("100.00"), weekly.get(0).avgOrderAmount());
        assertEquals(monday.plusDays(7).toString(), weekly.get(1).label());
    }

    @Test
    void 日桶上卷到月与按天直通() {
        List<TrendPoint> daily = List.of(
                point("2026-08-31", "10.00", 1),
                point("2026-09-01", "20.00", 2),
                point("2026-09-30", "30.00", 3));

        List<TrendPoint> monthly = StatsViews.rollupTrend(daily, StatsViews.UNIT_MONTH);
        assertEquals(List.of("2026-08", "2026-09"), monthly.stream().map(TrendPoint::label).toList());
        assertEquals(new BigDecimal("50.00"), monthly.get(1).amount());
        assertEquals(5L, monthly.get(1).orders());
        assertEquals(new BigDecimal("10.00"), monthly.get(1).avgOrderAmount());

        // 按天时不能重排也不能丢点，前端直接拿它画折线
        List<TrendPoint> asDay = StatsViews.rollupTrend(daily, StatsViews.UNIT_DAY);
        assertEquals(3, asDay.size());
        assertEquals("2026-08-31", asDay.get(0).label());
    }

    @Test
    void 非法桶标签被丢弃而不是拖垮整张图() {
        List<TrendPoint> daily = List.of(point("2026-09-01", "10.00", 1), point("not-a-date", "5.00", 1));
        List<TrendPoint> weekly = StatsViews.rollupTrend(daily, StatsViews.UNIT_WEEK);
        assertEquals(1, weekly.size());
        assertNull(StatsViews.bucketLabel("2026-13-45", StatsViews.UNIT_WEEK));
    }

    @Test
    void 地址归并到城市且无法定位的并进其他() {
        List<GeoRow> rows = List.of(
                new GeoRow("上海市徐汇区漕溪北路 100 号", new BigDecimal("300.00"), 12),
                new GeoRow("上海市长宁区某某路 1 号", new BigDecimal("100.00"), 4),
                new GeoRow("浙江省杭州市西湖区某某路 2 号", new BigDecimal("200.00"), 8),
                new GeoRow("某地某个无法识别的地址", new BigDecimal("50.00"), 1));

        List<RegionSale> rank = StatsViews.regionRank(rows, new BigDecimal("650.00"), 3);

        assertEquals(3, rank.size());
        assertEquals("上海", rank.get(0).city());
        assertEquals(2L, rank.get(0).orders());
        assertEquals(16L, rank.get(0).units());
        assertEquals(new BigDecimal("400.00"), rank.get(0).amount());
        assertEquals(61.5, rank.get(0).percent(), 0.05);
        // 城市质心字典命中时才有坐标，前端据此决定能不能画气泡
        assertNotNull(rank.get(0).lng());
        assertNotNull(rank.get(0).lat());
        assertEquals("杭州", rank.get(1).city());
        assertEquals("其他", rank.get(2).city());
        assertNull(rank.get(2).lng());
    }

    @Test
    void 金额为空或零分母不能炸出NaN() {
        assertEquals(0d, StatsViews.shareOf(new BigDecimal("10"), BigDecimal.ZERO));
        assertEquals(0d, StatsViews.shareOf(null, new BigDecimal("10")));
        assertEquals(25d, StatsViews.shareOf(new BigDecimal("25"), new BigDecimal("100")));
        assertEquals(30d, StatsViews.ratePercent(3, 10), 0.001);
        assertEquals(0d, StatsViews.ratePercent(3, 0));

        assertNull(StatsViews.ratio(new BigDecimal("100.00"), 0));
        assertEquals(new BigDecimal("33.33"), StatsViews.ratio(new BigDecimal("100.00"), 3));

        // 没有动销的商品不该被算成「0 天售罄」，它其实是不缺货
        assertNull(StatsViews.coverDays(10, 0, 30));
        assertEquals(5d, StatsViews.coverDays(10, 20, 10), 0.001);
        assertNull(StatsViews.turnoverRate(10, 0));
        assertEquals(2d, StatsViews.turnoverRate(10, 5), 0.001);
    }

    @Test
    void 日期解析容忍带时间的文本() {
        assertEquals(LocalDate.of(2026, 9, 27), StatsViews.parseDate("2026-09-27"));
        assertEquals(LocalDate.of(2026, 9, 27), StatsViews.parseDate("2026-09-27T10:15:00"));
        assertNull(StatsViews.parseDate(null));
        assertNull(StatsViews.parseDate("  "));
        assertNull(StatsViews.parseDate("abc"));
    }
}
