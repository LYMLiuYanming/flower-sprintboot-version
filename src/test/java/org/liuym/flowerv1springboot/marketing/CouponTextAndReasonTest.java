package org.liuym.flowerv1springboot.marketing;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.vo.CouponViews;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E20 适用范围文案、E06 到期提醒、E08 不可用原因与可重试判定、E13 核销率、E02/E03 条款解析
 */
class CouponTextAndReasonTest {

    private static final UUID ROSE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID WREATH = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID PRODUCT_A = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID PRODUCT_B = UUID.fromString("00000000-0000-0000-0000-0000000000d2");

    private static UserCoupon held(String type, String threshold, String amount, String scope) {
        UserCoupon u = new UserCoupon();
        u.setId(UUID.randomUUID());
        u.setCouponId(UUID.randomUUID());
        u.setCode("T-1");
        u.setName("测试券");
        u.setType(type);
        u.setThreshold(new BigDecimal(threshold));
        u.setAmount(amount == null ? null : new BigDecimal(amount));
        u.setScope(scope);
        u.setCategoryId(Coupon.SCOPE_CATEGORY.equals(scope) ? ROSE : null);
        u.setStatus(UserCoupon.STATUS_UNUSED);
        u.setExpireAt(LocalDateTime.now().plusDays(30));
        u.setReceivedAt(LocalDateTime.now());
        return u;
    }

    private static List<CouponPolicy.Line> oneLine(UUID categoryId, UUID productId, String amount) {
        return List.of(new CouponPolicy.Line(categoryId, new BigDecimal(amount), productId));
    }

    /* ------------------------------------------------------------------ E20 */

    @Test
    void scopeTextCoversAllWhitelistAndCategoryCases() {
        assertEquals("全场花礼通用",
                CouponPolicy.scopeText(List.of(), 0, List.of(), false, false));
        assertEquals("指定 3 款花礼可用",
                CouponPolicy.scopeText(List.of(), 3, List.of(), false, false));
        assertEquals("「玫瑰系列、永生花」 可用",
                CouponPolicy.scopeText(List.of(ROSE, WREATH), 0, List.of("玫瑰系列", "永生花"), false, false));
        // 分类很多时只列前三个并去掉引号，避免券面被一长串分类名撑爆
        assertEquals("玫瑰系列、永生花、花礼 可用",
                CouponPolicy.scopeText(List.of(ROSE, WREATH, ROSE, WREATH), 0,
                        List.of("玫瑰系列", "永生花", "花礼", "开业花篮"), false, false));
        assertEquals("新客专享 · 全场花礼通用",
                CouponPolicy.scopeText(List.of(), 0, List.of(), true, false));
        assertEquals("新客专享 · 会员专享 · 指定 3 款花礼可用",
                CouponPolicy.scopeText(List.of(), 3, List.of(), true, true));
        // 分类名取不到（分类已被删）也要出一句能看的话，不能留空
        assertEquals("「指定分类」 可用",
                CouponPolicy.scopeText(List.of(ROSE, WREATH), 0, null, false, false));
    }

    /** 页面拿到的是服务端算好的字段，券面规则与适用范围都不再是前端拼串 */
    @Test
    void couponViewCarriesGeneratedCopy() {
        Coupon cash = new Coupon();
        cash.setId(UUID.randomUUID());
        cash.setCode("CASH-50");
        cash.setName("满减券");
        cash.setType(Coupon.TYPE_CASH);
        cash.setScope(Coupon.SCOPE_ALL);
        cash.setThreshold(new BigDecimal("150.00"));
        cash.setAmount(new BigDecimal("50.00"));
        CouponViews.CouponView cashView = CouponViews.CouponView.from(cash, null, null);
        assertEquals("满150减50", cashView.ruleText());
        assertEquals("全场花礼通用", cashView.scopeText());
        assertTrue(cashView.claimable());

        Coupon ladder = new Coupon();
        ladder.setId(UUID.randomUUID());
        ladder.setCode("LADDER-1");
        ladder.setName("阶梯券");
        ladder.setType(Coupon.TYPE_CASH);
        ladder.setScope(Coupon.SCOPE_ALL);
        ladder.setThreshold(new BigDecimal("199.00"));
        ladder.setLadderRule("199:20,399:60");
        CouponViews.CouponView ladderView = CouponViews.CouponView.from(ladder, "指定 2 款花礼可用", "已领取");
        assertEquals("满199减20 · 满399减60（自动取最优档）", ladderView.ruleText());
        assertEquals("满199减20 · 满399减60", ladderView.ladderText());
        assertEquals("指定 2 款花礼可用", ladderView.scopeText());
        assertFalse(ladderView.claimable());
        assertEquals("已领取", ladderView.claimBlockReason());

        Coupon discount = new Coupon();
        discount.setId(UUID.randomUUID());
        discount.setCode("DISC-88");
        discount.setName("折扣券");
        discount.setType(Coupon.TYPE_DISCOUNT);
        discount.setScope(Coupon.SCOPE_ALL);
        discount.setThreshold(new BigDecimal("100.00"));
        discount.setDiscountRate(new BigDecimal("0.88"));
        discount.setMaxDiscount(new BigDecimal("20.00"));
        assertEquals("满100打8.8折（封顶20）", CouponViews.CouponView.from(discount).ruleText());
    }

