package org.liuym.flowerv1springboot.checkout;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结算策略单测：金额口径只要在这里改动就会被测出来，避免结算页与建单两套账
 */
class CheckoutPolicyTest {

    @Test
    void giftWrapChargesOncePerOrder() {
        assertEquals(new BigDecimal("0.00"), CheckoutPolicy.giftWrapFee(0));
        assertEquals(CheckoutPolicy.GIFT_WRAP_FEE, CheckoutPolicy.giftWrapFee(1));
        // 三束都勾选也只收一次，符合"每单加价"口径（B01）
        assertEquals(CheckoutPolicy.GIFT_WRAP_FEE, CheckoutPolicy.giftWrapFee(3));
    }

    @Test
    void quantityClampsToStockAndPerItemCap() {
        CheckoutPolicy.QuantityClamp over = CheckoutPolicy.clampQuantity(10, 3);
        assertEquals(3, over.quantity());
        assertTrue(over.clamped());
        assertEquals(3, over.maxAllowed());

        CheckoutPolicy.QuantityClamp huge = CheckoutPolicy.clampQuantity(500, 900);
        // 库存充足仍受单行上限约束（B03）
        assertEquals(CheckoutPolicy.MAX_QUANTITY_PER_ITEM, huge.quantity());
        assertTrue(huge.clamped());

        CheckoutPolicy.QuantityClamp soldOut = CheckoutPolicy.clampQuantity(2, 0);
        assertEquals(0, soldOut.quantity());
        CheckoutPolicy.QuantityClamp unknownStock = CheckoutPolicy.clampQuantity(2, null);
        assertEquals(0, unknownStock.maxAllowed());
    }

    @Test
    void pointsDeductionCappedAtTwentyPercentAndTruncated() {
        // 折后 200 元最多抵 40 元，即 4000 积分（B21）
        CheckoutPolicy.PointsClamp clamp = CheckoutPolicy.clampPoints(
                new BigDecimal("80.00"), new BigDecimal("200.00"), 20000);
        assertEquals(new BigDecimal("40.00"), clamp.deduction());
        assertEquals(4000, clamp.points());
        assertTrue(clamp.truncated());
        assertEquals(new BigDecimal("40.00"), clamp.cap());

        // 持有积分不足时按积分为准，同样属于截断
        CheckoutPolicy.PointsClamp byBalance = CheckoutPolicy.clampPoints(
                new BigDecimal("30.00"), new BigDecimal("200.00"), 1500);
        assertEquals(new BigDecimal("15.00"), byBalance.deduction());
        assertTrue(byBalance.truncated());

        CheckoutPolicy.PointsClamp withinRange = CheckoutPolicy.clampPoints(
                new BigDecimal("10.00"), new BigDecimal("200.00"), 20000);
        assertEquals(new BigDecimal("10.00"), withinRange.deduction());
        assertFalse(withinRange.truncated());
    }

    @Test
    void pointsCapNeverGoesNegative() {
        // 券把货款抵平后金额可能为 0 甚至为负，上限必须归零而不是给出负数滑杆
        assertEquals(new BigDecimal("0.00"), CheckoutPolicy.pointsCap(new BigDecimal("-30.00")));
        assertEquals(new BigDecimal("0.00"), CheckoutPolicy.pointsCap(null));
    }

    @Test
    void priceComposesWrapAndFreightOutsideDiscountBase() {
        CheckoutPolicy.Amounts amounts = CheckoutPolicy.price(
                new BigDecimal("100.00"), CheckoutPolicy.GIFT_WRAP_FEE, new BigDecimal("10.00"),
                new BigDecimal("5.00"), new BigDecimal("20.00"), BigDecimal.ZERO);
        // 100 - 25 + 12 包装 + 10 运费
        assertEquals(new BigDecimal("97.00"), amounts.payAmount());
        assertEquals(new BigDecimal("25.00"), amounts.discountTotal());
        assertFalse(amounts.floored());
    }

