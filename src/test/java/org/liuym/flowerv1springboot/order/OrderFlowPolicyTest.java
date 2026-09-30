package org.liuym.flowerv1springboot.order;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.OrderFlowPolicy;
import org.liuym.flowerv1springboot.model.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单履约口径单测（C01/C02/C03/C06/C07/C08/C19/C26）：
 * 这些都是页面与后端共用的规则，改一处就会在这里被测出来。
 */
class OrderFlowPolicyTest {

    @Test
    void cancelReasonCombinesPresetAndDetailAndNeverExceedsColumn() {
        String picked = OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS, "wrong_pick", null, null);
        assertEquals("选错了，想重新搭配", picked);

        String merged = OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS, "wrong_pick", "想改成向日葵", null);
        assertEquals("选错了，想重新搭配 · 想改成向日葵", merged);

        // 只填自由文本也成立：预设里没有的 code 一律按用户原文落库
        String free = OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS, "unknown", "临时改行程", null);
        assertEquals("临时改行程", free);

        assertNull(OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS, "", "   ", null));

        String longText = OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS, null, "啊".repeat(400), null);
        assertEquals(OrderFlowPolicy.REASON_MAX, longText.length());
    }

    @Test
    void statusGroupsCoverTabSemantics() {
        assertEquals(List.of(OrderStatus.PAID, OrderStatus.PROCESSING), OrderFlowPolicy.statusGroup("toship"));
        assertEquals(List.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED), OrderFlowPolicy.statusGroup("closed"));
        assertEquals(8, OrderFlowPolicy.statusGroup(null).size());
        assertEquals(8, OrderFlowPolicy.statusGroup("all").size());
        assertEquals(List.of(OrderStatus.SHIPPED), OrderFlowPolicy.statusGroup("shipped"));
        assertThrows(IllegalArgumentException.class, () -> OrderFlowPolicy.statusGroup("not-a-status"));
    }

    @Test
    void sortWhitelistFallsBackToLatestFirst() {
        assertEquals("createdAt", OrderFlowPolicy.sortOf("time_asc").get().iterator().next().getProperty());
        assertEquals("ASC", OrderFlowPolicy.sortOf("time_asc").get().iterator().next().getDirection().name());
        assertEquals("payAmount", OrderFlowPolicy.sortOf("amount_desc").get().iterator().next().getProperty());
        // 页面传什么都不作数：未知键一律回落默认，避免 Sort 属性名被自由传入
        assertEquals("createdAt", OrderFlowPolicy.sortOf("drop table").get().iterator().next().getProperty());
        assertEquals("DESC", OrderFlowPolicy.sortOf(null).get().iterator().next().getDirection().name());
    }

    @Test
    void keywordStripsLikeWildcardsAndNormalizesPhone() {
        assertEquals("13800001111", OrderFlowPolicy.keyword("13800001111"));
        assertEquals("", OrderFlowPolicy.keyword(null));
        assertEquals("玫瑰花束", OrderFlowPolicy.keyword("  玫瑰花束  "));
        // 通配符会放大 LIKE 结果集，必须剥掉
        assertEquals("100", OrderFlowPolicy.keyword("1%0_0"));
        assertEquals(40, OrderFlowPolicy.keyword("长".repeat(80)).length());
        assertEquals("13800001111", OrderFlowPolicy.keyword("+86 138-0000-1111"));
    }

    @Test
    void payCountdownOnlyForPendingAndWarnsInLastMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 12, 0);
        LocalDateTime deadline = now.plusMinutes(30);
        assertEquals(1800L, OrderFlowPolicy.paySecondsLeft(OrderStatus.PENDING, deadline, now));
        assertNull(OrderFlowPolicy.paySecondsLeft(OrderStatus.PAID, deadline, now));
        // 已过点不回负数，页面拿到 0 就直接提示刷新
        assertEquals(0L, OrderFlowPolicy.paySecondsLeft(OrderStatus.PENDING, deadline, now.plusHours(2)));
        assertEquals("04:59", OrderFlowPolicy.payCountdownText(299L));
        assertEquals("30:00", OrderFlowPolicy.payCountdownText(1800L));
        assertTrue(OrderFlowPolicy.paySecondsLeft(OrderStatus.PENDING, deadline, now.plusMinutes(25))
                <= OrderFlowPolicy.PAY_WARN_SECONDS);
    }

    @Test
    void reviewGuideStartsAfterNDaysOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 30, 12, 0);
        assertNull(OrderFlowPolicy.reviewGuide(now.minusDays(2), now, 3, 2));
        String guide = OrderFlowPolicy.reviewGuide(now.minusDays(4), now, 3, 2);
        assertNotNull(guide);
        assertTrue(guide.contains("4 天前"));
        assertTrue(guide.contains("2 束待评价"));
        assertNull(OrderFlowPolicy.reviewGuide(null, now, 3, 2));
    }

    @Test
    void rangeErrorGuardsAgainstReversedDates() {
        LocalDateTime now = LocalDateTime.now();
        assertNotNull(OrderFlowPolicy.rangeError(now, now.minusDays(3)));
        assertNull(OrderFlowPolicy.rangeError(now, null));
        assertNull(OrderFlowPolicy.rangeError(null, null));
    }

    @Test
    void endOfDayKeepsOrdersPlacedThatNight() {
        LocalDateTime morning = LocalDateTime.of(2026, 9, 30, 7, 30);
        assertEquals(LocalDateTime.of(2026, 9, 30, 23, 59, 59), OrderFlowPolicy.endOfDay(morning));
        assertNull(OrderFlowPolicy.endOfDay(null));
    }

    @Test
    void refundPresetsMatchOrderAfterSaleScenarios() {
        List<String> codes = OrderFlowPolicy.REFUND_REASONS.stream().map(OrderFlowPolicy.Reason::code).toList();
        assertTrue(codes.contains("withered"));
        assertTrue(codes.contains("not_arrived"));
        // 取消与退款的候选互相独立，不能共用一份文案
        assertNotEquals(codes, OrderFlowPolicy.CANCEL_REASONS.stream().map(OrderFlowPolicy.Reason::code).toList());
    }
}
