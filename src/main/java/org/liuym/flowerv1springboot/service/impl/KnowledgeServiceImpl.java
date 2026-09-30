package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.HtmlSanitizer;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Article;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.ArticleRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.service.KnowledgeService;
import org.liuym.flowerv1springboot.vo.ContentViews.ArticleCategory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class KnowledgeServiceImpl implements KnowledgeService {

    /** F16 推荐池上限：知识库体量有限，一次取回打分比在 SQL 里做字符串匹配更可控 */
    private static final int RECOMMEND_POOL = 200;

    private static final String[] CATEGORY_CODES = {
            Article.CATEGORY_CARE, Article.CATEGORY_LANGUAGE, Article.CATEGORY_STORY,
            Article.CATEGORY_GUIDE, Article.CATEGORY_FESTIVAL};

    private static final String[] CATEGORY_LABELS = {
            "养护指南", "花语故事", "产地与花艺", "送礼手册", "节日特辑"};

    private final ArticleRepository articleRepository;
    private final ProductRepository productRepository;

    public KnowledgeServiceImpl(ArticleRepository articleRepository, ProductRepository productRepository) {
        this.articleRepository = articleRepository;
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Article> searchPublished(String category, String keyword, String material, Pageable pageable) {
        return articleRepository.searchPublished(safeCategory(category), trim(keyword), trim(material),
                LocalDateTime.now(), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Article> searchAdmin(String status, String category, String keyword, Pageable pageable) {
        return articleRepository.searchAdmin(trim(status), safeCategory(category), trim(keyword), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findDisplayable(UUID id) {
        return articleRepository.findById(id).filter(a -> a.displayableAt(LocalDateTime.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Article> findById(UUID id) {
        return articleRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArticleCategory> categories() {
        List<Object[]> rows = articleRepository.categoryCounts(LocalDateTime.now());
        List<ArticleCategory> out = new ArrayList<>();
        for (int i = 0; i < CATEGORY_CODES.length; i++) {
            // lambda 只能捕获最终变量，循环下标必须先落成局部 final
            final String code = CATEGORY_CODES[i];
            final String label = CATEGORY_LABELS[i];
            long count = rows.stream()
                    .filter(r -> code.equals(String.valueOf(r[0])))
                    .mapToLong(r -> ((Number) r[1]).longValue())
                    .findFirst().orElse(0L);
            out.add(new ArticleCategory(code, label, count));
        }
        return out;
    }

    @Override
    public Article createByForm(ContentDtos.ArticleForm form, String authorName) {
        Article article = new Article();
        applyForm(article, form, authorName);
        article.setViewCount(0);
        return articleRepository.save(article);
    }

    @Override
    public Article updateByForm(UUID id, ContentDtos.ArticleForm form) {
        Article article = articleRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("文章不存在"));
        applyForm(article, form, article.getAuthorName());
        return articleRepository.save(article);
    }

    @Override
    public boolean updateStatus(UUID id, String status) {
        if (!List.of(Article.STATUS_DRAFT, Article.STATUS_PUBLISHED, Article.STATUS_OFFLINE).contains(status)) {
            throw new BusinessException("状态取值不合法");
        }
        return articleRepository.updateStatus(id, status) > 0;
    }

    @Override
    public boolean deleteById(UUID id) {
        if (articleRepository.existsById(id)) {
            articleRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public void incrementViewCount(UUID id) {
        articleRepository.incrementViewCount(id);
    }

    @Override
    public long count() {
        return articleRepository.count();
    }

    @Override
    public long countPublished() {
        return articleRepository.countByStatus(Article.STATUS_PUBLISHED);
    }

    /**
     * F16：把商品的「花材 + 名称 + 标签 + 分类名 + 花语」合成一段文本，
     * 用文章里维护好的花材关键词去反向命中——关键词表由编辑控制、数量少，
     * 比分词商品文本更稳，也不会因为商品名里一个「love」就命中玫瑰文章。
     */
    @Override
    @Transactional(readOnly = true)
    public List<Article> recommendForProduct(UUID productId, int limit) {
        int size = Math.min(Math.max(limit, 1), 8);
        Product product = productRepository.findById(productId).orElse(null);
        if (product == null) {
            return List.of();
        }
        String haystack = KnowledgeService.haystackOf(product);
        List<Article> pool = articleRepository.findDisplayable(LocalDateTime.now(),
                PageRequest.of(0, RECOMMEND_POOL));

        record Scored(Article article, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Article article : pool) {
            // 相关性由 KnowledgeService.matchScore 统一定义，测试也打这一个口子
            int score = KnowledgeService.matchScore(haystack, article);
            if (score > 0) {
                score += Math.min(article.getViewCount() == null ? 0 : article.getViewCount(), 100) / 100;
                scored.add(new Scored(article, score));
            }
        }
        List<Article> hits = scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(s -> s.article().getTitle()))
                .map(Scored::article)
                .limit(size)
                .toList();
        if (hits.size() >= size) {
            return hits;
        }
        // 花材没命中也要给读者能用的内容：用全站最热养护文兜底补齐，侧栏不会出现空壳
        Set<UUID> picked = new LinkedHashSet<>();
        hits.forEach(a -> picked.add(a.getId()));
        List<Article> filled = new ArrayList<>(hits);
        pool.stream()
                .filter(a -> Article.CATEGORY_CARE.equals(a.getCategory()) || a.getViewCount() > 0)
                .filter(a -> !picked.contains(a.getId()))
                .sorted(Comparator.comparing(Article::getViewCount).reversed())
                .limit(size - filled.size())
                .forEach(filled::add);
        return filled;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Article> relatedOf(UUID articleId, int limit) {
        Article self = articleRepository.findById(articleId).orElse(null);
        if (self == null) {
            return List.of();
        }
        String firstMaterial = tokens(self.getMaterials()).stream().findFirst().orElse("");
        return articleRepository.findRelated(articleId, self.getCategory(), firstMaterial,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), 10),
                                Sort.by(Sort.Direction.DESC, "viewCount")))
                .stream().toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> relatedProductIdsOf(UUID articleId) {
        return articleRepository.findById(articleId)
                .map(a -> parseIds(a.getRelatedProductIds()))
                .orElse(List.of());
    }

    // ------------------------------------------------------------------

    private void applyForm(Article article, ContentDtos.ArticleForm form, String authorName) {
        String content = HtmlSanitizer.clean(form.content());
        String plain = HtmlSanitizer.text(content);
        if (plain.isBlank()) {
            throw new BusinessException("正文不能为空");
        }
        if (form.publishAt() != null && form.offlineAt() != null && !form.offlineAt().isAfter(form.publishAt())) {
            throw new BusinessException("下线时间必须晚于发布时间");
        }
        String status = form.status() == null || form.status().isBlank() ? Article.STATUS_DRAFT : form.status();
        // 定时发布：状态先落 published、可见性交给 publishAt 判定，到点自动出现在前台，不需要定时任务改状态
        article.setPublishAt(form.publishAt());
        article.setTitle(form.title().trim());
        article.setSummary(blankToNull(form.summary()) == null ? autoSummary(plain) : trim(form.summary()));
        article.setCoverImage(safeOptionalUrl(form.coverImage()));
        article.setContent(content);
        article.setCategory(safeCategory(form.category()));
        article.setTags(joinTokens(form.tags()));
        article.setMaterials(joinTokens(form.materials()));
        article.setRelatedProductIds(joinIds(keepExistingProducts(form.relatedProductIds())));
        article.setStatus(status);
        article.setOfflineAt(form.offlineAt());
        article.setIsTop(Boolean.TRUE.equals(form.isTop()));
        article.setSortOrder(form.sortOrder() == null ? 0 : form.sortOrder());
        article.setAuthorName(blankToNull(authorName) == null ? "花艺组" : authorName);
    }

    /** 摘要留空时从正文首段取一句，后台不必为了卡片好看再手写一遍 */
    private static String autoSummary(String plainText) {
        return plainText.length() <= 100 ? plainText : plainText.substring(0, 99) + "…";
    }

    /** 花材/标签的分词口径与推荐打分共用同一实现，避免两处规则漂移 */
    private static List<String> tokens(String raw) {
        return KnowledgeService.tokens(raw);
    }

    private static String joinTokens(String raw) {
        List<String> tokens = tokens(raw);
        if (tokens.isEmpty()) {
            return null;
        }
        String joined = String.join(",", tokens);
        return joined.length() <= 300 ? joined : joined.substring(0, 299);
    }

    /** 只保留库里真实存在的商品：文章挂一个已删除的商品，前台会渲染出一张打不开的卡片 */
    private List<UUID> keepExistingProducts(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Set<UUID> existing = new LinkedHashSet<>();
        productRepository.findAllById(ids).forEach(p -> existing.add(p.getId()));
        List<UUID> missing = ids.stream().filter(id -> !existing.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("关联商品不存在或已删除：" + missing.size() + " 个");
        }
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private static String joinIds(List<UUID> ids) {
        return ids == null || ids.isEmpty() ? null : String.join(",", ids.stream().map(UUID::toString).toList());
    }

    private static List<UUID> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                if (!part.isBlank()) {
                    out.add(UUID.fromString(part.trim()));
                }
            } catch (IllegalArgumentException ignored) {
                // 脏 UUID 忽略即可，不要让一篇文章打不开
            }
        }
        return out;
    }

    private static String safeCategory(String category) {
        String value = trim(category);
        for (String code : CATEGORY_CODES) {
            if (code.equals(value)) {
                return code;
            }
        }
        return "";
    }

    private static String safeOptionalUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SafeUrl.requireSafe(value, "封面图地址");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
