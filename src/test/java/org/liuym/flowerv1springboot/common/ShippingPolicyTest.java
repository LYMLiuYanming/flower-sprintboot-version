package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ShippingPolicyTest {

    @Test
    void freightIsBasePlusPerKg() {
        BigDecimal freight = ShippingPolicy.freightOf(ShippingPolicy.AIR_COLD,
                new BigDecimal("100.00"), new BigDecimal("3.00"));
        // 38 首重 + 6 × 3kg
        assertEquals(new BigDecimal("56.00"), freight);
    }

    @Test
    void freeOverThresholdDropsFreightToZero() {
        BigDecimal goods = new BigDecimal("199.00");
        assertEquals(BigDecimal.ZERO.setScale(2),
                ShippingPolicy.freightOf(ShippingPolicy.NEXT_DAY, goods, new BigDecimal("5.00")));
        assertEquals(new BigDecimal("10.00"),
                ShippingPolicy.freightOf(ShippingPolicy.NEXT_DAY, new BigDecimal("198.99"), new BigDecimal("0.00")));
        // 闪送与自提不参与包邮
        assertEquals(new BigDecimal("18.00"),
                ShippingPolicy.freightOf(ShippingPolicy.SAME_CITY, new BigDecimal("9999.00"), BigDecimal.ONE));
    }

    @Test
    void originalFreightKeepsDiscountAmountVisible() {
        BigDecimal origin = ShippingPolicy.originalFreight(ShippingPolicy.SEA_FRESH, new BigDecimal("4.00"));
        assertEquals(new BigDecimal("22.00"), origin);
        assertEquals(BigDecimal.ZERO.setScale(2),
                ShippingPolicy.freightOf(ShippingPolicy.SEA_FRESH, new BigDecimal("900.00"), new BigDecimal("4.00")));
    }

    @Test
    void weightParsingCoversSeedFormats() {
        assertEquals(new BigDecimal("1.50"), ShippingPolicy.unitWeightKg("1.5kg"));
        assertEquals(new BigDecimal("0.80"), ShippingPolicy.unitWeightKg("800g"));
        assertEquals(new BigDecimal("1.20"), ShippingPolicy.unitWeightKg("约 1.2 公斤"));
        assertEquals(ShippingPolicy.DEFAULT_UNIT_KG, ShippingPolicy.unitWeightKg(null));
        assertEquals(ShippingPolicy.DEFAULT_UNIT_KG, ShippingPolicy.unitWeightKg("精选花束"));
        // 越界值回落，避免录入 0.01kg 或 100kg 把运费算飞
        assertEquals(ShippingPolicy.DEFAULT_UNIT_KG, ShippingPolicy.unitWeightKg("120kg"));
    }

    @Test
    void totalWeightScalesToTwoDecimals() {
        assertEquals(new BigDecimal("3.60"),
                ShippingPolicy.totalWeightKg(List.of(new BigDecimal("1.20"), new BigDecimal("2.40"))));
        assertEquals(new BigDecimal("0.00"), ShippingPolicy.totalWeightKg(null));
    }

    @Test
    void etaFollowsMethodUnlessScheduled() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 10, 0);
        LocalDateTime slot = LocalDateTime.of(2026, 10, 1, 18, 30);
        assertEquals(now.plusHours(2), ShippingPolicy.arriveAt(ShippingPolicy.SAME_CITY, now, null));
        assertEquals(slot.plusHours(1), ShippingPolicy.arriveAt(ShippingPolicy.SCHEDULED, now, slot));
        assertNull(ShippingPolicy.arriveAt(ShippingPolicy.SCHEDULED, now, null));
    }

    @Test
    void slotValidationCoversBusinessWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 27, 10, 0);
        LocalDateTime ok = LocalDateTime.of(2026, 9, 28, 15, 0);
        assertEquals(ok, ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, ok));
        // 提前量不足
        assertThrows(BusinessException.class,
                () -> ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, LocalDateTime.of(2026, 9, 27, 10, 30)));
        // 超出 08:00–21:00
        assertThrows(BusinessException.class,
                () -> ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, LocalDateTime.of(2026, 9, 28, 22, 0)));
        // 超出可约窗口
        assertThrows(BusinessException.class,
                () -> ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, LocalDateTime.of(2026, 12, 28, 15, 0)));
        // 非预约方式不需要时段
        assertNull(ShippingPolicy.requireSlot(ShippingPolicy.NEXT_DAY, now, null));
        assertThrows(BusinessException.class, () -> ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, null));
    }

    @Test
    void resolveFallsBackToDefaultForUnknownCode() {
        assertEquals(ShippingPolicy.DEFAULT, ShippingPolicy.resolve(null));
        assertEquals(ShippingPolicy.DEFAULT, ShippingPolicy.resolve("  "));
        assertEquals(ShippingPolicy.DEFAULT, ShippingPolicy.resolve("helicopter"));
        assertEquals(ShippingPolicy.AIR_COLD, ShippingPolicy.resolve("AIR_COLD"));
        assertEquals(6, ShippingPolicy.METHODS.size());
    }

    @Test
    void parseSlotAcceptsDateTimeLocalFormat() {
        assertEquals(LocalDateTime.of(2026, 9, 28, 15, 30), ShippingPolicy.parseSlot("2026-09-28T15:30"));
        assertEquals(LocalDateTime.of(2026, 9, 28, 15, 30), ShippingPolicy.parseSlot("2026-09-28T15:30:12"));
        assertEquals("2026-09-28T15:30", ShippingPolicy.slotText(LocalDateTime.of(2026, 9, 28, 15, 30, 12, 0)));
        assertNull(ShippingPolicy.parseSlot(""));
        assertThrows(BusinessException.class, () -> ShippingPolicy.parseSlot("28/09/2026 15:30"));
    }

    @Test
    void slotHoursCoverTheBookableWindow() {
        List<Integer> hours = ShippingPolicy.slotHours();
        assertEquals(14, hours.size());
        assertEquals(ShippingPolicy.SLOT_FIRST_HOUR, hours.get(0).intValue());
        assertEquals(ShippingPolicy.SLOT_LAST_HOUR, hours.get(hours.size() - 1).intValue());
        assertEquals("09:00–10:00", ShippingPolicy.slotHourText(9));
        assertEquals("09 月 28 日", ShippingPolicy.slotDateText(LocalDateTime.of(2026, 9, 28, 9, 0)));
        assertEquals("", ShippingPolicy.slotDateText(null));
        // 时段边界仍受 requireSlot 约束：21:30 超出窗口
        LocalDateTime now = LocalDateTime.of(2026, 9, 26, 8, 0);
        assertEquals(LocalDateTime.of(2026, 9, 28, 21, 0),
                ShippingPolicy.requireSlot(ShippingPolicy.SCHEDULED, now, LocalDateTime.of(2026, 9, 28, 21, 0)));
        assertThrows(BusinessException.class, () -> ShippingPolicy.requireSlot(
                ShippingPolicy.SCHEDULED, now, LocalDateTime.of(2026, 9, 28, 21, 30)));
    }
}
