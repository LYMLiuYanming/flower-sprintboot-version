package org.liuym.flowerv1springboot.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.controller.MediaController;
import org.liuym.flowerv1springboot.vo.MediaViews;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J11 缩略图入参校验：/img/thumb 是站内唯一的重采样出口，白名单、路径穿越与尺寸上限
 * 都在这里一次性把住，所以按「攻击面」逐条测，而不是只测正常路径。
 */
class ThumbRequestTest {

    private static MediaViews.ThumbRequest ok(String path, Integer w, Integer h) {
        return MediaController.normalizeThumb(path, w, h);
    }

    private static void reject(String path, Integer w, Integer h) {
        assertThrows(BusinessException.class, () -> ok(path, w, h), "应拒绝：" + path);
    }

    @Test
    @DisplayName("白名单目录内的正常请求：路径保持，宽度按请求")
    void acceptsWhitelistedPath() {
        MediaViews.ThumbRequest request = ok("/img/photos/hero-rose.jpg", 320, null);
        assertEquals("/img/photos/hero-rose.jpg", request.path());
        assertEquals(320, request.width());
        assertNull(request.height());
        assertFalse(request.passthrough());
    }

    @Test
    @DisplayName("宽度缺省补 640，越界夹进 [64,1600]，不做放大代理")
    void clampsWidth() {
        assertEquals(MediaController.THUMB_DEFAULT_WIDTH, ok("/img/photos/a.jpg", null, null).width());
        assertEquals(MediaController.THUMB_MAX_WIDTH, ok("/img/photos/a.jpg", 99999, null).width());
        assertEquals(MediaController.THUMB_MIN_WIDTH, ok("/img/photos/a.jpg", 1, null).width());
    }

    @Test
    @DisplayName("目录穿越与编码变体一律拒绝，包括 ../ 与 %2e%2e 与双斜杠开头")
    void rejectsTraversal() {
        reject("/img/photos/../../models/GlassVaseFlowers.glb", 320, null);
        reject("/img/photos/%2e%2e/application.properties", 320, null);
        reject("//img.photos.evil.com/a.jpg", 320, null);
        reject("img/photos/a.jpg", 320, null);
        reject("https://evil.com/a.jpg", 320, null);
        reject("/img/photos/a.jpg?path=/etc/passwd", 320, null);
        reject("/img/photos/a.jpg#x", 320, null);
        reject("/img/photos\\..\\..\\a.jpg", 320, null);
        reject("", 320, null);
        reject(null, 320, null);
    }

    @Test
    @DisplayName("白名单外的目录（含 /models 与站点根）不提供缩略图，站点占位图按整串精确放行")
    void rejectsOutsideWhitelist() {
        reject("/models/GlassVaseFlowers.glb", 320, null);
        reject("/img/a.jpg", 320, null);
        reject("/js/site.js", 320, null);
        reject("/admin/x.jpg", 320, null);
        assertTrue(ok("/img/product-placeholder.svg", 320, null).passthrough());
    }

    @Test
    @DisplayName("非图片扩展名拒绝；SVG 走原样透出且不带宽高")
    void handlesExtensions() {
        reject("/img/photos/a.exe", 320, null);
        reject("/img/photos/a", 320, null);
        MediaViews.ThumbRequest svg = ok("/img/banners/banner.svg", 320, 400);
        assertTrue(svg.passthrough());
        assertNull(svg.height());
        assertEquals("svg", MediaController.extension("/img/banners/banner.SVG"));
    }

    @Test
    @DisplayName("总像素预算限制：宽高都顶满时拒掉，避免一张图吃掉几十兆内存")
    void rejectsHugeCanvas() {
        assertEquals(MediaController.THUMB_MAX_WIDTH, ok("/img/photos/a.jpg", 1600, 1100).height());
        reject("/img/photos/a.jpg", 1600, 1600);
    }

    @Test
    @DisplayName("路径中间夹控制字符拒掉，首尾空白照常裁掉")
    void rejectsControlCharacters() {
        assertThrows(BusinessException.class, () -> ok("/img/pa\tths/a.jpg", 320, null));
        assertEquals("/img/photos/a.jpg", ok("  /img/photos/a.jpg  ", 320, null).path());
    }

    @Test
    @DisplayName("只缩不放：源图不比目标小就原样返回，缩一半时尺寸按比例")
    void resizeNeverUpscales() {
        BufferedImage source = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        assertSame(source, MediaController.resize(source, 200, null), "不应放大源图");

        BufferedImage half = MediaController.resize(source, 50, null);
        assertEquals(50, half.getWidth());
        assertEquals(40, half.getHeight());

        BufferedImage capped = MediaController.resize(source, 50, 20);
        assertEquals(25, capped.getWidth(), "高度约束应等比生效");
        assertEquals(20, capped.getHeight());
    }

    @Test
    @DisplayName("带 alpha 的 PNG 缩完仍是 PNG，别把透明底填成黑")
    void keepsAlpha() {
        BufferedImage source = new BufferedImage(120, 120, BufferedImage.TYPE_INT_ARGB);
        BufferedImage out = MediaController.resize(source, 60, null);
        assertTrue(out.getColorModel().hasAlpha());
    }
}
