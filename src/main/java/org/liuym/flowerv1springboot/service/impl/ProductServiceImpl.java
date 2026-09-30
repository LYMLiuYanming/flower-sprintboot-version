package org.liuym.flowerv1springboot.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.BatchPricePolicy;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.common.SearchTokenizer;
import org.liuym.flowerv1springboot.dto.ProductDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.AdminQueryRepository;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.FavoriteRepository;
import org.liuym.flowerv1springboot.repository.FlowerOriginRepository;
import org.liuym.flowerv1springboot.repository.OrderItemRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    /** LIKE 转义符：与 escapeLike 中的处理配套，防止用户输入 % / _ 变成通配 */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * A09 命中字段权重表：顺序即优先级。相关度打分（relevance）与命中判定（termHit）共用这一份定义，
     * 否则会出现「靠描述排到第一，但其实没命中描述」的口径漂移
     */
    record ScoringField(String attribute, int weight, boolean nullable) {
    }

    static final List<ScoringField> SCORING_FIELDS = List.of(
            new ScoringField("name", 40, false),
            new ScoringField("material", 16, true),
            new ScoringField("tags", 12, true),
            new ScoringField("suitableFor", 8, true),
            new ScoringField("flowerLanguage", 6, true),
            new ScoringField("description", 4, true));

    /** 分类名命中最轻：搜「玫瑰」时不希望整簇玫瑰系列只因为挂在玫瑰分类就顶到前面 */
    static final int CATEGORY_WEIGHT = 2;

    static final int SIMILAR_PRICE_SPREAD_PERMILLE = 400;

    /** 价格带放大后的第二档：±40% 内不足一屏时放宽到 ±80%，而不是直接给同类目随机补齐 */
    static final int SIMILAR_PRICE_SPREAD_WIDE_PERMILLE = 800;

    private static final DateTimeFormatter CODE_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    /** 库存上限与 ProductDtos.Form 的 @Max 一致：批量调库存不能绕过单条编辑的上限 */
    private static final int MAX_STOCK = 999999;

    /** 单次批量上限：再多就该分批，否则一个事务要锁住上千行还回不来逐条明细 */
    private static final int MAX_BATCH_SIZE = 200;

    /** 批量操作要用到的商品快照（见 snapshots()） */
    private record Snapshot(String name, String code, BigDecimal price, BigDecimal originalPrice,
                            Integer stock, Boolean isActive) {
    }

    /** 手填编码的合法字符：字母数字开头，允许 . _ -，堵住把 HTML/脚本塞进编码字段的路 */
    private static final java.util.regex.Pattern CODE_PATTERN =
            java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9._-]{1,49}$");

    /** 纯函数：命中字段集合 → 相关度分值，供 relevance() 组表达式与单测共用同一权重口径 */
    static int scoreOfFields(Collection<String> hitAttributes) {
        int score = 0;
        for (ScoringField field : SCORING_FIELDS) {
            if (hitAttributes.contains(field.attribute())) {
                score += field.weight();
            }
        }
        if (hitAttributes.contains("category")) {
            score += CATEGORY_WEIGHT;
        }
        return score;
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private FlowerOriginRepository flowerOriginRepository;

    /** 订单/评价/收藏的引用计数只读查询：跨表统计集中在这一处，避免各处散落 native SQL */
    @Autowired
    private AdminQueryRepository adminQueryRepository;

    /** 价格分布聚合用：Specification 只能表达行过滤，min/max 需要一条独立的聚合查询 */
    @Autowired
    private EntityManager entityManager;

    @Override
    public Product save(Product product) {
        if (product.getStock() == null) {
            product.setStock(0);
        }
        if (product.getSalesCount() == null) {
            product.setSalesCount(0);
        }
        if (product.getReviewCount() == null) {
            product.setReviewCount(0);
        }
        if (product.getIsActive() == null) {
            product.setIsActive(true);
        }
        if (product.getIsFeatured() == null) {
            product.setIsFeatured(false);
        }
        if (product.getIsNew() == null) {
            product.setIsNew(false);
        }
        if (product.getUnit() == null) {
            product.setUnit("束");
        }
        return productRepository.save(product);
    }

    @Override
    public List<Product> saveAll(List<Product> products) {
        return productRepository.saveAll(products);
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return productRepository.findById(id);
    }

    @Override
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Override
    public Page<Product> findByPage(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    @Override
    public Page<Product> findByActivePage(Pageable pageable) {
        return productRepository.findByIsActiveTrue(pageable);
    }

    @Override
    public Page<Product> findByCategoryId(UUID categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdInAndIsActiveTrue(categoryRepository.idsWithChildren(categoryId), pageable);
    }

    @Override
    public List<Product> findFeaturedProducts(int limit) {
        return productRepository.findByIsFeaturedTrueAndIsActiveTrue(PageRequest.of(0, limit));
    }

    @Override
    public List<Product> findNewProducts(int limit) {
        return productRepository.findByIsNewTrueAndIsActiveTrue(PageRequest.of(0, limit));
    }

    @Override
    public List<Product> findBestSellers(UUID categoryId, int limit) {
        return productRepository.findBestSellers(categoryId, PageRequest.of(0, limit));
    }

    @Override
    public Page<Product> searchByName(String name, Pageable pageable) {
        return productRepository.findByNameContainingAndIsActiveTrue(name, pageable);
    }

    @Override
    public Page<Product> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.findByPriceRange(minPrice, maxPrice, pageable);
    }

    @Override
    public Product update(Product product) {
        if (!productRepository.existsById(product.getId())) {
            throw new RuntimeException("商品不存在，更新失败！商品ID：" + product.getId());
        }
        return productRepository.save(product);
    }

    @Override
    public boolean updateActiveStatus(UUID id, Boolean isActive) {
        int affectedRows = productRepository.updateActiveStatus(id, isActive);
        return affectedRows > 0;
    }

    @Override
    public boolean reduceStock(UUID id, Integer quantity) {
        int affectedRows = productRepository.reduceStock(id, quantity);
        return affectedRows > 0;
    }

    @Override
    public boolean increaseStock(UUID id, Integer quantity) {
        int affectedRows = productRepository.increaseStock(id, quantity);
        return affectedRows > 0;
    }

    @Override
    public boolean increaseSalesCount(UUID id, Integer count) {
        int affectedRows = productRepository.increaseSalesCount(id, count);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    public boolean deleteById(UUID id) {
        if (productRepository.existsById(id)) {
            productRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public long count() {
        return productRepository.count();
    }

    @Override
    public long countActive() {
        return productRepository.countByIsActiveTrue();
    }

    @Override
    public long countByCategory(UUID categoryId) {
        return productRepository.countByCategoryId(categoryId);
    }

    @Override
    public Page<Product> searchAdmin(UUID categoryId, Boolean isActive, Boolean lowStockOnly,
                                     String keyword, Pageable pageable) {
        // 空关键词以空串下传：LIKE 参数为 null 时 PostgreSQL 推断不出类型
        String kw = keyword == null ? "" : keyword.trim();
        return productRepository.searchAdmin(categoryId, isActive,
                Boolean.TRUE.equals(lowStockOnly) ? Boolean.TRUE : null, kw, pageable);
    }

    @Override
    public long countLowStock() {
        return productRepository.countLowStockActive();
    }

    @Override
    public Page<Product> searchActive(String keyword, Pageable pageable) {
        List<String> terms = SearchTokenizer.terms(keyword);
        if (terms.isEmpty()) {
            return productRepository.findByIsActiveTrue(pageable);
        }
        Page<Product> matched = productRepository.findAll(activeMatching(terms, true), pageable);
        // 逐词 AND 命中为空时放宽为「任一词命中」，多词查询不至于直接给空结果
        if (matched.hasContent() || terms.size() == 1) {
            return matched;
        }
        return productRepository.findAll(activeMatching(terms, false), pageable);
    }

    @Override
    public Page<Product> browse(Query query, Pageable pageable) {
        return productRepository.findAll(browsing(query), pageable);
    }

    /**
     * A11：当前筛选条件下的真实价格分布。命中为空时回落全站在售区间，
     * 让页面价格滑块始终有可信上下界，而不是显示 ¥0-0
     */
    @Override
    public BigDecimal[] priceStats(Query query) {
        BigDecimal[] scoped = aggregatePrice(browsing(query));
        if (scoped != null) {
            return scoped;
        }
        return aggregatePrice((root, criteria, cb) -> cb.isTrue(root.get("isActive")));
    }

    private BigDecimal[] aggregatePrice(Specification<Product> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<BigDecimal[]> criteria = cb.createQuery(BigDecimal[].class);
        Root<Product> root = criteria.from(Product.class);
        criteria.multiselect(cb.min(root.<BigDecimal>get("price")), cb.max(root.<BigDecimal>get("price")));
        // 聚合查询的 resultType 不是 Product，keywordMatching 里的相关度排序不会被加进来
        criteria.where(spec.toPredicate(root, criteria, cb));
        try {
            BigDecimal[] row = entityManager.createQuery(criteria).getSingleResult();
            return row != null && row[0] != null && row[1] != null ? row : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A10：无结果兜底。优先级——已选分类 → 关键词猜中的分类 → 全站热卖，
     * 并沿用「有货 + 销量优先」两条硬规则，保证递出去的都是当下真能买到的
     */
    @Override
    public List<Product> browseFallback(Query query, int limit) {
        List<UUID> scope = fallbackCategoryScope(query);
        Specification<Product> spec = (root, criteria, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isTrue(root.get("isActive")));
            ps.add(cb.greaterThan(root.get("stock"), 0));
            if (!scope.isEmpty()) {
                ps.add(root.get("category").get("id").in(scope));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return productRepository.findAll(spec, PageRequest.of(0, Math.min(Math.max(limit, 1), 12),
                        Sort.by(Sort.Direction.DESC, "salesCount").and(Sort.by(Sort.Direction.DESC, "rating"))))
                .getContent();
    }

    private List<UUID> fallbackCategoryScope(Query query) {
        List<UUID> scope = new ArrayList<>();
        if (query.categoryIds() != null) {
            query.categoryIds().forEach(cid -> scope.addAll(categoryRepository.idsWithChildren(cid)));
        }
        if (!scope.isEmpty() || !query.hasKeyword()) {
            return scope;
        }
        // 搜不到商品时把关键词当分类名试一次：搜「郁金香」至少能递出郁金香分类的热卖
        List<String> terms = SearchTokenizer.terms(query.keyword());
        if (terms.isEmpty()) {
            return scope;
        }
        String probe = terms.get(0).toLowerCase(Locale.ROOT);
        categoryRepository.findByIsActiveTrueOrderBySortOrderAsc().stream()
                .filter(c -> c.getName() != null && c.getName().toLowerCase(Locale.ROOT).contains(probe))
                .forEach(c -> scope.addAll(categoryRepository.idsWithChildren(c.getId())));
        return scope;
    }

    @Override
    public List<Map<String, Object>> originOptions() {
        return flowerOriginRepository.findByKindAndIsActiveTrueOrderBySortOrderAsc("origin").stream()
                .map(origin -> {
                    Map<String, Object> option = new LinkedHashMap<String, Object>();
                    option.put("id", origin.getId());
                    option.put("name", origin.getName());
                    option.put("province", origin.getProvince());
                    option.put("flowers", origin.getFlowers());
                    return option;
                }).toList();
    }

    /** 预填展示用：真正保存时按当时的当日流水重算，所以这里允许与最终值差一号 */
    @Override
    public String previewCode() {
        return nextGeneratedCode();
    }

    /**
     * 每个筛选项都是「给了才加，没给跳过」，且价格区间允许只填一端；
     * 关键词沿用既有的分词命中逻辑，避免列表页与搜索框两套检索语义
     */
    private Specification<Product> browsing(Query q) {
        Specification<Product> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.isTrue(root.get("isActive")));
            if (q.minPrice() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("price"), q.minPrice()));
            }
            if (q.maxPrice() != null) {
                ps.add(cb.lessThanOrEqualTo(root.get("price"), q.maxPrice()));
            }
            if (Boolean.TRUE.equals(q.inStockOnly())) {
                ps.add(cb.greaterThan(root.get("stock"), 0));
            }
            if (Boolean.TRUE.equals(q.featured())) {
                ps.add(cb.isTrue(root.get("isFeatured")));
            }
            if (Boolean.TRUE.equals(q.newOnly())) {
                ps.add(cb.isTrue(root.get("isNew")));
            }
            if (q.originId() != null) {
                ps.add(cb.equal(root.get("originId"), q.originId()));
            }
            if (notBlank(q.tag())) {
                ps.add(cb.like(cb.lower(root.get("tags")), likeOf(q.tag()), LIKE_ESCAPE));
            }
            if (notBlank(q.scene())) {
                ps.add(cb.like(root.get("suitableFor"), likeOf(q.scene()), LIKE_ESCAPE));
            }
            if (q.categoryIds() != null && !q.categoryIds().isEmpty()) {
                List<UUID> ids = new ArrayList<>();
                q.categoryIds().forEach(cid -> ids.addAll(categoryRepository.idsWithChildren(cid)));
                ps.add(root.get("category").get("id").in(ids));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        if (notBlank(q.keyword())) {
            spec = spec.and(keywordMatching(q.keyword()));
        }
        // 勾选展示列＝只看「该列有内容」的款：勾了花语却半数商品没填花语时，页面不会一片空白
        for (String field : fieldsOrDefault(q.fields())) {
            if (!"priceRange".equals(field) && !"sold".equals(field) && !"lowStock".equals(field)) {
                spec = spec.and(hasText(field));
            }
        }
        return spec;
    }

    /** 展示字段 → 该字段有内容（null 与空串都算没内容，coalesce 已经把 null 消化掉） */
    private static Specification<Product> hasText(String attribute) {
        return (root, query, cb) -> cb.notEqual(cb.coalesce(root.<String>get(attribute), ""), "");
    }

    /** 没勾任何展示字段时不收窄结果集，sold / lowStock 只是展示口径而非筛选语义 */
    private static List<String> fieldsOrDefault(List<String> fields) {
        return fields == null ? List.of() : fields;
    }

    /**
     * 关键词命中 + 相关度排序：商品名命中得 40 分、花材 16、标签 12、场景 8、花语 6、描述 4，
     * 再叠一点销量做同分裁决。此前搜「玫瑰」时描述里提过玫瑰的郁金香会排在真玫瑰前面，
     * 就是因为没有位置权重，只按销量排
     */
    private Specification<Product> keywordMatching(String keyword) {
        return (root, query, cb) -> {
            List<String> terms = SearchTokenizer.terms(keyword);
            if (terms.isEmpty()) {
                return cb.conjunction();
            }
            Join<Product, Category> category = root.join("category", JoinType.LEFT);
            if (canSort(query)) {
                // 相关度只按主词加权：多词时其余词靠 OR 兜底命中，排序仍回答「我搜的那个词谁最像」
                Expression<Integer> score = relevance(cb, root, cb.lower(category.get("name")), likeOf(terms.get(0)));
                query.orderBy(cb.desc(score), cb.desc(root.get("salesCount")), cb.desc(root.get("createdAt")));
            }
            return orOf(terms.stream().map(term -> termHit(root, category, cb, term)).toList(), cb);
        };
    }

    /**
     * 命中字段越靠前权重越高：商品名 40 > 花材 16 > 标签 12 > 场景 8 > 花语 6 > 描述 4 > 分类名 2。
     * CriteriaBuilder 的 sum 只接两个操作数，所以逐项累加而不是一次传六个 case 表达式。
     */
    private static Expression<Integer> relevance(CriteriaBuilder cb, Root<Product> root,
                                                 Expression<String> categoryName, String like) {
        Expression<Integer> score = cb.literal(0);
        for (ScoringField field : SCORING_FIELDS) {
            score = cb.sum(score, cb.<Integer>selectCase()
                    .when(cb.like(textOf(cb, root, field), like, LIKE_ESCAPE), cb.literal(field.weight()))
                    .otherwise(cb.literal(0)));
        }
        return cb.sum(score, cb.<Integer>selectCase()
                .when(cb.like(categoryName, like, LIKE_ESCAPE), cb.literal(CATEGORY_WEIGHT))
                .otherwise(cb.literal(0)));
    }

    /** 小写比对 + NULL 兜底：nullable 字段（花材/标签/花语…）为空时不能让整个 CASE 表达式变成 NULL */
    private static Expression<String> textOf(CriteriaBuilder cb, Root<Product> root, ScoringField field) {
        Path<String> path = root.get(field.attribute());
        return cb.lower(field.nullable() ? cb.coalesce(path, "") : path);
    }

    /** 命中字段与加权字段共用 SCORING_FIELDS，否则会出现「排第一但没命中该字段」的口径漂移 */
    private Predicate termHit(Root<Product> root, Join<Product, Category> category, CriteriaBuilder cb, String term) {
        String like = likeOf(term);
        List<Predicate> hits = new ArrayList<>();
        for (ScoringField field : SCORING_FIELDS) {
            hits.add(cb.like(textOf(cb, root, field), like, LIKE_ESCAPE));
        }
        hits.add(cb.like(cb.lower(category.get("name")), like, LIKE_ESCAPE));
        return orOf(hits, cb);
    }

    private static Predicate orOf(List<Predicate> predicates, CriteriaBuilder cb) {
        return cb.or(predicates.toArray(new Predicate[0]));
    }

    /**
     * 分页时 Spring Data 会用同一个 Specification 生成 count 查询，那时 resultType 是 Long；
     * 给聚合查询加 ORDER BY 会被 PostgreSQL 拒绝（表达式不在 GROUP BY 里），所以只在实体查询上排序
     */
    private static boolean canSort(jakarta.persistence.criteria.CriteriaQuery<?> query) {
        return query != null && Product.class.equals(query.getResultType());
    }

    private static String likeOf(String raw) {
        return "%" + escapeLike(raw.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    private static boolean notBlank(String raw) {
        return raw != null && !raw.isBlank();
    }

    /**
     * A12：同分类价格带近似款。±40% 内不足一屏时放宽到 ±80%，
     * 仍然按「离本款价差」由近及远排，而不是直接换成同类目销量榜
     */
    @Override
    public List<Product> similar(UUID productId, int limit) {
        int size = Math.min(Math.max(limit, 1), 12);
        Optional<Product> found = productRepository.findById(productId);
        if (found.isEmpty()) {
            return List.of();
        }
        Product product = found.get();
        if (product.getCategory() == null || product.getPrice() == null) {
            return List.of();
        }
        List<Product> picked = new ArrayList<>(similarInBand(product, size, SIMILAR_PRICE_SPREAD_PERMILLE));
        if (picked.size() < size) {
            Set<UUID> taken = new LinkedHashSet<>();
            picked.forEach(p -> taken.add(p.getId()));
            taken.add(product.getId());
            similarInBand(product, size * 2, SIMILAR_PRICE_SPREAD_WIDE_PERMILLE).stream()
                    .filter(p -> !taken.contains(p.getId()))
                    .forEach(picked::add);
        }
        return picked.size() > size ? picked.subList(0, size) : picked;
    }

    private List<Product> similarInBand(Product product, int size, int spreadPermille) {
        BigDecimal half = product.getPrice().multiply(BigDecimal.valueOf(spreadPermille))
                .divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
        return productRepository.findSimilarInCategory(product.getId(), product.getCategory().getId(),
                product.getPrice(), product.getPrice().subtract(half).max(BigDecimal.ZERO),
                product.getPrice().add(half), PageRequest.of(0, size));
    }

    @Override
    public List<Product> coPurchased(UUID productId, int limit) {
        List<UUID> ids = orderItemRepository.findCoPurchased(productId, OrderStatus.DEAL_STATUSES,
                        PageRequest.of(0, Math.min(Math.max(limit, 1), 12) * 2))
                .stream().map(row -> (UUID) row[0]).toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> found = productRepository.findAllById(ids).stream()
                .filter(Product::getIsActive)
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));
        // 保持共购次数顺序，而不是 findAllById 的库内顺序
        return ids.stream().map(found::get).filter(java.util.Objects::nonNull)
                .limit(Math.min(Math.max(limit, 1), 12)).toList();
    }

    /**
     * A26：近 N 天真实成交排行。榜单名次来自 order_item，这里只补商品快照，
     * 并过滤掉期间下架的款——首页点进一个「已下架」是差评，不是功能
     */
    @Override
    public List<Product> bestSellersRecent(int days, UUID categoryId, int limit) {
        Pageable cap = PageRequest.of(0, Math.min(Math.max(limit, 1), 24));
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        List<UUID> ranked = (categoryId == null
                ? orderItemRepository.findTopProductIds(since, OrderStatus.DEAL_STATUSES, cap)
                : orderItemRepository.findTopProductIdsInCategories(since,
                        categoryRepository.idsWithChildren(categoryId), OrderStatus.DEAL_STATUSES, cap))
                .stream().map(row -> (UUID) row[0]).toList();
        if (ranked.isEmpty()) {
            return List.of();
        }
        Map<UUID, Product> found = productRepository.findAllById(ranked).stream()
                .filter(Product::getIsActive)
                .collect(java.util.stream.Collectors.toMap(Product::getId, p -> p));
        return ranked.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
    }

    /** 一个词命中名称/花材/标签/场景/花语/描述/分类名任一即算匹配；多词按 requireAll 决定 AND 还是 OR */
    private Specification<Product> activeMatching(List<String> terms, boolean requireAll) {
        return (root, query, cb) -> {
            Join<Product, Category> category = root.join("category", JoinType.LEFT);
            List<Predicate> hits = terms.stream().map(term -> termHit(root, category, cb, term)).toList();
            Predicate matched = requireAll ? cb.and(hits.toArray(new Predicate[0])) : orOf(hits, cb);
            return cb.and(cb.isTrue(root.get("isActive")), matched);
        };
    }

    /** 用户输入的 LIKE 通配符必须转义，否则「%」可以当全匹配用 */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public Product createByForm(ProductDtos.Form form) {
        Product product = new Product();
        applyForm(product, form);
        product.setSalesCount(0);
        product.setReviewCount(0);
        if (product.getRating() == null) {
            product.setRating(BigDecimal.valueOf(5.0));
        }
        return productRepository.save(product);
    }

    @Override
    public Product updateByForm(UUID id, ProductDtos.Form form) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        applyForm(product, form);
        return productRepository.save(product);
    }

    /**
     * 表单→实体：只有运营可维护的字段能覆盖，stock 仅在明确传入时改动，
     * salesCount / rating / reviewCount 由业务写入，杜绝后台表单伪造销量
     */
    private void applyForm(Product product, ProductDtos.Form form) {
        if (form.categoryId() == null) {
            throw new BusinessException("请选择商品分类");
        }
        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new BusinessException("所选分类不存在"));
        product.setCategory(category);
        product.setCode(resolveCode(product, form.code()));

        product.setName(form.name().trim());
        product.setDescription(form.description());
        product.setPrice(form.price());
        product.setOriginalPrice(originalPriceOf(form.price(), form.originalPrice()));
        product.setMainImage(SafeUrl.requireSafe(form.mainImage(), "主图地址"));
        // 图集顺序即前台展示顺序（A23），后台拖拽后的次序在这里原样落库
        product.setImages(SafeUrl.requireSafeList(form.images(), "图集地址"));
        product.setTags(joinList(form.tags(), 200));
        product.setUnit(blankTo(form.unit(), "束"));
        product.setWeight(trimTo(form.weight(), 50));
        product.setMaterial(trimTo(form.material(), 100));
        product.setPackaging(trimTo(form.packaging(), 100));
        product.setSubtitle(trimTo(form.subtitle(), 120));
        product.setCareTip(trimTo(form.careTip(), 500));
        product.setFlowerLanguage(trimTo(form.flowerLanguage(), 200));
        product.setSuitableFor(joinList(form.suitableFor(), 200));
        product.setOriginId(resolveOrigin(form.originId()));
        product.setIsActive(form.isActive());
        product.setIsFeatured(Boolean.TRUE.equals(form.isFeatured()));
        product.setIsNew(Boolean.TRUE.equals(form.isNew()));
        if (form.stock() != null) {
            product.setStock(form.stock());
        }
        if (product.getSalesCount() == null) {
            product.setSalesCount(0);
        }
        if (product.getReviewCount() == null) {
            product.setReviewCount(0);
        }
    }

    /**
     * A22：编码留空由系统按「P + 上架日 + 当日流水」生成，手填值统一大写并只允许字母数字与 . _ -；
     * 与已有商品重号时直接报业务错，而不是让唯一索引抛 500
     */
    private String resolveCode(Product product, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return product.getCode() != null && !product.getCode().isBlank()
                    ? product.getCode() : nextGeneratedCode();
        }
        String code = rawCode.trim().toUpperCase(Locale.ROOT);
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new BusinessException("商品编码只能使用字母、数字与 . _ -，且以字母或数字开头");
        }
        productRepository.findByCode(code).ifPresent(exist -> {
            if (!exist.getId().equals(product.getId())) {
                throw new BusinessException("商品编码「" + code + "」已被「" + exist.getName() + "」占用");
            }
        });
        return code;
    }

    /** 流水号取「当日已建档数 + 1」，再向后探测，避免并发下撞唯一索引 */
    private String nextGeneratedCode() {
        String day = LocalDateTime.now().format(CODE_DAY);
        long seed = productRepository.countByCodePrefix("P" + day) + 1;
        for (long seq = seed; seq < seed + 200; seq++) {
            String candidate = "P" + day + String.format(Locale.ROOT, "%03d", seq);
            if (!productRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        throw new BusinessException("当日商品编码已用尽，请手工指定编码");
    }

    /** 产地必须是启用中的直采基地；留空表示「暂不标注」，产地地图按未标注处理 */
    private UUID resolveOrigin(UUID originId) {
        if (originId == null) {
            return null;
        }
        return flowerOriginRepository.findByKindAndIsActiveTrueOrderBySortOrderAsc("origin").stream()
                .filter(origin -> origin.getId().equals(originId))
                .findFirst()
                .map(origin -> origin.getId())
                .orElseThrow(() -> new BusinessException("所选产地不存在或已停用"));
    }

    /**
     * A19：划线价只在真的高于售价时保留，且低于成本线的「假折扣」直接丢弃；
     * 运营把价格改回去时旧划线价不会继续挂着
     */
    private static BigDecimal originalPriceOf(BigDecimal price, BigDecimal original) {
        if (original == null || price == null || original.compareTo(price) <= 0) {
            return null;
        }
        return original;
    }

    /** 逗号串字段统一去重、按「,」重排，前端拆分展示才不会出现空项 */
    private static String joinList(String raw, int maxLen) {
        List<String> parts = splitTokens(raw);
        if (parts.isEmpty()) {
            return null;
        }
        String joined = String.join(",", parts);
        if (joined.length() > maxLen) {
            throw new BusinessException("标签过长，请精简到 " + maxLen + " 字以内");
        }
        return joined;
    }

    /** 中英文逗号都能接受，顺序保持运营输入次序 */
    static List<String> splitTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(java.util.Arrays.stream(raw.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList()));
    }

    private static String trimTo(String value, int maxLen) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLen) {
            throw new BusinessException("内容过长，请精简到 " + maxLen + " 字以内");
        }
        return trimmed;
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /**
     * 批量上下架（G05）：逐条条件更新，已经是目标状态的记为「跳过」而不是成功，
     * 这样运营看到的成功数就是真实变更数
     */
    @Override
    public BatchOutcome changeActiveStatus(List<UUID> ids, boolean isActive) {
        List<UUID> picked = distinctIds(ids);
        Map<UUID, Snapshot> snapshots = snapshots(picked);
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID id : picked) {
            Snapshot current = snapshots.get(id);
            Map<String, Object> item = newItem(id, current);
            if (current == null) {
                failed++;
                item.put("ok", false);
                item.put("reason", "商品不存在或已被删除");
            } else if (Boolean.valueOf(isActive).equals(current.isActive())) {
                skipped++;
                item.put("ok", true);
                item.put("skipped", true);
                item.put("reason", isActive ? "已经是上架状态" : "已经是下架状态");
            } else if (productRepository.updateActiveStatusCas(id, isActive) > 0) {
                ok++;
                item.put("ok", true);
                item.put("before", !isActive);
                item.put("after", isActive);
            } else {
                failed++;
                item.put("ok", false);
                item.put("reason", "状态刚被其他操作改过，请刷新后重试");
            }
            items.add(item);
        }
        return new BatchOutcome(ok, skipped, failed, items);
    }

    /**
     * 批量改价（G06）：先在内存里按策略算出新价，再用「旧价不变才更新」的条件语句落库。
     * 并发下别人已经改过价时影响行数为 0，这条记为失败并提示刷新，而不是把新价覆盖上去。
     */
    @Override
    public BatchOutcome batchAdjustPrice(List<UUID> ids, BatchPricePolicy policy, boolean previewOnly) {
        List<UUID> picked = distinctIds(ids);
        Map<UUID, Snapshot> snapshots = snapshots(picked);
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID id : picked) {
            Snapshot current = snapshots.get(id);
            Map<String, Object> item = newItem(id, current);
            if (current == null) {
                failed++;
                item.put("ok", false);
                item.put("reason", "商品不存在或已被删除");
            } else if (current.price() == null) {
                failed++;
                item.put("ok", false);
                item.put("reason", "该商品没有可售价格，跳过改价");
            } else {
                BigDecimal target = policy.applyTo(current.price());
                if (!policy.changes(current.price())) {
                    skipped++;
                    item.put("ok", true);
                    item.put("skipped", true);
                    item.put("before", current.price());
                    item.put("after", target);
                    item.put("reason", "价格未发生变化");
                } else if (previewOnly) {
                    // 预览不落库：把改前改后交给运营过目，确认后才发正式请求
                    ok++;
                    item.put("ok", true);
                    item.put("preview", true);
                    item.put("before", current.price());
                    item.put("after", target);
                } else if (productRepository.updatePriceIfUnchanged(id, current.price(), target,
                        policy.nextOriginalPrice(current.originalPrice(), target)) > 0) {
                    ok++;
                    item.put("ok", true);
                    item.put("before", current.price());
                    item.put("after", target);
                } else {
                    failed++;
                    item.put("ok", false);
                    item.put("before", current.price());
                    item.put("reason", "价格刚被其他操作改过，请刷新后重试");
                }
            }
            items.add(item);
        }
        return new BatchOutcome(ok, skipped, failed, items);
    }

    /**
     * 批量调库存（G07）：三种模式都先比对读到的旧库存再写，
     * 减量不足或库存被并发改过都会落到失败明细里
     */
    @Override
    public BatchOutcome batchAdjustStock(List<UUID> ids, String mode, int quantity) {
        List<UUID> picked = distinctIds(ids);
        Map<UUID, Snapshot> snapshots = snapshots(picked);
        List<Map<String, Object>> items = new ArrayList<>();
        int ok = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID id : picked) {
            Snapshot current = snapshots.get(id);
            Map<String, Object> item = newItem(id, current);
            if (current == null) {
                failed++;
                item.put("ok", false);
                item.put("reason", "商品不存在或已被删除");
                items.add(item);
                continue;
            }
            int now = current.stock() == null ? 0 : current.stock();
            Integer target = switch (mode == null ? "set" : mode) {
                case "increase" -> now + quantity;
                case "reduce" -> now - quantity;
                default -> quantity;
            };
            item.put("before", now);
            item.put("after", target);
            if (target < 0) {
                failed++;
                item.put("ok", false);
                item.put("reason", "库存不足，当前仅 " + now + " 件");
            } else if (target > MAX_STOCK) {
                failed++;
                item.put("ok", false);
                item.put("reason", "调整后库存 " + target + " 超过上限 " + MAX_STOCK);
            } else if (Objects.equals(target, now)) {
                skipped++;
                item.put("ok", true);
                item.put("skipped", true);
                item.put("reason", "库存未发生变化");
            } else if (productRepository.updateStockIfUnchanged(id, now, target) > 0) {
                ok++;
                item.put("ok", true);
            } else {
                failed++;
                item.put("ok", false);
                item.put("reason", "库存刚被其他操作改过，请刷新后重试");
            }
            items.add(item);
        }
        return new BatchOutcome(ok, skipped, failed, items);
    }

    @Override
    public Product adjustStock(UUID id, ProductDtos.StockRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        int quantity = request.quantity();
        int affected = "reduce".equals(request.type())
                ? productRepository.reduceStock(id, quantity)
                : productRepository.increaseStock(id, quantity);
        if (affected == 0) {
            throw new BusinessException("库存不足，当前库存 " + product.getStock() + " 件");
        }
        product.setStock(product.getStock() + ("reduce".equals(request.type()) ? -quantity : quantity));
        return product;
    }

    /**
     * 删除或归档（G12）：被订单或评价引用过就必须留档——历史订单要能点开看当时买了什么，
     * 评价表还带着指向本商品的 product_id 外键，硬删会直接把约束撞响
     */
    @Override
    public String deleteOrArchive(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        if (referenced(id)) {
            productRepository.updateActiveStatus(id, false);
            return "archived";
        }
        favoriteRepository.deleteByProductId(id);
        productRepository.delete(product);
        return "deleted";
    }

    @Override
    public BatchOutcome batchDeleteOrArchive(List<UUID> ids) {
        List<UUID> picked = distinctIds(ids);
        Map<UUID, Snapshot> snapshots = snapshots(picked);
        List<Map<String, Object>> items = new ArrayList<>();
        int deleted = 0;
        int archived = 0;
        int failed = 0;
        for (UUID id : picked) {
            Snapshot current = snapshots.get(id);
            Map<String, Object> item = newItem(id, current);
            if (current == null) {
                failed++;
                item.put("ok", false);
                item.put("reason", "商品不存在或已被删除");
            } else {
                String mode = deleteOrArchive(id);
                boolean archivedRow = "archived".equals(mode);
                if (archivedRow) {
                    archived++;
                } else {
                    deleted++;
                }
                item.put("ok", true);
                item.put("mode", mode);
                item.put("reason", archivedRow ? "已有订单或评价引用，已下架归档" : "无引用，已删除");
            }
            items.add(item);
        }
        // 「成功」这里等于「已处置」：删除与归档都算处理完成，failed 只留给真没做成的
        return new BatchOutcome(deleted + archived, 0, failed, items);
    }

    @Override
    public Map<String, Object> deleteImpact(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        long[] refs = referenceCounts(id);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("id", product.getId());
        impact.put("name", product.getName());
        impact.put("code", product.getCode());
        impact.put("orderCount", refs[0]);
        impact.put("paidOrderCount", refs[1]);
        impact.put("soldQuantity", refs[2]);
        impact.put("reviewCount", refs[3]);
        impact.put("favoriteCount", refs[4]);
        boolean referenced = refs[0] > 0 || refs[3] > 0;
        impact.put("deletable", !referenced);
        impact.put("mode", referenced ? "archived" : "deleted");
        impact.put("hint", referenced
                ? "「" + product.getName() + "」已被 " + refs[0] + " 笔订单、" + refs[3] + " 条评价引用，"
                + "删除会自动改为下架归档：商品从前台消失，历史订单与评价仍可追溯。"
                : "该商品还没有任何订单与评价引用，可以直接删除。");
        impact.put("actions", referenced
                ? List.of("确认后下架归档（保留历史数据）", "先到商品管理核对是否只是临时售罄")
                : List.of("确认后直接删除", "不想删也可以先下架"));
        return impact;
    }

    /** 订单数 / 成交订单数 / 累计销量 / 评价数 / 收藏数 */
    private long[] referenceCounts(UUID id) {
        long[] counts = new long[]{0, 0, 0, 0, 0};
        long[] orderRefs = adminQueryRepository.orderReferences(List.of(id)).get(id);
        if (orderRefs != null) {
            counts[0] = orderRefs[0];
            counts[1] = orderRefs[1];
            counts[2] = orderRefs[2];
        }
        counts[3] = adminQueryRepository.reviewReferences(List.of(id)).getOrDefault(id, 0L);
        counts[4] = adminQueryRepository.favoriteReferences(List.of(id)).getOrDefault(id, 0L);
        return counts;
    }

    private boolean referenced(UUID id) {
        long[] counts = referenceCounts(id);
        return counts[0] > 0 || counts[3] > 0;
    }

    /**
     * 批量语句带 clearAutomatically，跑完一级缓存就被清了，提前加载的实体会变游离对象；
     * 所以先把要展示与比对的四五个字段拷成普通记录，再逐条落库
     */
    private Map<UUID, Snapshot> snapshots(List<UUID> ids) {
        Map<UUID, Snapshot> snapshots = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return snapshots;
        }
        for (Product product : productRepository.findAllById(ids)) {
            snapshots.put(product.getId(), new Snapshot(product.getName(), product.getCode(),
                    product.getPrice(), product.getOriginalPrice(), product.getStock(), product.getIsActive()));
        }
        return snapshots;
    }

    private static Map<String, Object> newItem(UUID id, Snapshot snapshot) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", snapshot == null ? "（记录已不存在）" : snapshot.name());
        item.put("code", snapshot == null ? null : snapshot.code());
        return item;
    }

    /** 去重并守住单次批量上限，避免一次请求把上万行摁进事务 */
    private static List<UUID> distinctIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        List<UUID> picked = new ArrayList<>(new LinkedHashSet<>(ids));
        if (picked.size() > MAX_BATCH_SIZE) {
            throw new BusinessException("单次批量最多处理 " + MAX_BATCH_SIZE + " 条，请分批操作");
        }
        return picked;
    }
}