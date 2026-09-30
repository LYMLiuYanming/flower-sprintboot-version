package org.liuym.flowerv1springboot.engineering;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.Masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L01 脱敏口径：手机号在任何出口都是 138****8000 这一个形状，地址不得带详址，口令判定要能兜住变体名。
 */
class MaskingTest {

    @Test
    void 手机号保留前3后4() {
        assertEquals("138****8000", Masking.phone("13800008000"));
        assertEquals("138****8000", Masking.phone(" 13800008000 "));
        assertEquals("138****8000", Masking.phone("138-0000-8000".replaceAll("-", "")));
    }

    @Test
    void 形状不符的原样返回以免掩盖脏数据() {
        assertEquals("12345", Masking.phone("12345"));
        assertEquals("1380000800a", Masking.phone("1380000800a"));
        assertNull(Masking.phone(null));
    }

    @Test
    void 地址只保留到行政区() {
        // 存储里最常见的形态是一整行没有分隔符：前 6 字大约到「市区」一级
        assertEquals("北京市朝阳区****", Masking.address("北京市朝阳区望京街10号院3单元"));
        // 有分隔符时最后一段（详址）永不保留
        assertEquals("北京朝阳****", Masking.address("北京 朝阳 望京街10号"));
        assertEquals("广东省深圳市南山区****", Masking.address("广东省/深圳市/南山区/科技园路1号"));
        assertNull(Masking.address(null));
    }

    @Test
    void 姓名与邮箱保留可读部分() {
        assertEquals("张*", Masking.name("张三"));
        assertEquals("欧*修", Masking.name("欧阳修"));
        assertEquals("匿名用户", Masking.name("  "));
        assertEquals("z***@qq.com", Masking.email("zhangsan@qq.com"));
    }

    @Test
    void 自由文本里只打手机号不误伤订单号() {
        assertEquals("手机 138****8000 已核", Masking.scrubText("手机 13800008000 已核"));
        // 订单号是 18 位数字，不该被当成手机号截掉
        String order = "订单 FD202609301234567890 已付";
        assertEquals(order, Masking.scrubText(order));
        assertEquals("", Masking.scrubText(null));
    }

    @Test
    void 口令类字段名一律判定为敏感() {
        assertTrue(Masking.isSecretKey("password"));
        assertTrue(Masking.isSecretKey("confirmPassword"));
        assertTrue(Masking.isSecretKey("old_password"));
        assertTrue(Masking.isSecretKey("passwordHash"));
        assertTrue(Masking.isSecretKey("remember-secret"));
        assertTrue(Masking.isSecretKey("captchaCode"));
        assertFalse(Masking.isSecretKey("passengerCount"));
        assertFalse(Masking.isSecretKey("receiverPhone"));
        assertFalse(Masking.isSecretKey(null));
    }

    @Test
    void 异常摘要取根因且截断() {
        Exception root = new IllegalStateException("连接被拒绝 13800008000");
        var wrapper = new RuntimeException("外层包装", new RuntimeException("中层", root));
        String brief = Masking.brief(wrapper);
        assertTrue(brief.startsWith("IllegalStateException"), brief);
        // 摘要里也要打码：任务台账与日志都会原样带出这句话
        assertTrue(brief.contains("138****8000"), brief);
        assertEquals("", Masking.brief(null));
    }

    @Test
    void 长文本摘要截断() {
        String longMessage = "x".repeat(500);
        String brief = Masking.brief(new RuntimeException(longMessage));
        assertTrue(brief.length() <= 200, "摘要应被截断到 200 字符内，实际 " + brief.length());
    }

    @Test
    void 指纹只留首尾够排障用() {
        assertEquals("abcd****uvwx", Masking.fingerprint("abcdefghijklmnopqrstuvwxy"));
        assertEquals("****", Masking.fingerprint("short"));
    }
}
