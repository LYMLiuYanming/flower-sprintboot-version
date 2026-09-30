package org.liuym.flowerv1springboot.analytics;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.liuym.flowerv1springboot.vo.StatsViews.Anomaly;
import org.liuym.flowerv1springboot.vo.StatsViews.Delta;
import org.liuym.flowerv1springboot.vo.StatsViews.Thresholds;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H14 环比异常判定与 H12 CSV 转义的纯逻辑单测。
 *
 * <p>阈值全部走 StatsViews.Thresholds，测试里不写死数字，调整阈值时只需要核对方向与边界含义。
 */
class StatsViewsThresholdTest {

    @Test
    void 基期为零时不给变化率而不是伪装成下跌() {
        assertNull(StatsViews.changeRate(120, 0));
        assertNull(StatsViews.changeRate(0, 0));
        assertEquals(0.2, StatsViews.changeRate(120, 100), 0.0001);
        assertEquals(-0.2, StatsViews.changeRate(80, 100), 0.0001);

        Anomaly anomaly = StatsViews.anomaly("成交额", 500, 0, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        assertEquals(StatsViews.LEVEL_FLAT, anomaly.level());
        assertEquals("上期无对比数据", anomaly.text());
    }

    @Test
    void 成交额下跌到阈值即标异常上涨走另一档() {
        // 阈值边界按「达到即异常」处理，正好等于阈值也要提示，否则运营会以为一切正常
        Anomaly drop = StatsViews.anomaly("成交额", 85, 100, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        assertEquals(StatsViews.LEVEL_DOWN, drop.level());
        assertTrue(drop.text().contains("异常下滑"), drop.text());

        Anomaly mild = StatsViews.anomaly("成交额", 86, 100, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        assertEquals(StatsViews.LEVEL_FLAT, mild.level());
        assertTrue(mild.text().contains("正常区间"), mild.text());

        Anomaly spike = StatsViews.anomaly("成交额", 170, 100, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        assertEquals(StatsViews.LEVEL_UP, spike.level());
        assertTrue(spike.text().contains("异常上涨"), spike.text());
    }

    @Test
    void 越小越好的指标只在升高时报警() {
        Anomaly worse = StatsViews.anomaly("退款率", 3, 2, Thresholds.NEVER, Thresholds.REFUND_RISE, true);
        assertEquals(StatsViews.LEVEL_DOWN, worse.level());

        // 退款率腰斩是好消息，不能因为「变化很大」就标红
        Anomaly better = StatsViews.anomaly("退款率", 1, 2, Thresholds.NEVER, Thresholds.REFUND_RISE, true);
        assertEquals(StatsViews.LEVEL_FLAT, better.level());
    }

    @Test
    void 只关心单向下行时用NEVER关掉另一侧阈值() {
        Anomaly surge = StatsViews.anomaly("支付转化率", 40, 20, Thresholds.CONVERT_DROP, Thresholds.NEVER, false);
        assertEquals(StatsViews.LEVEL_FLAT, surge.level());

        Anomaly fall = StatsViews.anomaly("支付转化率", 10, 20, Thresholds.CONVERT_DROP, Thresholds.NEVER, false);
        assertEquals(StatsViews.LEVEL_DOWN, fall.level());
    }

    @Test
    void delta与anomaly给出同一份变化率() {
        Delta delta = StatsViews.delta(120, 100, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        Anomaly anomaly = StatsViews.anomaly("成交额", 120, 100, Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        assertEquals(anomaly.changeRate(), delta.changeRate());
        assertEquals(anomaly.level(), delta.level());
    }

    @Test
    void 百分数文本保留一位小数() {
        assertEquals("12.3%", StatsViews.percent(12.345, 1));
        assertEquals("0.0%", StatsViews.percent(0, 1));
    }

    @Test
    void csv转义覆盖逗号引号换行与公式注入() {
        assertEquals("", StatsViews.csvCell(null));
        assertEquals("普通名称", StatsViews.csvCell("普通名称"));
        assertEquals("\"带,逗号\"", StatsViews.csvCell("带,逗号"));
        assertEquals("\"说\"\"满减\"\"了\"", StatsViews.csvCell("说\"满减\"了"));
        // 以 = 开头会被 Excel 当公式执行，必须加引号关掉
        assertEquals("\"=HYPERLINK(\"\"http://x\"\")\"", StatsViews.csvCell("=HYPERLINK(\"http://x\")"));
        assertEquals("换行 变空格", StatsViews.csvCell("换行\n变空格"));
        assertEquals("\" 前导空格\"", StatsViews.csvCell(" 前导空格"));
        assertEquals("成交额,100,", StatsViews.csvRow("成交额", 100, null));
    }
}
