package org.liuym.flowerv1springboot.marketing;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.PointsPolicy;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E17 会员等级折扣规则（集中在 CampaignPolicy）+ E18 开通/续费价格与权益文案
 */
class MemberLevelPolicyTest {

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), "期望 " + expected + "，实际 " + actual);
    }

    @Test
    void ratesAreCentralised() {
        assertMoney("1", CampaignPolicy.memberRate(CampaignPolicy.LEVEL_ORDINARY));
        assertMoney("0.95", CampaignPolicy.memberRate(CampaignPolicy.LEVEL_VIP));
        assertMoney("0.88", CampaignPolicy.memberRate(CampaignPolicy.LEVEL_SVIP));
        // 未知等级与脏数据一律按非会员处理，不能白送折扣
        assertMoney("1", CampaignPolicy.memberRate(null));
        assertMoney("1", CampaignPolicy.memberRate("diamond"));
        // 策略类与积分策略里的 VIP 折扣必须是同一个数，否则两套账迟早对不上
        assertEquals(0, CampaignPolicy.memberRate(CampaignPolicy.LEVEL_VIP).compareTo(PointsPolicy.VIP_DISCOUNT_RATE));
    }

    /** 等级越高越省钱，不允许出现 svip 折扣率反而高于 vip 的倒挂 */
    @Test
    void higherLevelAlwaysDiscountsMore() {
        BigDecimal amount = new BigDecimal("1000.00");
        BigDecimal ordinary = CampaignPolicy.memberDiscount(CampaignPolicy.LEVEL_ORDINARY, amount);
        BigDecimal vip = CampaignPolicy.memberDiscount(CampaignPolicy.LEVEL_VIP, amount);
        BigDecimal svip = CampaignPolicy.memberDiscount(CampaignPolicy.LEVEL_SVIP, amount);
        assertMoney("0", ordinary);
        assertMoney("50.00", vip);
        assertMoney("120.00", svip);
        assertTrue(svip.compareTo(vip) > 0 && vip.compareTo(ordinary) > 0);
        assertMoney("0", CampaignPolicy.memberDiscount(CampaignPolicy.LEVEL_VIP, BigDecimal.ZERO));
        assertMoney("0", CampaignPolicy.memberDiscount(CampaignPolicy.LEVEL_VIP, null));
    }

    @Test
    void levelFollowsTotalPaidAndThresholdsAscend() {
        assertEquals(CampaignPolicy.LEVEL_ORDINARY, CampaignPolicy.levelForSpend(null));
        assertEquals(CampaignPolicy.LEVEL_ORDINARY, CampaignPolicy.levelForSpend(new BigDecimal("999.99")));
        assertEquals(CampaignPolicy.LEVEL_VIP, CampaignPolicy.levelForSpend(new BigDecimal("1000.00")));
        assertEquals(CampaignPolicy.LEVEL_SVIP, CampaignPolicy.levelForSpend(new BigDecimal("3000.00")));
        assertMoney("1000.00", CampaignPolicy.upgradeThreshold(CampaignPolicy.LEVEL_ORDINARY));
        assertMoney("3000.00", CampaignPolicy.upgradeThreshold(CampaignPolicy.LEVEL_VIP));
        assertNull(CampaignPolicy.upgradeThreshold(CampaignPolicy.LEVEL_SVIP));
        // 历史脏值（unknown）按普通账号对待，仍能看到升级门槛
        assertMoney("1000.00", CampaignPolicy.upgradeThreshold("unknown"));
    }

    @Test
    void discountTextReadsLikeChineseCouponCopy() {
        assertEquals("9.5 折", CampaignPolicy.discountText(CampaignPolicy.LEVEL_VIP));
        assertEquals("8.8 折", CampaignPolicy.discountText(CampaignPolicy.LEVEL_SVIP));
        assertEquals("10 折", CampaignPolicy.discountText(CampaignPolicy.LEVEL_ORDINARY));
        assertEquals("10 折", CampaignPolicy.discountText(null));
    }

    /** E18：100 积分 = 1 元，续费价按整年线性放大 */
    @Test
    void membershipPriceScalesByYears() {
        assertEquals(9900, CampaignPolicy.vipCostPoints(BigDecimal.ONE));
        assertEquals(19800, CampaignPolicy.vipCostPoints(new BigDecimal("2")));
        assertEquals(29700, CampaignPolicy.vipCostPoints(new BigDecimal("3")));
        // 非法年限退回一年，不让 0 元或负数把会员送出去
        assertEquals(9900, CampaignPolicy.vipCostPoints(null));
        assertEquals(9900, CampaignPolicy.vipCostPoints(new BigDecimal("-2")));
    }

    @Test
    void benefitsAreListedByLevel() {
        List<String> ordinary = CampaignPolicy.memberBenefits(CampaignPolicy.LEVEL_ORDINARY);
        assertEquals(3, ordinary.size());
        assertTrue(ordinary.get(0).contains("1000"));
        assertTrue(ordinary.get(1).contains(CampaignPolicy.discountText(CampaignPolicy.LEVEL_VIP)));

        List<String> vip = CampaignPolicy.memberBenefits(CampaignPolicy.LEVEL_VIP);
        assertEquals(3, vip.size());
        assertTrue(vip.get(0).contains("9.5 折"));
        assertTrue(vip.stream().anyMatch(t -> t.contains(String.valueOf(CampaignPolicy.VIP_EXTRA_COUPONS_PER_MONTH))));
        assertTrue(vip.stream().anyMatch(t -> t.contains(CampaignPolicy.VIP_FREE_SHIPPING_THRESHOLD_DROP
                .stripTrailingZeros().toPlainString())));

        List<String> svip = CampaignPolicy.memberBenefits(CampaignPolicy.LEVEL_SVIP);
        assertTrue(svip.size() > vip.size());
        assertTrue(svip.stream().anyMatch(t -> t.contains("专属客服")));
    }
}
