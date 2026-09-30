package org.liuym.flowerv1springboot.content;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.Review;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewCard;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评价写入校验与展示口径（F01/F02/F05/F06）的纯逻辑测试：
 * 标签白名单、图片地址白名单、正文净化、匿名脱敏都在服务端定死，
 * 这几条一旦被人放宽，晒单广场就会成为 XSS 与图床外链的入口。
 */
class ReviewContentLogicTest {

    @Test
    void 标签字典编码唯一且都有中文名() {
        List<String> codes = ReviewService.TAGS.stream().map(ReviewService.ReviewTag::code).toList();
        assertEquals(codes.size(), List.copyOf(codes).stream().distinct().count(), "标签编码不能重复");
        assertTrue(codes.containsAll(List.of("fresh", "packaging", "ontime")), "常用标签不能少");
        ReviewService.TAGS.forEach(t -> assertFalse(t.label().isBlank(), t.code() + " 缺少中文名"));
        assertEquals("花材新鲜", ReviewService.tagLabel("fresh"));
        // 字典外的历史编码不报错，原样回显，后台才看得出是脏数据
        assertEquals("legacy", ReviewService.tagLabel("legacy"));
    }

    @Test
    void 非法标签编码被拒绝而非静默丢弃() {
        assertEquals("fresh,ontime", ReviewService.normalizeTags(List.of("fresh", "ONTIME")));
        assertNull(ReviewService.normalizeTags(List.of()));
        assertNull(ReviewService.normalizeTags(null));
        BusinessException e = assertThrows(BusinessException.class,
                () -> ReviewService.normalizeTags(List.of("fresh", "刷单")));
        assertTrue(e.getMessage().contains("刷单"), e.getMessage());
    }

    @Test
    void 标签最多六个且去重() {
        String joined = ReviewService.normalizeTags(
                List.of("fresh", "fresh", "packaging", "ontime", "beauty", "service", "value", "fresh"));
        assertEquals(6, joined.split(",").length, joined);
    }

    @Test
    void 图片只收站内路径并按上限截断() {
        assertEquals("/review-photo/rv_a.jpg",
                ReviewService.normalizeImages(List.of(" /review-photo/rv_a.jpg ", ""), 9));
        assertNull(ReviewService.normalizeImages(null, 9));
        // 超出上限的尾部丢弃，与前端选择器的 9 张上限同口径
        List<String> many = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> "/review-photo/rv_" + i + ".png").toList();
        assertEquals(9, ReviewService.normalizeImages(many, 9).split(",").length);
    }

    @Test
    void 外链图片与伪协议地址一律拒绝() {
        assertThrows(BusinessException.class,
                () -> ReviewService.normalizeImages(List.of("https://img外链.com/a.jpg"), 9));
        assertThrows(BusinessException.class,
                () -> ReviewService.normalizeImages(List.of("javascript:alert(1)"), 9));
        assertThrows(BusinessException.class,
                () -> ReviewService.normalizeImages(List.of("data:image/png;base64,AAAA"), 9));
    }

    @Test
    void 评价正文入库前剥掉全部标签() {
        assertEquals("花很新鲜", ReviewService.plainText("<p>花很新鲜</p>"));
        String cleaned = ReviewService.plainText("<img src=x onerror=alert(1)>花很新鲜");
        assertFalse(cleaned.contains("<"), cleaned);
        assertFalse(cleaned.contains("onerror"), cleaned);
        assertTrue(cleaned.contains("花很新鲜"), cleaned);
        // 已经转义过的伪标签同样不会带着尖括号进库，前端再 text() 一次也不会拼出可执行片段
        assertFalse(ReviewService.plainText("好花 &lt;script&gt;alert(1)&lt;/script&gt;").contains("<"));
        assertEquals("", ReviewService.plainText(null));
        assertEquals(500, ReviewService.plainText("字".repeat(900)).length(), "超长正文按 500 字截断");
    }

    @Test
    void 标签筛选串只对字典内编码生效() {
        assertEquals("%,fresh,%", ReviewService.tagPattern("FRESH"));
        assertEquals("", ReviewService.tagPattern(""));
        assertEquals("", ReviewService.tagPattern("不存在"));
    }

    @Test
    void 匿名评价不输出任何可回溯的昵称() {
        ReviewCard card = ReviewCard.from(review("张三丰", true));
        assertEquals("匿名顾客", card.userName());
        assertTrue(card.anonymous());
    }

    @Test
    void 实名评价昵称做掩码且补齐标签中文名与图片数组() {
        Review review = review("李雷", false);
        review.setTags("fresh,ontime,legacy");
        review.setImages("/review-photo/rv_1.png,/review-photo/rv_2.png");
        ReviewCard card = ReviewCard.from(review);
        assertEquals("李**雷", card.userName());
        assertEquals(List.of("fresh", "ontime", "legacy"), card.tags());
        assertEquals(List.of("花材新鲜", "准时送达", "legacy"), card.tagLabels());
        assertEquals(2, card.images().size());
        assertEquals("红玫瑰 11 支", card.productName());
        assertEquals("玫瑰", card.categoryName());
    }

    @Test
    void 时间线字段按实体原样透出供前端排序() {
        Review review = review("王小明", false);
        review.setAppendContent("第三天还是硬挺");
        review.setAppendImages("/review-photo/rv_3.png");
        review.setAppendAt(java.time.LocalDateTime.now());
        review.setReply("感谢反馈，已同步给门店");
        review.setReplyByName("flower_shop");
        ReviewCard card = ReviewCard.from(review);
        assertEquals("第三天还是硬挺", card.appendContent());
        assertEquals(List.of("/review-photo/rv_3.png"), card.appendImages());
        assertEquals("感谢反馈，已同步给门店", card.reply());
        assertEquals("flower_shop", card.replyByName());
        assertFalse(card.visible());
    }

    /** 内存对象即可，这些判定都不依赖数据库 */
    private static Review review(String fullName, boolean anonymous) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setFullName(fullName);
        user.setUsername(fullName);

        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("玫瑰");

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("红玫瑰 11 支");
        product.setMainImage("/img/products/rose-red.svg");
        product.setCategory(category);

        Review review = new Review();
        review.setId(UUID.randomUUID());
        review.setUser(user);
        review.setProduct(product);
        review.setRating(4);
        review.setContent("花很新鲜");
        review.setAnonymous(anonymous);
        review.setVisible(false);
        review.setCreatedAt(java.time.LocalDateTime.now());
        return review;
    }
}
