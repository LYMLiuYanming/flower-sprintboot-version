package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.FlowerCarePolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预报与养护文案（I08 + I10）：把「未来三天的天况」翻译成配送决策，
 * 并把同一份天气同时用到「花田成长」和「回家怎么养」两句话上。
 */
class WeatherForecastAdviceTest {

    private static AmapClient.Forecast.Cast cast(String date, String day, String night, Integer dayTemp, Integer nightTemp) {
        return new AmapClient.Forecast.Cast(date, day, night, dayTemp, nightTemp);
    }

    @Test
    void 逐日预报给出等级与配送结论() {
        List<FlowerCarePolicy.ForecastDay> days = FlowerCarePolicy.forecastAdvice(List.of(
                cast("2026-09-28", "晴", "晴", 35, 24),
                cast("2026-09-29", "阵雨", "阴", 22, 17),
                cast("2026-09-30", "多云", "多云", 24, 12)));

        assertEquals(3, days.size());
        assertEquals(FlowerCarePolicy.LEVEL_RISK, days.get(0).level());
        assertTrue(days.get(0).recommendColdChain(), "35℃ 要提示冷链");
        assertTrue(days.get(0).deliveryNote().contains("空运冷链"));

        assertEquals(FlowerCarePolicy.LEVEL_CAUTION, days.get(1).level());
        assertTrue(days.get(1).deliveryNote().contains("降水"));

        // 24/12 温差刚好 12 度，提醒结露与醒花
        assertTrue(days.get(2).deliveryNote().contains("温差"));
        assertEquals("2026-09-28", days.get(0).date());
    }

    @Test
    void 适宜与缺失字段都不抛错() {
        List<FlowerCarePolicy.ForecastDay> days = FlowerCarePolicy.forecastAdvice(
                List.of(cast("2026-09-28", "晴", "晴", 22, 15), cast("2026-09-29", null, null, null, null)));
        assertTrue(days.get(0).deliveryNote().contains("适宜配送"));
        // 字段全空的cast：只给出兜底文案，等级留在 good
        assertEquals(FlowerCarePolicy.LEVEL_GOOD, days.get(1).level());
        assertTrue(days.get(1).deliveryNote().contains("适宜配送"));
        assertTrue(FlowerCarePolicy.forecastAdvice(null).isEmpty());
    }

    @Test
    void 低温雪天与高温的配送结论互不混用() {
        FlowerCarePolicy.Advice cold = FlowerCarePolicy.advise(0, 60, "小雪", "≤3");
        assertTrue(FlowerCarePolicy.deliveryNote(0, -3, "小雪", cold).contains("保温"));
        FlowerCarePolicy.Advice hot = FlowerCarePolicy.advise(33, 40, "晴", "≤3");
        assertTrue(FlowerCarePolicy.deliveryNote(33, 25, "晴", hot).contains("18:00"));
        FlowerCarePolicy.Advice warm = FlowerCarePolicy.advise(29, 50, "多云", "≤3");
        assertTrue(FlowerCarePolicy.deliveryNote(29, 22, "多云", warm).contains("偏热"));
    }

    @Test
    void 养护文案按温度湿度降水与风分开给() {
        assertTrue(FlowerCarePolicy.careNote(35, 40, "晴", "≤3").contains("每天换水"));
        assertTrue(FlowerCarePolicy.careNote(1, 60, "多云", "≤3").contains("8℃"));
        assertTrue(FlowerCarePolicy.careNote(24, 20, "晴", "≤3").contains("喷雾"));
        assertTrue(FlowerCarePolicy.careNote(20, 80, "中雨", "≤3").contains("水位"));
        assertTrue(FlowerCarePolicy.careNote(20, 60, "晴", "6-7").contains("大风"));
        assertTrue(FlowerCarePolicy.careNote(22, 60, "晴", "≤3").contains("隔天换水"));
        assertTrue(FlowerCarePolicy.careNote(null, null, null, null).contains("隔天换水"));
    }

    @Test
    void 雨热同时在线时先说风险最高的那条() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(34, 90, "雷阵雨", "≤3");
        assertTrue(advice.recommendColdChain());
        // 预报文案优先落在冷链上，而不是只提醒带伞
        assertTrue(FlowerCarePolicy.deliveryNote(34, 26, "雷阵雨", advice).contains("空运冷链"));
        assertFalse(FlowerCarePolicy.careNote(34, 90, "雷阵雨", "≤3").contains("水位"), "高温优先于降雨口径");
    }
}
