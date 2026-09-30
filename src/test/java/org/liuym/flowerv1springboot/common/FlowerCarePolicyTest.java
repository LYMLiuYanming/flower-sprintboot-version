package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowerCarePolicyTest {

    @Test
    void 高温判定风险并推荐冷链() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(35, 40, "晴", "≤3");
        assertEquals(FlowerCarePolicy.LEVEL_RISK, advice.level());
        assertTrue(advice.recommendColdChain());
        assertTrue(advice.headline().contains("35"));
        assertFalse(advice.tips().isEmpty());
    }

    @Test
    void 偏热只提醒不改变配送建议() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(29, 55, "多云", "≤3");
        assertEquals(FlowerCarePolicy.LEVEL_CAUTION, advice.level());
        assertFalse(advice.recommendColdChain());
    }

    @Test
    void 低温同样按风险处理() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(1, 60, "小雪", "4-5");
        assertEquals(FlowerCarePolicy.LEVEL_RISK, advice.level());
        assertTrue(advice.tips().stream().anyMatch(t -> t.contains("保温")));
    }

    @Test
    void 降雨叠加提醒且不高估风险() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(22, 88, "阵雨", "≤3");
        assertEquals(FlowerCarePolicy.LEVEL_CAUTION, advice.level());
        assertTrue(advice.headline().contains("阵雨"));
        assertTrue(advice.tips().stream().anyMatch(t -> t.contains("雨天配送")));
    }

    @Test
    void 干燥与大风各补一条建议() {
        FlowerCarePolicy.Advice dry = FlowerCarePolicy.advise(24, 30, "晴", "≤3");
        assertTrue(dry.tips().stream().anyMatch(t -> t.contains("干燥")));
        FlowerCarePolicy.Advice windy = FlowerCarePolicy.advise(24, 60, "晴", "6-7");
        assertTrue(windy.tips().stream().anyMatch(t -> t.contains("风力较大")));
    }

    @Test
    void 适宜天气给出正向结论() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(22, 60, "晴", "≤3");
        assertEquals(FlowerCarePolicy.LEVEL_GOOD, advice.level());
        assertEquals(1, advice.tips().size());
        assertTrue(advice.tips().get(0).contains("适宜"));
    }

    @Test
    void 缺失字段不抛异常也不夸大() {
        FlowerCarePolicy.Advice advice = FlowerCarePolicy.advise(null, null, null, null);
        assertEquals(FlowerCarePolicy.LEVEL_GOOD, advice.level());
        assertEquals("天气适宜", advice.headline());
    }

    @Test
    void 风力文本取区间上限() {
        assertEquals(3, FlowerCarePolicy.windLevel("≤3"));
        assertEquals(5, FlowerCarePolicy.windLevel("4-5"));
        assertEquals(7, FlowerCarePolicy.windLevel("6-7级"));
        assertEquals(0, FlowerCarePolicy.windLevel(null));
        assertEquals(0, FlowerCarePolicy.windLevel("未知"));
    }

    @Test
    void 雨天雪天冰雹都算降水() {
        assertTrue(FlowerCarePolicy.isRainy("雷阵雨"));
        assertTrue(FlowerCarePolicy.isRainy("中雪"));
        assertTrue(FlowerCarePolicy.isRainy("冰雹"));
        assertFalse(FlowerCarePolicy.isRainy("多云"));
        assertFalse(FlowerCarePolicy.isRainy(null));
    }

    @Test
    void 晴天补光照雨天补水润极端天气扣分() {
        assertEquals(2, FlowerCarePolicy.growth("晴", 24).sun());
        assertEquals(0, FlowerCarePolicy.growth("晴", 24).water());
        assertEquals(2, FlowerCarePolicy.growth("小雨", 20).water());
        FlowerCarePolicy.Growth hot = FlowerCarePolicy.growth("晴", 36);
        assertEquals(2, hot.stress());
        assertEquals(0, hot.bonus(), "高温下光照加成应被受压抵消");
        assertEquals(-2, FlowerCarePolicy.growth(null, 0).bonus());
        assertTrue(FlowerCarePolicy.growth("晴", 24).note().contains("光照"));
    }
}