    @Test
    void priceFloorsToMinimumPayAmount() {
        CheckoutPolicy.Amounts amounts = CheckoutPolicy.price(
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2.00"), new BigDecimal("8.00"), new BigDecimal("5.00"));
        // 折扣超过货款时按比例回收，实付落到下限且明细相加仍等于总折扣
        assertEquals(new BigDecimal("10.00"), amounts.discountTotal());
        assertTrue(amounts.floored());
        assertEquals(CheckoutPolicy.MIN_PAY_AMOUNT, amounts.payAmount());
        assertEquals(amounts.payFloorText(), CheckoutPolicy.minPayText());
        assertTrue(amounts.vipDiscount().compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(amounts.couponDiscount().compareTo(BigDecimal.ZERO) >= 0);
    }

    @Test
    void freightStillChargedWhenGoodsFullyDiscounted() {
        CheckoutPolicy.Amounts amounts = CheckoutPolicy.price(
                new BigDecimal("199.00"), BigDecimal.ZERO, new BigDecimal("10.00"),
                BigDecimal.ZERO, new BigDecimal("199.00"), BigDecimal.ZERO);
        assertEquals(new BigDecimal("10.00"), amounts.payAmount());
    }

    @Test
    void lineErrorsListEveryFailingProduct() {
        String text = CheckoutPolicy.lineErrorText(List.of(
                new CheckoutPolicy.LineIssue("玫瑰恋歌", "已下架"),
                new CheckoutPolicy.LineIssue("向日葵", "库存不足，仅剩 2 件")));
        assertTrue(text.contains("玫瑰恋歌"));
        assertTrue(text.contains("向日葵"));
        assertTrue(text.startsWith("以下商品暂不可购买："));
        assertNull(CheckoutPolicy.lineErrorText(List.of()));
    }

    @Test
    void crossCategoryCouponGetsItsOwnReason() {
        Optional<String> mismatch = CheckoutPolicy.couponMismatch("春日专享券", true, 0);
        assertTrue(mismatch.isPresent());
        assertTrue(mismatch.get().contains("仅限指定分类"));
        assertTrue(CheckoutPolicy.couponMismatch("春日专享券", true, 2).isEmpty());
        assertTrue(CheckoutPolicy.couponMismatch("全场券", false, 0).isEmpty());
    }

    @Test
    void noteCleaningKeepsSingleLineWithinLimit() {
        assertEquals("卡片写妈妈辛苦了", CheckoutPolicy.cleanNote("  卡片写妈妈辛苦了 \n"));
        assertNull(CheckoutPolicy.cleanNote("   "));
        String longText = "字".repeat(CheckoutPolicy.MAX_LINE_NOTE + 20);
        assertEquals(CheckoutPolicy.MAX_LINE_NOTE, CheckoutPolicy.cleanNote(longText).length());
    }

    @Test
    void presetInsertsOnceAndKeepsExistingText() {
        String with = CheckoutPolicy.appendPreset("", "请放门口");
        assertEquals("请放门口", with);
        // 重复插入被忽略，取消由前端第二次点击自己完成（B12）
        assertEquals("请放门口", CheckoutPolicy.appendPreset(with, "请放门口"));
        assertEquals("轻放 请放门口", CheckoutPolicy.appendPreset("轻放", "请放门口"));
        String repeated = CheckoutPolicy.appendPreset("x".repeat(498), "请放门口，轻放勿按门铃");
        assertEquals(500, repeated.length());
    }

    @Test
    void fastestBookableSkipsClosedAndFullSlots() {
        LocalDate today = LocalDate.of(2026, 5, 10);
        Optional<CheckoutPolicy.SlotCandidate> first = CheckoutPolicy.firstBookable(List.of(
                new CheckoutPolicy.SlotCandidate(today, 8, false),
                new CheckoutPolicy.SlotCandidate(today, 9, false),
                new CheckoutPolicy.SlotCandidate(today, 10, true)));
        assertTrue(first.isPresent());
        assertEquals(10, first.get().hour());
        assertTrue(CheckoutPolicy.firstBookable(List.of()).isEmpty());
    }

    @Test
    void highRiskWeatherSuggestsReschedule() {
        LocalDateTime slot = LocalDateTime.of(2026, 5, 10, 14, 0);
        Optional<CheckoutPolicy.Advisory> hot = CheckoutPolicy.weatherAdvisory(
                "risk", "晴", 35, "scheduled", slot);
        assertTrue(hot.isPresent());
        assertTrue(hot.get().text().contains("高温"));
        assertEquals("air_cold", hot.get().suggestDeliveryMethod());
        assertEquals("2026-05-11T14:00", hot.get().suggestSlotAfter());

        Optional<CheckoutPolicy.Advisory> rain = CheckoutPolicy.weatherAdvisory(
                "caution", "阵雨", 22, "scheduled", slot);
        // 预约单遇雨也给改约建议，普通即时配送不额外打扰（B15）
        assertTrue(rain.isPresent());
        assertTrue(rain.get().text().contains("改约"));

        assertTrue(CheckoutPolicy.weatherAdvisory("good", "晴", 20, "next_day", null).isEmpty());
    }

    @Test
    void validationCarriesFieldForFormFocus() {
        CheckoutPolicy.CheckoutValidation e = new CheckoutPolicy.CheckoutValidation(
                "receiverPhone", "手机号格式不正确", List.of("手机号格式不正确"));
        assertEquals("receiverPhone", e.field());
        assertEquals(1, e.issues().size());
        assertEquals(400, e.getCode());
    }
}
