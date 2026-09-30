package org.liuym.flowerv1springboot.service.impl;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.vo.ProductView;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检索与商品视图的纯逻辑校验：相关度权重（A09）、排序/分页白名单（A06/A08）、
 * 折扣与库存告急口径（A19/A20）、场景标签归一（A18）。
 * 不依赖 Spring 上下文，改权重表时这里会第一时间报错。
 */
class ProductRelevanceTest {

    @Test
    void 权重表按命中位置严格递减() {
        List<ProductServiceImpl.ScoringField> fields = ProductServiceImpl.SCORING_FIELDS;
        for (int i = 1; i < fields.size(); i++) {
            assertTrue(fields.get(i - 1).weight() > fields.get(i).weight(),
                    "字段权重必须递减：" + fields);
        }
        assertEquals(40, fields.get(0).weight());
        assertEquals("name", fields.get(0).attribute());
        assertTrue(fields.get(0).weight() > ProductServiceImpl.CATEGORY_WEIGHT,
                "分类名权重应低于任何商品自身字段");
    }

    @Test
    void 名称命中优于花材优于标签优于描述() {
        int name = ProductServiceImpl.scoreOfFields(Set.of("name"));
        int material = ProductServiceImpl.scoreOfFields(Set.of("material"));
        int tags = ProductServiceImpl.scoreOfFields(Set.of("tags"));
        int description = ProductServiceImpl.scoreOfFields(Set.of("description"));
        int category = ProductServiceImpl.scoreOfFields(Set.of("category"));
        assertTrue(name > material && material > tags && tags > description);
        assertTrue(description > category);
        assertEquals(0, ProductServiceImpl.scoreOfFields(Set.of()));
    }

    @Test
    void 多字段同时命中按分值累加() {
        assertEquals(40 + 4, ProductServiceImpl.scoreOfFields(Set.of("name", "description")));
        // 同名字段重复出现在集合里也只计一次，避免集合语义外多算
        assertEquals(40, ProductServiceImpl.scoreOfFields(List.of("name", "name")));
    }

    @Test
    void 排序键只认白名单非法值回落() {
        Sort priceAsc = Sort.by(Sort.Direction.ASC, "price").and(ProductService.BY_SALES);
        assertEquals(priceAsc, ProductService.sortOf("price-asc", ProductService.BY_SALES));
        assertEquals(Sort.by(Sort.Direction.DESC, "price").and(ProductService.BY_SALES),
                ProductService.sortOf("price-desc", ProductService.BY_SALES));
        assertEquals(ProductService.BY_RECOMMENDED, ProductService.sortOf("new", ProductService.BY_SALES));
        assertEquals(ProductService.BY_SALES, ProductService.sortOf("sales", ProductService.BY_RECOMMENDED));
        // 折扣排序必须排在有划线价的款前面
        assertEquals(Sort.by(Sort.Direction.DESC, "originalPrice").and(ProductService.BY_SALES),
                ProductService.sortOf("discount", ProductService.BY_SALES));

        for (String illegal : List.of("createdAt; drop table product", "", "   ", "stock", "password", "default")) {
            assertEquals(ProductService.BY_SALES, ProductService.sortOf(illegal, ProductService.BY_SALES),
                    "非法排序键必须回落：" + illegal);
        }
        assertEquals(ProductService.BY_SALES, ProductService.sortOf(null, ProductService.BY_SALES));
        // relevance 的打分在 SQL 里，Sort 层给销量序兜底即可
        assertEquals(ProductService.BY_SALES, ProductService.sortOf("relevance", ProductService.BY_SALES));
        assertTrue(ProductService.SORT_KEYS.containsAll(ProductService.SORTS.keySet()));
    }

    @Test
    void 每页数量只接受白名单() {
        assertEquals(12, ProductService.limitOf(12));
        assertEquals(24, ProductService.limitOf(24));
        assertEquals(48, ProductService.limitOf(48));
        for (int illegal : List.of(0, -1, 1, 13, 47, 49, 100, 9999)) {
            assertEquals(12, ProductService.limitOf(illegal), "非白名单值应回落 12：" + illegal);
        }
    }

