package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CouponPolicyTest {

    private static final UUID FLOWERS = UUID.randomUUID();
    private static final UUID CARDS = UUID.randomUUID();

    private static UserCoupon coupon(String type, String threshold, String amount,
                                     String rate, String maxDiscount, String scope) {
        UserCoupon u = new UserCoupon();
        u.setCouponId(UUID.randomUUID());
        u.setName("测试券");
        u.setType(type);
        u.setThreshold(new BigDecimal(threshold));
        u.setAmount(amount == null ? null : new BigDecimal(amount));
        u.setDiscountRate(rate == null ? null : new BigDecimal(rate));
        u.setMaxDiscount(maxDiscount == null ? null : new BigDecimal(maxDiscount));
        u.setScope(scope);
        u.setCategoryId(Coupon.SCOPE_CATEGORY.equals(scope) ? FLOWERS : null);
        u.setStatus("unused");
        return u;
    }

    private static List<CouponPolicy.Line> cart() {
        return List.of(
                new CouponPolicy.Line(FLOWERS, new BigDecimal("150.00")),
                new CouponPolicy.Line(FLOWERS, new BigDecimal("60.00")),
                new CouponPolicy.Line(CARDS, new BigDecimal("30.00")));
    }

    @Test
    void allScopeBasesOnWholeOrder() {
        UserCoupon u = coupon("cash", "200", "30", null, null, Coupon.SCOPE_ALL);
        assertEquals(new BigDecimal("240.00"), CouponPolicy.baseAmount(u, cart()));
        assertEquals(new BigDecimal("30.00"), CouponPolicy.discountOf(u, new BigDecimal("240.00")));
    }

    @Test
    void categoryScopeOnlyCountsItsOwnLines() {
        UserCoupon u = coupon("cash", "200", "30", null, null, Coupon.SCOPE_CATEGORY);
        assertEquals(new BigDecimal("210.00"), CouponPolicy.baseAmount(u, cart()));
    }

    @Test
    void thresholdIsInclusiveAndBasedOnScope() {
        UserCoupon u = coupon("cash", "200", "30", null, null, Coupon.SCOPE_CATEGORY);
        BigDecimal base = CouponPolicy.baseAmount(u, cart());
        assertTrue(CouponPolicy.meetsThreshold(u, new BigDecimal("200.00")));
        assertNull(CouponPolicy.discountOf(u, new BigDecimal("199.99")));
        assertNotNull(CouponPolicy.discountOf(u, base));
    }

    @Test
    void cashCouponCannotExceedItsOwnBase() {
        UserCoupon u = coupon("cash", "0", "500", null, null, Coupon.SCOPE_CATEGORY);
        assertEquals(new BigDecimal("210.00"), CouponPolicy.discountOf(u, CouponPolicy.baseAmount(u, cart())));
    }

    @Test
    void discountCouponRespectsMaxDiscountCap() {
        UserCoupon u = coupon("discount", "100", null, "0.80", "20", Coupon.SCOPE_ALL);
        assertEquals(new BigDecimal("20.00"), CouponPolicy.discountOf(u, new BigDecimal("240.00")));
    }

    @Test
    void discountCouponWithoutCapUsesRate() {
        UserCoupon u = coupon("discount", "100", null, "0.88", null, Coupon.SCOPE_ALL);
        assertEquals(new BigDecimal("28.80"), CouponPolicy.discountOf(u, new BigDecimal("240.00")));
    }

    @Test
    void invalidTermsYieldNoDiscount() {
        assertNull(CouponPolicy.discountOf(coupon("cash", "0", "0", null, null, Coupon.SCOPE_ALL), new BigDecimal("10")));
        assertNull(CouponPolicy.discountOf(coupon("discount", "0", null, "1", null, Coupon.SCOPE_ALL), new BigDecimal("10")));
        assertNull(CouponPolicy.discountOf(coupon("mystery", "0", "10", null, null, Coupon.SCOPE_ALL), new BigDecimal("10")));
        assertNull(CouponPolicy.discountOf(coupon("cash", "100", "10", null, null, Coupon.SCOPE_ALL), BigDecimal.ZERO));
        assertNull(CouponPolicy.discountOf(null, new BigDecimal("10")));
    }

    @Test
    void nullSafeBase() {
        assertEquals(BigDecimal.ZERO, CouponPolicy.baseAmount(null, cart()));
        assertEquals(new BigDecimal("180.00"),
                CouponPolicy.baseAmount(coupon("cash", "0", "10", null, null, Coupon.SCOPE_CATEGORY),
                        List.of(new CouponPolicy.Line(FLOWERS, new BigDecimal("180.00")),
                                new CouponPolicy.Line(null, new BigDecimal("99.00")))));
    }
}
