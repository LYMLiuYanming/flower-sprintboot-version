package org.liuym.flowerv1springboot.content;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.model.Article;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.KnowledgeService;
import org.liuym.flowerv1springboot.vo.ContentViews.ArticleView;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库（F15）与详情页花材推荐（F16）的打分与状态判定。
 * 命中方向是「用编辑配好的花材短词去命中商品文本」，规则一旦改动，
 * 详情页侧栏会出现不相干的文章，这里先把住关。
 */
class KnowledgeRecommendTest {

    @Test
    void 分词覆盖中英文逗号顿号斜杠与空白() {
        assertEquals(List.of("玫瑰", "康乃馨"), KnowledgeService.tokens("玫瑰,康乃馨"));
        assertEquals(List.of("玫瑰", "百合"), KnowledgeService.tokens("玫瑰、百合 "));
        assertEquals(List.of("rose"), KnowledgeService.tokens("ROSE"));
        assertEquals(List.of(), KnowledgeService.tokens(null));
        // 重复词只留一次，避免同一关键词把分数翻倍
        assertEquals(2, KnowledgeService.tokens("玫瑰,玫瑰,百合").size());
    }

    @Test
    void 商品文本把花材名称分类与花语都拼进来() {
        Product product = product("玫瑰,百合", "情人节红玫瑰花束", "浪漫,告白", "爱情与热情");
        String haystack = KnowledgeService.haystackOf(product);
        assertTrue(haystack.contains("玫瑰"));
        assertTrue(haystack.contains("百合"));
        assertTrue(haystack.contains("告白"));
        assertTrue(haystack.contains("爱情与热情"));
        assertTrue(haystack.contains("玫瑰系列"), "分类名也要参与命中：" + haystack);
    }

    @Test
    void 主花材命中得分高于标签命中() {
        String haystack = KnowledgeService.haystackOf(product("玫瑰", "红玫瑰 11 支", "浪漫", null));
        Article byMaterial = article("玫瑰养护指南", "玫瑰", "养护", false, 0);
        Article byTag = article("夏季送花手册", "康乃馨", "玫瑰", false, 0);
        int materialScore = KnowledgeService.matchScore(haystack, byMaterial);
        int tagScore = KnowledgeService.matchScore(haystack, byTag);
        assertTrue(materialScore > tagScore, "花材命中 " + materialScore + " 应高于标签命中 " + tagScore);
        assertEquals(KnowledgeService.MATERIAL_WEIGHT, materialScore);
    }

    @Test
    void 完全不相关的文章得零分() {
        String haystack = KnowledgeService.haystackOf(product("向日葵", "阳光向日葵", "毕业", null));
        assertEquals(0, KnowledgeService.matchScore(haystack, article("百合开花全过程", "百合", "花粉", false, 0)));
    }

    @Test
    void 置顶与排序只在同分时微调不影响相关性判定() {
        String haystack = KnowledgeService.haystackOf(product("玫瑰", "红玫瑰", null, null));
        int plain = KnowledgeService.matchScore(haystack, article("玫瑰怎么养", "玫瑰", null, false, 0));
        int topped = KnowledgeService.matchScore(haystack, article("玫瑰怎么养", "玫瑰", null, true, 60));
        assertEquals(KnowledgeService.MATERIAL_WEIGHT + KnowledgeService.TOP_BONUS + 2, topped);
        assertTrue(topped > plain);
    }

    @Test
    void 分值为零时置顶也救不回来() {
        String haystack = KnowledgeService.haystackOf(product("郁金香", "郁金香花束", null, null));
        assertEquals(0, KnowledgeService.matchScore(haystack, article("郁金香的种球处理", "玫瑰", null, true, 99)));
    }

    @Test
    void 文章状态与定时发布共同决定前台可见性() {
        LocalDateTime now = LocalDateTime.now();
        Article draft = article("草稿", "玫瑰", null, false, 0);
        draft.setStatus(Article.STATUS_DRAFT);
        assertFalse(draft.displayableAt(now), "草稿默认不可见");

        draft.setStatus(Article.STATUS_PUBLISHED);
        draft.setPublishAt(now.plusDays(1));
        assertFalse(draft.displayableAt(now), "定时发布未到点不可见");
        assertTrue(draft.displayableAt(now.plusDays(2)));

        draft.setPublishAt(now.minusDays(1));
        draft.setOfflineAt(now.minusHours(1));
        assertFalse(draft.displayableAt(now), "过了下线时间要自动消失");

        draft.setOfflineAt(null);
        draft.setStatus(Article.STATUS_OFFLINE);
        assertFalse(draft.displayableAt(now), "手动下线优先于时间窗");
    }

    /** 列表形态不带正文：一次拉 9 篇不必把全文搬到接口响应里 */
    @Test
    void 文章视图按形态决定是否带正文() {
        Article article = article("玫瑰怎么养", "玫瑰,康乃馨", "养护,瓶插期", true, 60);
        article.setId(UUID.randomUUID());
        article.setSummary("四步让玫瑰多开五天");
        article.setContent("<p>先醒花再修剪</p>");
        article.setRelatedProductIds(UUID.randomUUID() + "," + "不是uuid");
        article.setCreatedAt(LocalDateTime.now());
        article.setViewCount(12);

        ArticleView list = ArticleView.from(article, false);
        assertNull(list.content());
        assertEquals("花语故事", list.categoryLabel());
        assertEquals(List.of("玫瑰", "康乃馨"), list.materials());
        assertEquals(1, list.relatedProductIds().size(), "脏 UUID 被忽略而不是让整篇打不开");

        ArticleView detail = ArticleView.from(article, true);
        assertEquals("<p>先醒花再修剪</p>", detail.content());
        assertEquals("live", detail.state());
        assertEquals(12, detail.viewCount());
    }

    @Test
    void 未发布文章的视图状态可区分草稿与定时() {
        LocalDateTime now = LocalDateTime.now();
        Article draft = article("新稿", "玫瑰", null, false, 0);
        draft.setStatus(Article.STATUS_DRAFT);
        assertEquals("draft", ArticleView.from(draft, false).state());

        draft.setStatus(Article.STATUS_PUBLISHED);
        draft.setPublishAt(now.plusDays(2));
        assertEquals("scheduled", ArticleView.from(draft, false).state());

        draft.setStatus(Article.STATUS_OFFLINE);
        assertEquals("offline", ArticleView.from(draft, false).state());
    }

    private static Article article(String title, String materials, String tags, boolean top, int sortOrder) {
        Article article = new Article();
        article.setId(UUID.randomUUID());
        article.setTitle(title);
        article.setMaterials(materials);
        article.setTags(tags);
        article.setCategory("language");
        article.setStatus(Article.STATUS_PUBLISHED);
        article.setIsTop(top);
        article.setSortOrder(sortOrder);
        article.setViewCount(0);
        return article;
    }

    private static Product product(String material, String name, String tags, String flowerLanguage) {
        Category category = new Category();
        category.setId(UUID.randomUUID());
        category.setName("玫瑰系列");

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setMaterial(material);
        product.setName(name);
        product.setTags(tags);
        product.setFlowerLanguage(flowerLanguage);
        product.setPrice(BigDecimal.valueOf(199));
        product.setCategory(category);
        return product;
    }
}
