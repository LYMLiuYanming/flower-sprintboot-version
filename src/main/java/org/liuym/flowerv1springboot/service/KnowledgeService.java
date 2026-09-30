package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Article;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.vo.ContentViews.ArticleCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 花语/养护知识库（F15/F16）
 */
public interface KnowledgeService {

    /** 花材命中权重最高：文章 materials 里配置的词就是编辑给推荐用的关键词表 */
    int MATERIAL_WEIGHT = 5;
    /** 标签命中作为次级信号，避免「养护」这种通用词把不相干文章顶上去 */
    int TAG_WEIGHT = 2;
    int TOP_BONUS = 3;

    /**
     * F16 命中打分用的文本：把商品的「主花材 + 名称 + 标签 + 分类名 + 花语 + 适用场合」拼在一起。
     * 方向是「用文章配好的短词去命中商品文本」，比反过来给商品分词更可控。
     */
    static String haystackOf(Product product) {
        StringBuilder sb = new StringBuilder();
        appendToken(sb, product.getMaterial());
        appendToken(sb, product.getName());
        appendToken(sb, product.getTags());
        appendToken(sb, product.getFlowerLanguage());
        appendToken(sb, product.getSuitableFor());
        if (product.getCategory() != null) {
            appendToken(sb, product.getCategory().getName());
        }
        return sb.toString().toLowerCase();
    }

    static void appendToken(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(' ');
        }
    }

    /** 逗号、顿号、斜杠、分号与空白都算分隔符，编辑怎么填都不会漏 */
    static List<String> tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，、/;；\\s]+")) {
            String value = part.trim().toLowerCase();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * F16 单篇文章对某商品文本的命中分：0 表示不相关。
     * 置顶与排序值只作同分时的微调，编辑的运营意图不该盖过花材本身的相关性。
     */
    static int matchScore(String haystack, Article article) {
        int score = 0;
        for (String material : tokens(article.getMaterials())) {
            if (haystack.contains(material)) {
                score += MATERIAL_WEIGHT;
            }
        }
        for (String tag : tokens(article.getTags())) {
            if (haystack.contains(tag)) {
                score += TAG_WEIGHT;
            }
        }
        if (score == 0) {
            return 0;
        }
        if (Boolean.TRUE.equals(article.getIsTop())) {
            score += TOP_BONUS;
        }
        int sortOrder = article.getSortOrder() == null ? 0 : article.getSortOrder();
        return score + Math.min(Math.max(sortOrder, 0), 50) / 25;
    }

    Page<Article> searchPublished(String category, String keyword, String material, Pageable pageable);

    Page<Article> searchAdmin(String status, String category, String keyword, Pageable pageable);

    Optional<Article> findDisplayable(UUID id);

    Optional<Article> findById(UUID id);

    List<ArticleCategory> categories();

    Article createByForm(ContentDtos.ArticleForm form, String authorName);

    Article updateByForm(UUID id, ContentDtos.ArticleForm form);

    boolean updateStatus(UUID id, String status);

    boolean deleteById(UUID id);

    void incrementViewCount(UUID id);

    long count();

    long countPublished();

    /** F16：详情页侧栏按花材自动推荐——命中花材最多 → 同分类 → 最热，缺料时给通用养护兜底 */
    List<Article> recommendForProduct(UUID productId, int limit);

    /** 文章页底部的相关文章：同分类或共享花材 */
    List<Article> relatedOf(UUID articleId, int limit);

    /** 按文章里挂的关联商品 UUID 取在售商品（供文章页展示可下单的花礼） */
    List<UUID> relatedProductIdsOf(UUID articleId);
}