    @Test
    void 展示字段白名单去重并按候选顺序归一() {
        assertEquals(List.of("subtitle", "flowerLanguage"),
                ProductService.fieldsOf("flowerLanguage,subtitle,bogus"));
        assertEquals(List.of("careTip"), ProductService.fieldsOf("careTip，careTip"));
        assertEquals(List.of(), ProductService.fieldsOf(null));
        assertEquals(List.of(), ProductService.fieldsOf("  ,, ,, "));
        assertTrue(ProductService.fieldsOf("subtitle,sold,lowStock,careTip,flowerLanguage,priceRange,zzz")
                .size() <= ProductService.MAX_FIELDS);
    }

    @Test
    void 空关键词与纯标点不算有检索意图() {
        assertFalse(new ProductService.Query(null, List.of(), null, null, null, null, null, null, null, null)
                .hasKeyword());
        assertFalse(new ProductService.Query("   ", List.of(), null, null, null, null, null, null, null, null)
                .hasKeyword());
        assertTrue(new ProductService.Query("玫瑰", List.of(), null, null, null, null, null, null, null, null)
                .hasKeyword());
        // 老的十参构造默认不带展示字段，行为与升级前一致
        assertTrue(new ProductService.Query("玫瑰", List.of(), null, null, null, null, null, null, null, null)
                .fields().isEmpty());
    }

    @Test
    void 折扣文案只在真便宜时给出() {
        assertEquals("6.5 折", view("129.00", "199.00", 50, true).discountLabel());
        assertEquals("5 折", view("99.00", "199.00", 50, true).discountLabel());
        // 9.5 折以上不值得吆喝，给了反而是噪音
        assertNull(view("97.00", "100.00", 50, true).discountLabel());
        assertNull(view("100.00", null, 50, true).discountLabel());
        assertNull(view("199.00", "99.00", 50, true).discountLabel());
    }

    @Test
    void 库存告急阈值五束且下架商品不打标() {
        ProductView alert = view("100.00", null, 5, true);
        assertTrue(alert.lowStock());
        assertFalse(alert.soldOut());
        assertEquals(5, ProductView.LOW_STOCK_ALERT);

        ProductView plenty = view("100.00", null, 6, true);
        assertFalse(plenty.lowStock());

        ProductView empty = view("100.00", null, 0, true);
        assertTrue(empty.soldOut());

        // 下架款不再制造「仅剩 N 束」的紧迫感
        assertFalse(view("100.00", null, 2, false).lowStock());
    }

    @Test
    void 花语副标题与养护贴士透出到列表视图() {
        Product product = new Product();
        product.setName("红玫瑰花束");
        product.setPrice(new BigDecimal("199.00"));
        product.setSubtitle("11 枝云南红玫瑰 · 当日手打");
        product.setFlowerLanguage("一心一意");
        product.setCareTip("斜剪根 2-3 厘米");
        product.setSuitableFor("告白,纪念日，求婚");
        product.setOriginId(java.util.UUID.randomUUID());

        ProductView view = ProductView.from(product);
        assertEquals("11 枝云南红玫瑰 · 当日手打", view.subtitle());
        assertEquals("一心一意", view.flowerLanguage());
        assertEquals("斜剪根 2-3 厘米", view.careTip());
        assertTrue(view.careTipProvided());
        assertEquals(List.of("告白", "纪念日", "求婚"), view.suitableForList());
        assertEquals(product.getOriginId(), view.originId());
    }

    @Test
    void 未填养护贴士时视图标记为缺省内容() {
        Product product = new Product();
        product.setName("百合花束");
        product.setPrice(new BigDecimal("299.00"));
        product.setCareTip("   ");
        ProductView view = ProductView.from(product);
        assertFalse(view.careTipProvided());
        assertNull(view.discountLabel());
        assertTrue(view.suitableForList().isEmpty());
    }

    @Test
    void 场景与标签串归一为逗号分隔且去重保序() {
        assertEquals(List.of("生日", "告白"), ProductServiceImpl.splitTokens(" 生日 ,, 生日，告白 "));
        assertTrue(ProductServiceImpl.splitTokens(null).isEmpty());
        assertTrue(ProductServiceImpl.splitTokens("，，").isEmpty());
    }

    private static ProductView view(String price, String originalPrice, int stock, boolean active) {
        Product product = new Product();
        product.setName("测试花礼");
        product.setPrice(new BigDecimal(price));
        product.setOriginalPrice(originalPrice == null ? null : new BigDecimal(originalPrice));
        product.setStock(stock);
        product.setIsActive(active);
        return ProductView.from(product);
    }
}
