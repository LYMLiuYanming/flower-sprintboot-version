package org.liuym.flowerv1springboot.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlSanitizerTest {

    @Test
    void keepsLayoutTagsAndDropsAttributes() {
        String html = "<h3>维护</h3><p style=\"color:red\">本周<b>停机</b> <ul><li>下单正常</li></ul></p>";
        assertEquals("<h3>维护</h3><p>本周<b>停机</b> <ul><li>下单正常</li></ul></p>", HtmlSanitizer.clean(html));
    }

    @Test
    void scriptBecomesPlainText() {
        String html = "<p>正常</p><script>alert(1)</script><img src=x onerror=alert(1)>";
        String clean = HtmlSanitizer.clean(html);
        assertFalse(clean.contains("<script"), clean);
        assertFalse(clean.contains("<img"), clean);
        assertFalse(clean.contains("onerror"), clean);
        assertTrue(clean.contains("正常"), clean);
    }

    @Test
    void brokenAngleBracketsCannotReassembleIntoTags() {
        String clean = HtmlSanitizer.clean("<scri<b>pt>alert(1)</b>pt>");
        assertFalse(clean.toLowerCase().contains("<script"), clean);
    }

    @Test
    void keepsRelativeLinkButRejectsDangerousHref() {
        assertTrue(HtmlSanitizer.clean("<a href=\"/products?tag=玫瑰\">站内</a>").contains("href=\"/products?tag=玫瑰\""));
        assertFalse(HtmlSanitizer.clean("<a href=\"javascript:alert(1)\">坏</a>").contains("href"));
        assertFalse(HtmlSanitizer.clean("<a href=\"//evil.com/x\">跳转</a>").contains("href"));
        assertFalse(HtmlSanitizer.clean("<a href=\"https://legit.com@evil.com/\">跳转</a>").contains("href"));
    }

    @Test
    void dropsComments() {
        assertEquals("<p>ab</p>", HtmlSanitizer.clean("<p>a<!-- 注释 -->b</p>"));
    }

    @Test
    void textStripsTagsAndEntities() {
        assertEquals("标题 正文", HtmlSanitizer.text("<h3>标题</h3><p>正文</p>"));
        assertEquals("A B", HtmlSanitizer.text("A&nbsp;B"));
        assertEquals("", HtmlSanitizer.text("  <p> </p><br>"));
        assertEquals("", HtmlSanitizer.text(null));
    }

    @Test
    void cleanOfBlankReturnsEmpty() {
        assertEquals("", HtmlSanitizer.clean(null));
        assertEquals("", HtmlSanitizer.clean("   "));
    }
}
