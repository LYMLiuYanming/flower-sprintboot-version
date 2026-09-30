package org.liuym.flowerv1springboot.account;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.AccountPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D01/D02/D03/D07/D13/D14/D17 账户校验策略单测：格式、强度、脱敏、券提醒与 CSV 转义。
 */
class AccountPolicyTest {

    /* ---------- D01 用户名 ---------- */
    @Test
    void usernameAcceptsCjkLatinDigitAndInnerUnderscore() {
        assertNull(AccountPolicy.usernameError("flower_01"));
        assertNull(AccountPolicy.usernameError("花语轩用户"));
        assertNull(AccountPolicy.usernameError("abc"));
    }

    @Test
    void usernameRejectsBadShapes() {
        assertNotNull(AccountPolicy.usernameError("ab"));              // 太短
        assertNotNull(AccountPolicy.usernameError("a".repeat(21)));     // 太长
        assertNotNull(AccountPolicy.usernameError("_flower"));          // 下划线开头
        assertNotNull(AccountPolicy.usernameError("flower_"));          // 下划线结尾
        assertNotNull(AccountPolicy.usernameError("花 语"));            // 含空格
        assertNotNull(AccountPolicy.usernameError("user@name"));        // 非法字符
        assertNotNull(AccountPolicy.usernameError("11111"));            // 全同字符
        assertNotNull(AccountPolicy.usernameError("  "));               // 空白
    }

    /* ---------- D02 手机号 ---------- */
    @Test
    void phoneValidatesAndMasks() {
        assertTrue(AccountPolicy.isValidPhone("13800138000"));
        assertFalse(AccountPolicy.isValidPhone("12345678901"));
        assertFalse(AccountPolicy.isValidPhone("1380013800"));
        assertNotNull(AccountPolicy.phoneError(null));
        assertEquals("138****8000", AccountPolicy.maskPhone("13800138000"));
        assertEquals("12345", AccountPolicy.maskPhone("12345")); // 非 11 位原样
    }

    @Test
    void nameMaskFollowsExistingConvention() {
        assertEquals("欧*修", AccountPolicy.maskName("欧阳修"));
        assertEquals("张*", AccountPolicy.maskName("张三"));
        assertEquals("李**", AccountPolicy.maskName("李"));
    }

    /* ---------- D03 密码强度 ---------- */
    @Test
    void strongPasswordPasses() {
        AccountPolicy.PasswordStrength s = AccountPolicy.evaluatePassword("Fl0wer#2026", "flower_01");
        assertNull(s.issue());
        assertEquals("强", s.level());
        assertTrue(s.percent() >= 80);
    }

    @Test
    void weakOrTooShortPasswordRejected() {
        assertNotNull(AccountPolicy.passwordError("abc", "u"));               // 太短
        assertNotNull(AccountPolicy.passwordError("abcdefghij", "u"));        // 纯字母，类别不足
        assertNotNull(AccountPolicy.passwordError("123456789", "u"));         // 纯数字
        assertNotNull(AccountPolicy.passwordError("12345678", "u"));          // 弱口令黑名单
        assertNotNull(AccountPolicy.passwordError("flower123456", "flower")); // 含用户名
    }

    @Test
    void passwordCategoryGateIsTwo() {
        // 字母+数字两类应通过（即便不含符号、不含大小写混合）
        assertNull(AccountPolicy.passwordError("flower12345", "someone"));
    }

    /* ---------- D07 地址标签 ---------- */
    @Test
    void addressTagNormalizedAndClamped() {
        assertNull(AccountPolicy.normalizeAddressTag("   "));
        assertEquals("家", AccountPolicy.normalizeAddressTag(" 家 "));
        assertEquals("自定义标签", AccountPolicy.normalizeAddressTag("自定义标签"));
        assertEquals(10, AccountPolicy.normalizeAddressTag("一二三四五六七八九十一二三").length());
        assertTrue(AccountPolicy.isPresetTag("公司"));
        assertFalse(AccountPolicy.isPresetTag("仓库"));
    }

    /* ---------- D13/D14 券到期与门槛 ---------- */
    @Test
    void expiringSoonWindow() {
        LocalDateTime now = LocalDateTime.now();
        assertTrue(AccountPolicy.isExpiringSoon(now.plusDays(2), now));
        assertTrue(AccountPolicy.isExpiringSoon(now.plusDays(AccountPolicy.COUPON_EXPIRING_DAYS), now));
        assertFalse(AccountPolicy.isExpiringSoon(now.plusDays(10), now));
        assertFalse(AccountPolicy.isExpiringSoon(now.minusDays(1), now)); // 已过期
    }

    @Test
    void thresholdGapOnlyWhenBelow() {
        assertEquals(new BigDecimal("50.00"),
                AccountPolicy.thresholdGap(new BigDecimal("200"), new BigDecimal("150")));
        assertNull(AccountPolicy.thresholdGap(new BigDecimal("200"), new BigDecimal("200")));
        assertNull(AccountPolicy.thresholdGap(BigDecimal.ZERO, new BigDecimal("10"))); // 无门槛
        assertEquals(new BigDecimal("200.00"), AccountPolicy.thresholdGap(new BigDecimal("200"), null));
    }

    /* ---------- D05 头像统一口径 ---------- */
    @Test
    void avatarInitialAndColorAreStableAndConsistent() {
        assertEquals("花", AccountPolicy.avatarInitial(""));
        assertEquals("欧", AccountPolicy.avatarInitial("  欧阳修"));
        // 同一名字任意时刻同色，不同名字大概率不同
        assertEquals(AccountPolicy.avatarColor("张三"), AccountPolicy.avatarColor("张三"));
        assertTrue(AccountPolicy.avatarColor("张三").startsWith("hsl("));
    }

    /* ---------- D17 CSV 转义 ---------- */
    @Test
    void csvEscapingGuardsAgainstInjection() {
        assertEquals("\"a,b\"", AccountPolicy.csvCell("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", AccountPolicy.csvCell("say \"hi\""));
        assertEquals("\"=1+1\"", AccountPolicy.csvCell("=1+1"));   // 公式注入需被引号包裹
        assertEquals("plain", AccountPolicy.csvCell("plain"));
        assertEquals("a,1", AccountPolicy.csvRow("a", "1"));
        assertEquals("\"x,y\",2", AccountPolicy.csvRow("x,y", "2"));
    }
}
