package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GardenPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 10);
    private static final GardenPolicy.Seed ROSE = GardenPolicy.seed("rose").orElseThrow();

    @Test
    void 花苗字典自洽且奖励券码不重复() {
        assertEquals(4, GardenPolicy.SEEDS.size());
        Set<String> codes = GardenPolicy.SEEDS.stream().map(GardenPolicy.Seed::couponCode).collect(Collectors.toSet());
        assertEquals(GardenPolicy.SEEDS.size(), codes.size(), "两种花苗发同一张券会让兑换奖励不可预期");
        GardenPolicy.SEEDS.forEach(seed -> {
            assertTrue(seed.needGrowth() > 0);
            assertTrue(GardenPolicy.seed(seed.code().toUpperCase()).isPresent(), "种子码大小写不敏感");
        });
        assertFalse(GardenPolicy.seed("unknown").isPresent());
        assertFalse(GardenPolicy.seed(null).isPresent());
    }

    @Test
    void 每日三次跨天重置() {
        assertEquals(3, GardenPolicy.remainingWaterToday(0, null, TODAY));
        assertEquals(1, GardenPolicy.remainingWaterToday(2, TODAY, TODAY));
        assertEquals(0, GardenPolicy.remainingWaterToday(3, TODAY, TODAY));
        assertEquals(3, GardenPolicy.remainingWaterToday(3, TODAY.minusDays(1), TODAY), "昨天浇满三次不该影响今天");
    }

    @Test
    void 连续培育天数只在隔天不断() {
        assertEquals(1, GardenPolicy.nextStreak(0, null, TODAY));
        assertEquals(4, GardenPolicy.nextStreak(3, TODAY.minusDays(1), TODAY));
        assertEquals(1, GardenPolicy.nextStreak(9, TODAY.minusDays(3), TODAY), "断了就从 1 重数");
        assertEquals(5, GardenPolicy.nextStreak(5, TODAY, TODAY), "同一天多次浇水不重复加天数");
    }

    @Test
    void 成长加成有上限也有下限() {
        assertEquals(0, GardenPolicy.streakBonus(1));
        assertEquals(6, GardenPolicy.streakBonus(4));
        assertEquals(6, GardenPolicy.streakBonus(30), "连续加成封顶 6");
        assertEquals(GardenPolicy.GROWTH_PER_WATER, GardenPolicy.waterGain(1, 0));
        assertEquals(GardenPolicy.GROWTH_PER_WATER + 6 + 2, GardenPolicy.waterGain(9, 2));
        assertEquals(1, GardenPolicy.waterGain(1, -9), "极端天气也不该让浇水变成负收益");
    }

    @Test
    void 宽限期后按天回落() {
        assertEquals(60, GardenPolicy.effectiveGrowth(60, TODAY.minusDays(2), null, TODAY));
        assertEquals(56, GardenPolicy.effectiveGrowth(60, TODAY.minusDays(3), null, TODAY));
        assertEquals(0, GardenPolicy.effectiveGrowth(3, TODAY.minusDays(30), null, TODAY), "回落到 0 为止");
        // 从没浇过水的新苗以播种日为锚点：闲置 5 天 → 回落 (5-2)×4
        assertEquals(8, GardenPolicy.effectiveGrowth(20, null, TODAY.minusDays(5), TODAY));
        assertEquals(3, GardenPolicy.idleDays(TODAY.minusDays(3), null, TODAY));
        assertEquals(0, GardenPolicy.idleDays(null, null, TODAY));
    }

    @Test
    void 阶段按成熟阈值分档() {
        assertEquals(GardenPolicy.STAGE_SEED, GardenPolicy.stageFor(20, ROSE));
        assertEquals(GardenPolicy.STAGE_SPROUT, GardenPolicy.stageFor(25, ROSE));
        assertEquals(GardenPolicy.STAGE_BUD, GardenPolicy.stageFor(60, ROSE));
        assertEquals(GardenPolicy.STAGE_BLOOM, GardenPolicy.stageFor(100, ROSE));
        // 阈值更高的花苗，同样的成长值仍处在早期阶段
        GardenPolicy.Seed hydra = GardenPolicy.seed("hydra").orElseThrow();
        assertEquals(GardenPolicy.STAGE_SPROUT, GardenPolicy.stageFor(60, hydra));
    }

    @Test
    void 成熟判定与预估口径一致() {
        assertFalse(GardenPolicy.mature(99, ROSE));
        assertTrue(GardenPolicy.mature(100, ROSE));
        assertFalse(GardenPolicy.mature(100, null));
        assertTrue(GardenPolicy.estimate(100, ROSE).contains("已经成熟"));
        assertTrue(GardenPolicy.estimate(0, ROSE).contains("100"));
        // 缺口 94，每天上限 18 → 6 天
        assertEquals("还差 94 点成长值，按时浇水约需 6 天", GardenPolicy.estimate(6, ROSE));
        assertEquals("", GardenPolicy.estimate(6, null));
    }

    @Test
    void 浇水到上限的天数正好成熟() {
        int growth = 0;
        for (int day = 0; day < 6; day++) {
            for (int i = 0; i < GardenPolicy.WATER_PER_DAY; i++) {
                growth += GardenPolicy.waterGain(1, 0);
            }
        }
        assertEquals(GardenPolicy.WATER_PER_DAY * GardenPolicy.GROWTH_PER_WATER * 6, growth);
        assertTrue(GardenPolicy.mature(growth, ROSE));
    }

    @Test
    void 字典里的产地名与阶段常量不漂移() {
        List<String> stages = List.of(GardenPolicy.STAGE_SEED, GardenPolicy.STAGE_SPROUT,
                GardenPolicy.STAGE_BUD, GardenPolicy.STAGE_BLOOM);
        GardenPolicy.SEEDS.forEach(seed -> assertTrue(stages.contains(GardenPolicy.stageFor(seed.needGrowth(), seed))));
        GardenPolicy.SEEDS.forEach(seed -> assertTrue(seed.originName().endsWith("基地"), "产地名要与 flower_origin.name 对齐"));
    }
}
