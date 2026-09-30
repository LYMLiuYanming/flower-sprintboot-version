package org.liuym.flowerv1springboot.garden;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.GardenPolicy;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预报日期的叫法（I08）：高德把「今天」排在第几行并不稳定（深夜会先把明天当首行给出），
 * 所以标签必须由服务端按日期比对得出，页面按下标取会把明天写成今天。
 */
class GardenForecastLabelTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    @Test
    void 相对日期叫法按日期差而不是下标() {
        assertEquals("今天", GardenPolicy.forecastDayLabel("2026-09-30", TODAY));
        assertEquals("明天", GardenPolicy.forecastDayLabel("2026-10-01", TODAY));
        assertEquals("后天", GardenPolicy.forecastDayLabel("2026-10-02", TODAY));
        assertEquals("昨天", GardenPolicy.forecastDayLabel("2026-09-29", TODAY));
        // 同年更远的日子回到「周几」，跨年才退回完整日期
        assertEquals("周三", GardenPolicy.forecastDayLabel("2026-10-07", TODAY));
        assertEquals("1 月 3 日", GardenPolicy.forecastDayLabel("2027-01-03", TODAY));
    }

    @Test
    void 异常日期不能把页面带崩() {
        // 预报偶发缺字段或写成别的格式：宁可原样显示也不能抛异常，天气不该打断花田首屏
        assertEquals("2026年10月1日", GardenPolicy.forecastDayLabel("2026年10月1日", TODAY));
        assertEquals("", GardenPolicy.forecastDayLabel(null, TODAY));
        assertEquals("", GardenPolicy.forecastDayLabel("  ", TODAY));
        assertEquals("9 月 30 日", GardenPolicy.forecastDayLabel("2026-09-30", null));
        assertNull(GardenPolicy.parseDate("2026年10月1日"));
        assertNull(GardenPolicy.parseDate(null));
        assertFalse(GardenPolicy.isSameDay("2026年10月1日", TODAY));
        assertFalse(GardenPolicy.isSameDay(null, TODAY));
        assertTrue(GardenPolicy.isSameDay("2026-09-30", TODAY));
    }

    @Test
    void 预报取四条对应今日加未来三日() {
        assertEquals(4, GardenPolicy.FORECAST_ROWS);
    }
}
