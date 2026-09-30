package org.liuym.flowerv1springboot.map;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.FlowerCarePolicy;
import org.liuym.flowerv1springboot.common.GardenPolicy;

import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多块地、转赠确认态与天气驱动的养护建议（I10/I11/I13）。
 *
 * <p>这三条都是「用户能直接感到」的规则：谁能开第二块地、花现在归谁、今天该不该多浇，
 * 一旦散落到页面或服务里就会出现两套说法，所以集中在 GardenPolicy 里一次测清。
 */
class GardenSlotAndGiftTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 10);

    @Test
    void 会员才有多块地() {
        assertEquals(1, GardenPolicy.slotsFor(false));
        assertEquals(2, GardenPolicy.slotsFor(true));
        assertTrue(GardenPolicy.canOpenSlot(1, false));
        assertFalse(GardenPolicy.canOpenSlot(2, false), "非会员开第二块地必须被拒");
        assertTrue(GardenPolicy.canOpenSlot(2, true));
        assertFalse(GardenPolicy.canOpenSlot(0, true), "编号从 1 开始");
        assertFalse(GardenPolicy.canOpenSlot(3, true), "再多一块也不给开");
    }

    @Test
    void 锁定提示说清解锁条件() {
        assertEquals("1 号地", GardenPolicy.slotName(1));
        assertEquals("2 号地", GardenPolicy.slotName(2));
        assertTrue(GardenPolicy.lockedNote(2).contains("会员"));
        assertTrue(GardenPolicy.lockedNote(2).contains(String.valueOf(GardenPolicy.SLOTS_VIP)));
    }

    @Test
    void 两块地的每日次数互不影响() {
        // 同一套规则分别作用在两块地上：A 地浇满、B 地仍是三次
        int slotA = GardenPolicy.remainingWaterToday(3, TODAY, TODAY);
        int slotB = GardenPolicy.remainingWaterToday(0, null, TODAY);
        assertEquals(0, slotA);
        assertEquals(GardenPolicy.WATER_PER_DAY, slotB);
    }

    @Test
    void 转赠确认态文案齐全() {
        assertEquals("待对方确认", GardenPolicy.giftStateLabel(GardenPolicy.GIFT_PENDING));
        assertEquals("对方已收下", GardenPolicy.giftStateLabel(GardenPolicy.GIFT_ACCEPTED));
        assertTrue(GardenPolicy.giftStateLabel(GardenPolicy.GIFT_DECLINED).contains("退回"));
        assertTrue(GardenPolicy.giftStateLabel(GardenPolicy.GIFT_CANCELED).contains("撤回"));
        // 老数据没有确认态，返回空串而不是「null」
        assertEquals("", GardenPolicy.giftStateLabel(null));
        assertEquals("", GardenPolicy.giftStateLabel("未知状态"));
    }

    @Test
    void 养护建议按花苗习性与天气分化() {
        GardenPolicy.Seed hydra = GardenPolicy.seed("hydra").orElseThrow();
        GardenPolicy.Seed rose = GardenPolicy.seed("rose").orElseThrow();
        FlowerCarePolicy.Growth rain = FlowerCarePolicy.growth("小雨", 20);
        FlowerCarePolicy.Growth sunny = FlowerCarePolicy.growth("晴", 24);
        FlowerCarePolicy.Growth scorched = FlowerCarePolicy.growth("晴", 36);

        assertTrue(GardenPolicy.careNote(hydra, rain).contains("绣球"));
        assertTrue(GardenPolicy.careNote(rose, sunny).contains("玫瑰"));
        assertTrue(GardenPolicy.careNote(rose, scorched).contains("早晚"));
        assertTrue(GardenPolicy.careNote(null, sunny).contains("光照"));
        // 没有天气数据时也要给得出可执行的话，不能空着
        assertTrue(GardenPolicy.careNote(rose, null).contains("暂无今日天气"));
    }

    @Test
    void 极端天气下浇水收益仍有下限() {
        Optional<GardenPolicy.Seed> sun = GardenPolicy.seed("sun");
        FlowerCarePolicy.Growth stress = FlowerCarePolicy.growth("晴", 40);
        int gain = GardenPolicy.waterGain(1, stress.bonus());
        assertTrue(gain >= 1, sun.isPresent() ? "最低也要涨 1 点" : "");
        assertEquals(80, sun.get().needGrowth(), "向日葵是最快的品种，成熟阈值应最低");
    }
}
