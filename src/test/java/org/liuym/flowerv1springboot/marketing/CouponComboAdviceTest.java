package org.liuym.flowerv1springboot.marketing;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CampaignPolicy;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E09 券与积分的组合建议：积分上限按「券抵扣后的金额」算，
 * 所以「先券还是先积分」必须三条方案都算一遍才知道哪个更省
 */
class CouponComboAdviceTest {

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), "期望 " + expected + "，实际 " + actual);
    }

    private static long bestCount(List<CampaignPolicy.ComboOption> options) {
        return options.stream().filter(CampaignPolicy.ComboOption::best).count();
    }

    @Test
    void couponThenPointsIsCheaperThanEitherAlone() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("200.00"), new BigDecimal("30.00"), 10000);
        assertEquals(3, options.size());
        CampaignPolicy.ComboOption combo = options.get(0);
        assertMoney("30.00", combo.coupon());
        // 用券后金额剩 170，积分上限随之降到 34，而不是整单的 40
        assertMoney("34.00", combo.points());
        assertMoney("64.00", combo.total());
        assertTrue(combo.best());
        assertMoney("30.00", options.get(1).total());
        assertMoney("40.00", options.get(2).total());
        assertEquals(1, bestCount(options));
        assertTrue(CampaignPolicy.comboSummary(options).startsWith("最划算：券 + 积分"));
    }

    /** 券已经把整单抵完时积分抵不出钱，两条方案打平就都标成最优 */
    @Test
    void couponCoveringWholeOrderTiesWithCouponOnly() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("100.00"), new BigDecimal("100.00"), 10000);
        assertMoney("0.00", options.get(0).points());
        assertMoney("100.00", options.get(0).total());
        assertMoney("100.00", options.get(1).total());
        assertEquals(2, bestCount(options));
    }

    @Test
    void couponIsClampedToOrderAmount() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("50.00"), new BigDecimal("80.00"), 0);
        assertMoney("50.00", options.get(0).coupon());
        assertMoney("50.00", options.get(0).total());
        assertMoney("0.00", options.get(2).points());
    }

    @Test
    void pointsOnlyWhenNoCouponChosen() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("200.00"), null, 3000);
        assertMoney("0.00", options.get(0).coupon());
        assertMoney("30.00", options.get(2).points());
        assertTrue(options.get(2).best());
    }

    /** 一分都省不到时不给「最划算」的建议，避免页面出现「可省 0 元」 */
    @Test
    void nothingToSaveHasNoBestOption() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("200.00"), BigDecimal.ZERO, 0);
        assertEquals(0, bestCount(options));
        assertEquals("本单暂无可用减免", CampaignPolicy.comboSummary(options));
        assertEquals("本单暂无可用减免", CampaignPolicy.comboSummary(List.of()));
    }

    /** 积分不足时按余额给建议，不假装能抵满上限 */
    @Test
    void scarcePointsAreHonoured() {
        List<CampaignPolicy.ComboOption> options =
                CampaignPolicy.comboOptions(new BigDecimal("1000.00"), new BigDecimal("100.00"), 500);
        assertMoney("5.00", options.get(0).points());
        assertMoney("105.00", options.get(0).total());
        assertMoney("5.00", options.get(2).total());
        assertTrue(options.get(0).best());
    }
}
