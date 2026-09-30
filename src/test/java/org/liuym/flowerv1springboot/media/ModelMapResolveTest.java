package org.liuym.flowerv1springboot.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.controller.MediaController;
import org.liuym.flowerv1springboot.vo.MediaViews;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J01 品类→GLB 映射解析的口径测试：三档命中的优先级、兜底与默认值都在纯函数里，
 * 起 Spring 只为读一张字典表不值得，所以直接测静态实现。
 */
class ModelMapResolveTest {

    private static MediaViews.ModelBinding row(String type, String key, String url, String preset, Double speed) {
        return new MediaViews.ModelBinding(type, key, url, "展台", null, preset, speed, true, null);
    }

    private static final List<MediaViews.ModelBinding> SEED = List.of(
            row("keyword", "抱抱桶", "/models/FlowerBox.glb", "vase", 0.28),
            row("category", "玫瑰", "/models/Rose.glb", "head", 0.4),
            row("category", "玫瑰花", "/models/RoseBouquet.glb", "full", 0.32),
            row("default", "*", "/models/GlassVaseFlowers.glb", "full", 0.32)
    );

    @Test
    @DisplayName("关键词档最优先：同分类里花盒与花束差很远")
    void keywordWinsOverCategory() {
        MediaViews.Stage stage = MediaController.resolveStage(SEED, "玫瑰花", "七夕抱抱桶玫瑰花束");
        assertEquals("/models/FlowerBox.glb", stage.modelUrl());
        assertEquals("keyword", stage.matched());
        assertEquals("vase", stage.preset());
        assertEquals(0.28, stage.spinSpeed());
    }

    @Test
    @DisplayName("同档位取更长（更具体）的 match_key，忽略大小写与首尾空格")
    void longestCategoryKeyWins() {
        MediaViews.Stage stage = MediaController.resolveStage(SEED, " 玫瑰花 ", null);
        assertEquals("/models/RoseBouquet.glb", stage.modelUrl());
        assertEquals("category", stage.matched());

        assertEquals("/models/Rose.glb",
                MediaController.resolveStage(SEED, "玫瑰", null).modelUrl());
    }

    @Test
    @DisplayName("未配到品类的走全局兜底，matched=default")
    void fallsBackToDefaultRow() {
        MediaViews.Stage stage = MediaController.resolveStage(SEED, "康乃馨", "母亲节花束");
        assertEquals("/models/GlassVaseFlowers.glb", stage.modelUrl());
        assertEquals("default", stage.matched());
    }

    @Test
    @DisplayName("删掉兜底行就是整体关掉立体视图：modelUrl 为空且给出可读原因")
    void noBindingMeansTwoDimensionalFallback() {
        List<MediaViews.ModelBinding> onlyCategories = List.of(row("category", "玫瑰花", "/models/Rose.glb", "full", 0.3));
        assertNull(MediaController.resolveStage(onlyCategories, "向日葵", null).modelUrl());
        assertTrue(MediaController.resolveStage(List.of(), "向日葵", null).modelUrl() == null);

        MediaViews.Stage none = MediaController.resolveStage(List.of(), null, null);
        assertNull(none.modelUrl());
        assertNotNull(none.reason());
        assertFalse(none.reason().isBlank());
    }

    @Test
    @DisplayName("preset / 转速 / 文案缺失时补默认值，页面不用各自兜底")
    void fillsDefaults() {
        MediaViews.Stage stage = MediaController.resolveStage(
                List.of(new MediaViews.ModelBinding("default", "*", "/models/a.glb", null, null, null, null, null, null)),
                null, null);
        assertEquals("立体展台", stage.label());
        assertEquals("full", stage.preset());
        assertEquals(0.32, stage.spinSpeed());
        assertTrue(stage.shared());
        assertFalse(stage.caption().isBlank());
    }

    @Test
    @DisplayName("空 match_key 与未知档位的脏数据不参与命中，不能让一条写错的配置吞掉兜底")
    void ignoresDirtyRows() {
        List<MediaViews.ModelBinding> dirty = List.of(
                row("category", "  ", "/models/Broken.glb", "full", 0.3),
                row("unexpected", "玫瑰花", "/models/Broken2.glb", "full", 0.3),
                row("default", "*", "/models/GlassVaseFlowers.glb", "full", 0.32));
        MediaViews.Stage stage = MediaController.resolveStage(dirty, "玫瑰花", null);
        assertEquals("/models/GlassVaseFlowers.glb", stage.modelUrl());
    }

    @Test
    @DisplayName("商品名里没有关键词时不硬凑，按分类走")
    void keywordRequiresText() {
        MediaViews.Stage stage = MediaController.resolveStage(SEED, "向日葵", "明亮花束");
        assertEquals("default", stage.matched());
    }
}