    /* ------------------------------------------------------------------ E03 */

    @Test
    void idListRoundTripIgnoresDirtyValues() {
        assertNull(CouponPolicy.joinIds(null));
        assertNull(CouponPolicy.joinIds(List.of()));
        assertEquals(ROSE + "," + WREATH, CouponPolicy.joinIds(List.of(ROSE, WREATH)));
        List<UUID> parsed = CouponPolicy.parseIds(ROSE + ", 坏值 ," + WREATH + ",");
        assertEquals(List.of(ROSE, WREATH), parsed);

        UserCoupon u = held("cash", "0", "10", Coupon.SCOPE_CATEGORY);
        u.setCategoryIds(WREATH.toString());
        // 单选与多选取并集（多选串在前），后台从单选升级到多选时老券照样能用
        assertEquals(List.of(WREATH, ROSE), CouponPolicy.boundCategoryIds(u));
        assertEquals(List.of(), CouponPolicy.boundProductIds(u));
        u.setProductIds(PRODUCT_A + "," + PRODUCT_B);
        assertEquals(List.of(PRODUCT_A, PRODUCT_B), CouponPolicy.boundProductIds(u));
        // 非分类券不认 categoryIds，避免脏数据把全场券限死
        UserCoupon all = held("cash", "0", "10", Coupon.SCOPE_ALL);
        all.setCategoryIds(ROSE.toString());
        assertEquals(List.of(), CouponPolicy.boundCategoryIds(all));
    }

    /** 白名单券只认名单内的行；行上缺 productId（历史调用方）时按不适用处理 */
    @Test
    void productWhitelistNarrowsTheBase() {
        UserCoupon u = held("cash", "0", "30", Coupon.SCOPE_ALL);
        u.setProductIds(PRODUCT_A.toString());
        List<CouponPolicy.Line> lines = List.of(
                new CouponPolicy.Line(ROSE, new BigDecimal("80.00"), PRODUCT_A),
                new CouponPolicy.Line(ROSE, new BigDecimal("60.00"), PRODUCT_B),
                new CouponPolicy.Line(ROSE, new BigDecimal("50.00")));
        assertEquals(new BigDecimal("80.00"), CouponPolicy.baseAmount(u, List.of(), lines));
        assertMoney("30.00", CouponPolicy.discountOf(u, CouponPolicy.baseAmount(u, List.of(), lines)));
    }

    /* ------------------------------------------------------------------ E08 */

    @Test
    void unavailableReasonTellsExactlyWhatIsMissing() {
        UserCoupon cash = held("cash", "200.00", "30.00", Coupon.SCOPE_ALL);
        assertEquals("还差 ¥50.00 可用",
                CouponPolicy.unavailableReason(cash, List.of(), oneLine(ROSE, PRODUCT_A, "150.00")));
        assertNull(CouponPolicy.unavailableReason(cash, List.of(), oneLine(ROSE, PRODUCT_A, "200.00")));

        UserCoupon used = held("cash", "0.00", "30.00", Coupon.SCOPE_ALL);
        used.setStatus(UserCoupon.STATUS_USED);
        assertEquals("已使用", CouponPolicy.unavailableReason(used, List.of(), oneLine(ROSE, PRODUCT_A, "100.00")));

        UserCoupon gifting = held("cash", "0.00", "30.00", Coupon.SCOPE_ALL);
        gifting.setStatus(UserCoupon.STATUS_GIFTING);
        assertEquals("正在转赠给他人", CouponPolicy.unavailableReason(gifting, List.of(), oneLine(ROSE, PRODUCT_A, "100.00")));

        UserCoupon expired = held("cash", "0.00", "30.00", Coupon.SCOPE_ALL);
        expired.setExpireAt(LocalDateTime.now().minusDays(1));
        assertEquals("已过期", CouponPolicy.unavailableReason(expired, List.of(), oneLine(ROSE, PRODUCT_A, "100.00")));

        UserCoupon categoryOnly = held("cash", "0.00", "30.00", Coupon.SCOPE_CATEGORY);
        assertEquals("本单没有适用分类的花礼",
                CouponPolicy.unavailableReason(categoryOnly, List.of(ROSE), oneLine(WREATH, PRODUCT_A, "100.00")));

        UserCoupon whitelist = held("cash", "0.00", "30.00", Coupon.SCOPE_ALL);
        whitelist.setProductIds(PRODUCT_A.toString());
        assertEquals("本单没有该券指定的花礼",
                CouponPolicy.unavailableReason(whitelist, List.of(), oneLine(ROSE, PRODUCT_B, "100.00")));

        // 门槛够但抵不出金额（满减金额漏填）也要说一句人话，而不是只报「不可用」
        UserCoupon broken = held("cash", "0.00", null, Coupon.SCOPE_ALL);
        assertEquals("券面条款已调整，本单抵不出金额",
                CouponPolicy.unavailableReason(broken, List.of(), oneLine(ROSE, PRODUCT_A, "100.00")));
        assertEquals("优惠券不存在", CouponPolicy.unavailableReason(null, List.of(), List.of()));
    }

