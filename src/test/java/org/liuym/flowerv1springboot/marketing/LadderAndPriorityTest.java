package org.liuym.flowerv1springboot.marketing;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CampaignPolicy;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E01 满减阶梯取最优档 + E14 满减与券并存的优先级
 */
class LadderAndPriorityTest {

    private static final String LADDER = "199:20,399:60,599:100";

    private static CampaignPolicy.Tier tier(String threshold, String reduce) {
        return new CampaignPolicy.Tier(new BigDecimal(threshold), new BigDecimal(reduce));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual, "金额不应为空");
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), "期望 " + expected + "，实际 " + actual);
    }

    @Test
    void parsesTiersAndDropsDirtyEntries() {
        List<CampaignPolicy.Tier> tiers = CampaignPolicy.parseLadder("abc,199:20,200:-5,,399:60");
        assertEquals(2, tiers.size());
        assertMoney("199.00", tiers.get(0).threshold());
        assertMoney("60.00", tiers.get(1).reduce());
        assertTrue(CampaignPolicy.parseLadder(null).isEmpty());
        assertTrue(CampaignPolicy.parseLadder("   ").isEmpty());
    }

    @Test
    void picksTheReachableTierWithBiggestReduce() {
        assertMoney("20.00", CampaignPolicy.bestTier(LADDER, new BigDecimal("199.00")).reduce());
        assertMoney("60.00", CampaignPolicy.bestTier(LADDER, new BigDecimal("400.00")).reduce());
        assertMoney("100.00", CampaignPolicy.bestTier(LADDER, new BigDecimal("1999.00")).reduce());
        assertNull(CampaignPolicy.bestTier(LADDER, new BigDecimal("198.99")));
        assertNull(CampaignPolicy.bestTier(LADDER, BigDecimal.ZERO));
    }

    /** 同一门槛重复配置时取减免更大的那档，用户不该因为后台手滑而少减 */
    @Test
    void sameThresholdPrefersLargerReduce() {
        CampaignPolicy.Tier best = CampaignPolicy.bestTier("199:20,199:30", new BigDecimal("199.00"));
        assertMoney("30.00", best.reduce());
    }

    @Test
    void suggestsNextTierAndLowestThreshold() {
        assertMoney("100.00", CampaignPolicy.nextTier(LADDER, new BigDecimal("400.00")).reduce());
        // 一档都没够着时提示最低档
        CampaignPolicy.Tier first = CampaignPolicy.nextTier(LADDER, new BigDecimal("100.00"));
        assertMoney("199.00", first.threshold());
        assertMoney("20.00", first.reduce());
        assertNull(CampaignPolicy.nextTier(LADDER, new BigDecimal("600.00")));
        assertMoney("199.00", CampaignPolicy.lowestThreshold(LADDER));
        assertMoney("0", CampaignPolicy.lowestThreshold(null));
    }

    @Test
    void rendersLadderTextAndFormatsBackToRule() {
        assertEquals("满199减20 · 满399减60 · 满599减100", CampaignPolicy.ladderText(LADDER));
        assertEquals("", CampaignPolicy.ladderText("bad rule"));
        assertEquals("199:20,399:60",
                CampaignPolicy.formatLadder(List.of(tier("399", "60"), tier("199", "20"), tier("199", "30"))));
    }

    /* ------------------------------------------------------------------ E14 */

    @Test
    void stackableReductionRunsBeforeCoupon() {
        CampaignPolicy.Plan plan = CampaignPolicy.combineReductionAndCoupon(
                tier("199", "20"), "全场满额立减", true, new BigDecimal("30.00"), "阶梯礼券");
        assertEquals(2, plan.benefits().size());
        assertTrue(plan.benefits().get(0).stackable());
        assertEquals("reduction", plan.benefits().get(0).kind());
        assertEquals("coupon", plan.benefits().get(1).kind());
        assertMoney("50.00", plan.totalSaved());
        assertTrue(plan.summary().contains("满减已减 20"));
    }

    /** 不可叠加时只有一条计入合计，落选的那条留作解释，页面不会出现两条都算钱 */
    @Test
    void exclusiveReductionKeepsOnlyTheBetterOne() {
        CampaignPolicy.Plan plan = CampaignPolicy.combineReductionAndCoupon(
                tier("199", "20"), "全场满额立减", false, new BigDecimal("30.00"), "阶梯礼券");
        assertMoney("30.00", plan.totalSaved());
        assertEquals("coupon", plan.benefits().get(0).kind());
        assertTrue(!plan.benefits().get(1).stackable());

        CampaignPolicy.Plan reductionWins = CampaignPolicy.combineReductionAndCoupon(
                tier("399", "60"), "全场满额立减", false, new BigDecimal("30.00"), "阶梯礼券");
        assertMoney("60.00", reductionWins.totalSaved());
        assertEquals("reduction", reductionWins.benefits().get(0).kind());
    }

    /** 金额相同时用券：券有有效期和发行量，先消耗存量更划算 */
    @Test
    void tieGoesToCoupon() {
        CampaignPolicy.Plan plan = CampaignPolicy.combineReductionAndCoupon(
                tier("199", "30"), "全场满额立减", false, new BigDecimal("30.00"), "阶梯礼券");
        assertEquals("coupon", plan.benefits().get(0).kind());
    }

    @Test
    void singleSourceAndEmptyCase() {
        assertMoney("20.00", CampaignPolicy.combineReductionAndCoupon(
                tier("199", "20"), "全场满额立减", false, null, null).totalSaved());
        assertMoney("30.00", CampaignPolicy.combineReductionAndCoupon(
                null, null, false, new BigDecimal("30.00"), "阶梯礼券").totalSaved());
        CampaignPolicy.Plan none = CampaignPolicy.combineReductionAndCoupon(null, null, false, null, null);
        assertMoney("0.00", none.totalSaved());
        assertTrue(none.benefits().isEmpty());
        assertEquals("本单暂无可用减免", none.summary());
    }
}