    /** 只有「再凑一点」和「券刚被人用掉」值得重试，其余重试还是失败 */
    @Test
    void retryableViewMarksOnlyWorthRetrying() {
        UserCoupon u = held("cash", "200.00", "30.00", Coupon.SCOPE_ALL);
        assertTrue(Boolean.TRUE.equals(CouponViews.UserCouponView.from(u, null, null, "还差 ¥50.00 可用").retryable()));
        assertTrue(Boolean.TRUE.equals(CouponViews.UserCouponView.from(u, null, null,
                "该优惠券已被核销（订单 123），刷新后可换一张券重新提交").retryable()));
        assertTrue(Boolean.TRUE.equals(CouponViews.UserCouponView.from(u, null, null,
                "该优惠券状态已变更，刷新券包后可重试").retryable()));
        assertFalse(Boolean.TRUE.equals(CouponViews.UserCouponView.from(u, null, null, "已过期").retryable()));
        assertFalse(Boolean.TRUE.equals(CouponViews.UserCouponView.from(u, null, null, null).retryable()));
        // 券面规则与适用范围都由服务端带出：缺 scopeText 时退化成按 scope 的兜底文案
        assertEquals("满200减30", CouponViews.UserCouponView.from(u, null, "「玫瑰系列」 可用", null).ruleText());
        assertEquals("「玫瑰系列」 可用", CouponViews.UserCouponView.from(u, null, "「玫瑰系列」 可用", null).scopeText());
        assertEquals("全场花礼通用", CouponViews.UserCouponView.from(u, null, null, null).scopeText());
        assertEquals("", CouponViews.UserCouponView.from(u, new BigDecimal("30.00"), null, null).ladderText());
        assertEquals(new BigDecimal("30.00"), CouponViews.UserCouponView.from(u, new BigDecimal("30.00"), null, null).discount());
    }

    /* ------------------------------------------------------------------ E06 */

    @Test
    void expireReminderOnlyForUnusedCouponsNearExpiry() {
        LocalDate today = LocalDate.now();
        UserCoupon u = held("cash", "100.00", "20.00", Coupon.SCOPE_ALL);

        u.setExpireAt(today.atTime(23, 59));
        assertEquals("今天到期", CouponPolicy.expireReminder(u, LocalDateTime.now()));
        u.setExpireAt(today.plusDays(1).atTime(9, 0));
        assertEquals("明天到期", CouponPolicy.expireReminder(u, LocalDateTime.now()));
        u.setExpireAt(today.plusDays(2).atTime(9, 0));
        assertEquals("2 天后到期", CouponPolicy.expireReminder(u, LocalDateTime.now()));
        u.setExpireAt(today.plusDays(CouponPolicy.REMIND_BEFORE_DAYS).atTime(9, 0));
        assertEquals(CouponPolicy.REMIND_BEFORE_DAYS + " 天后到期", CouponPolicy.expireReminder(u, LocalDateTime.now()));

        // 窗口外不提醒
        u.setExpireAt(today.plusDays(CouponPolicy.REMIND_BEFORE_DAYS + 20).atTime(9, 0));
        assertNull(CouponPolicy.expireReminder(u, LocalDateTime.now()));
        // 已过期不再提醒，避免和「已过期」状态同时出现
        u.setExpireAt(today.minusDays(1).atTime(9, 0));
        assertNull(CouponPolicy.expireReminder(u, LocalDateTime.now()));
        u.setExpireAt(today.plusDays(1).atTime(9, 0));
        u.setStatus(UserCoupon.STATUS_USED);
        assertNull(CouponPolicy.expireReminder(u, LocalDateTime.now()));
        assertNull(CouponPolicy.expireReminder(null, LocalDateTime.now()));
    }

    /* ------------------------------------------------------------------ E13 */

    @Test
    void consumeRateAvoidsDivideByZero() {
        assertMoney("0.00", CouponPolicy.consumeRate(0, 0));
        assertMoney("0.00", CouponPolicy.consumeRate(5, 0));
        assertMoney("25.00", CouponPolicy.consumeRate(4, 1));
        assertMoney("66.67", CouponPolicy.consumeRate(3, 2));
        assertMoney("100.00", CouponPolicy.consumeRate(7, 7));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)), "期望 " + expected + "，实际 " + actual);
    }
}
