package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.BatchPricePolicy;
import org.liuym.flowerv1springboot.dto.ProductDtos;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.common.Pages;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProductService {

    /** 上架列表默认序：最新优先 */
    Sort BY_RECOMMENDED = Sort.by(Sort.Direction.DESC, "createdAt");

    /** 分类 / 检索默认序：销量优先 */
    Sort BY_SALES = Sort.by(Sort.Direction.DESC, "salesCount").and(BY_RECOMMENDED);

    Sort BY_RATING = Sort.by(Sort.Direction.DESC, "rating")
            .and(Sort.by(Sort.Direction.DESC, "reviewCount"))
            .and(BY_SALES);

    /**
     * A06 排序键白名单：只认这几个语义，非法值回落调用方给的默认序，
     * 绝不让前端字符串直接变成 ORDER BY 字段
     */
    Map<String, Sort> SORTS = Map.of(
            "new", BY_RECOMMENDED,
            "sales", BY_SALES,
            "price-asc", Sort.by(Sort.Direction.ASC, "price").and(BY_SALES),
            "price-desc", Sort.by(Sort.Direction.DESC, "price").and(BY_SALES),
            "rating", BY_RATING,
            "discount", Sort.by(Sort.Direction.DESC, "originalPrice").and(BY_SALES));

    /** 前端排序控件的可选项，页面与接口共用一份，避免 UI 上出现后端不认的键 */
    List<String> SORT_KEYS = List.of("relevance", "sales", "price-asc", "price-desc", "rating", "discount", "new");

    /** A08 每页数量白名单 */
    List<Integer> LIMITS = List.of(12, 24, 48);

    /** 检索结果卡片可勾选展示的字段 */
    List<String> FIELD_KEYS = List.of("subtitle", "flowerLanguage", "careTip", "sold", "lowStock", "priceRange");

    /** 字段展示上限，避免一次带上几十个列把 SQL 撑爆 */
    int MAX_FIELDS = 6;

    /** A06：非法 / 缺省排序键回落到 fallback；relevance 由关键词加权打分实现，这里给销量序兜底 */
    static Sort sortOf(String key, Sort fallback) {
        if (key == null || key.isBlank() || "default".equals(key) || "relevance".equals(key)) {
            return fallback;
        }
        return SORTS.getOrDefault(key.toLowerCase(), fallback);
    }

    /** A08：limit 只接受白名单值，非法值回落 12 */
    static int limitOf(int requested) {
        return LIMITS.contains(requested) ? requested : 12;
    }

    /** 非分页端点的条数钳制（推荐位 / 榜单一次取完，不该被分页规则误伤） */
    static int sizeOf(int requested, int fallback) {
        int value = requested <= 0 ? fallback : requested;
        return Math.min(Math.max(value, 1), 24);
    }

    /** 展示字段白名单：去重、按候选顺序归一化，未知键静默丢弃而不是报错 */
    static List<String> fieldsOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> wanted = new java.util.HashSet<>();
        for (String part : raw.split("[,，]")) {
            String key = part.trim();
            if (FIELD_KEYS.contains(key)) {
                wanted.add(key);
            }
        }
        return FIELD_KEYS.stream().filter(wanted::contains).limit(MAX_FIELDS).toList();
    }

    Product save(Product product);

    List<Product> saveAll(List<Product> products);

    Optional<Product> findById(UUID id);

    List<Product> findAll();

    Page<Product> findByPage(Pageable pageable);

    Page<Product> findByActivePage(Pageable pageable);

    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);

    /** 前台统一检索：价格区间、库存、标签、场景、产地、分类、关键词一次覆盖 */
    Page<Product> browse(Query query, Pageable pageable);

    /** 当前筛选条件下的真实价格分布（A11），无命中时给全站在售区间 */
    BigDecimal[] priceStats(Query query);

    /**
     * A10：检索条件命中为 0 时的兜底——同类目（或关键词猜测出的类目）热销，
     * 而不是把「没有找到」原样甩给用户
     */
    List<Product> browseFallback(Query query, int limit);

    /** 后台产地下拉：只给直采产地，按排序值返回 id + 名称 */
    List<Map<String, Object>> originOptions();

    /** 后台提示用：编码留空时会被分配到的号（真正保存时重算，这里只做预填展示） */
    String previewCode();

    /** 检索意图：字段为 null / 空即「不过滤」，钳制与合法性判断留在实现里做 */
    record Query(String keyword, List<UUID> categoryIds, BigDecimal minPrice, BigDecimal maxPrice,
                 Boolean inStockOnly, Boolean featured, Boolean newOnly, String tag, String scene,
                 UUID originId, List<String> fields) {

        /** 老的十参构造：不带展示字段的调用方不必跟着改 */
        public Query(String keyword, List<UUID> categoryIds, BigDecimal minPrice, BigDecimal maxPrice,
                     Boolean inStockOnly, Boolean featured, Boolean newOnly, String tag, String scene,
                     UUID originId) {
            this(keyword, categoryIds, minPrice, maxPrice, inStockOnly, featured, newOnly, tag, scene,
                    originId, List.of());
        }

        /** 关键词是否真的会参与过滤：分词后为空（如整串标点）时不算有词 */
        public boolean hasKeyword() {
            return keyword != null && !keyword.isBlank();
        }
    }

    /** A07：收敛后的结果集 + 真实页码 */
    record Browsed(Page<Product> page, int effectivePage) {
    }

    /**
     * A07：page 越界时收敛到末页再取一次，并把真实页码带回——前端分页条不能停在空白页
     */
    default Browsed browseConverged(Query query, int page, int limit, Sort order) {
        Page<Product> first = browse(query, Pages.of(page, limit, order));
        int last = Math.max(first.getTotalPages(), 1);
        int effective = Math.min(Math.max(page, 1), last);
        if (first.isEmpty() && first.getTotalPages() > 0 && page > effective) {
            return new Browsed(browse(query, Pages.of(effective, limit, order)), effective);
        }
        return new Browsed(first, effective);
    }

    /** 同分类价格带近似款（详情页「看了又看」） */
    List<Product> similar(UUID productId, int limit);

    /** 共购推荐：买过该商品的人还买了什么 */
    List<Product> coPurchased(UUID productId, int limit);

    /** 近 N 天真实成交排行（首页畅销位不再只看静态 salesCount） */
    List<Product> bestSellersRecent(int days, UUID categoryId, int limit);

    List<Product> findFeaturedProducts(int limit);

    List<Product> findNewProducts(int limit);

    List<Product> findBestSellers(UUID categoryId, int limit);

    Page<Product> searchByName(String name, Pageable pageable);

    Page<Product> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable);

    Product update(Product product);

    boolean updateActiveStatus(UUID id, Boolean isActive);

    boolean reduceStock(UUID id, Integer quantity);

    boolean increaseStock(UUID id, Integer quantity);

    boolean increaseSalesCount(UUID id, Integer count);

    boolean deleteById(UUID id);

    long count();

    long countActive();

    long countByCategory(UUID categoryId);

    /** 后台组合筛选：分类 + 上架状态 + 库存告急 + 关键词（名称/编码/花材/标签/描述） */
    Page<Product> searchAdmin(UUID categoryId, Boolean isActive, Boolean lowStockOnly, String keyword,
                              Pageable pageable);

    /**
     * 后台列表可排序字段（G03）：白名单之外的键一律回落到创建时间倒序。
     * 后半段是前台 /api/products/browse 的 sort 键（A06 同一份 SORTS），
     * 两个后台/前台口径共用一套语义，运营在列表上按「销量」排出来的顺序和顾客看到的一致
     */
    Map<String, Sort> ADMIN_SORTS = Map.ofEntries(
            Map.entry("createdAt", Sort.by(Sort.Direction.DESC, "createdAt")),
            Map.entry("name", Sort.by(Sort.Direction.ASC, "name")),
            Map.entry("code", Sort.by(Sort.Direction.ASC, "code")),
            Map.entry("priceAsc", Sort.by(Sort.Direction.ASC, "price")),
            Map.entry("priceDesc", Sort.by(Sort.Direction.DESC, "price")),
            Map.entry("stockAsc", Sort.by(Sort.Direction.ASC, "stock")),
            Map.entry("stockDesc", Sort.by(Sort.Direction.DESC, "stock")),
            Map.entry("salesDesc", Sort.by(Sort.Direction.DESC, "salesCount")),
            Map.entry("salesAsc", Sort.by(Sort.Direction.ASC, "salesCount")),
            Map.entry("ratingDesc", BY_RATING),
            Map.entry("new", BY_RECOMMENDED),
            Map.entry("sales", BY_SALES),
            Map.entry("rating", BY_RATING),
            Map.entry("price-asc", SORTS.get("price-asc")),
            Map.entry("price-desc", SORTS.get("price-desc")),
            Map.entry("discount", SORTS.get("discount")),
            Map.entry("stock-asc", Sort.by(Sort.Direction.ASC, "stock")),
            Map.entry("stock-desc", Sort.by(Sort.Direction.DESC, "stock")));

    /** 排序键归一：页面传的是列字段名 + 方向，这里折叠成一个语义键，非法值不报错而是回默认序 */
    static Sort adminSortOf(String key, String direction) {
        if (key == null || key.isBlank()) {
            return Sort.by(Sort.Direction.DESC, "createdAt");
        }
        String normalized = key.trim();
        if ("price".equals(normalized) || "stock".equals(normalized) || "salesCount".equals(normalized)) {
            boolean asc = direction != null && "asc".equalsIgnoreCase(direction.trim());
            normalized = normalized + (asc ? "Asc" : "Desc");
        }
        return ADMIN_SORTS.getOrDefault(normalized, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** 库存告急阈值：与 ProductView.LOW_STOCK_ALERT 同一个数，列表筛选与页面标记不会各说各话 */
    int LOW_STOCK_ALERT = 5;

    /** 库存告急款数：列表页筛选按钮上的计数 */
    long countLowStock();

    /** 关键词检索（仅上架商品），名称/描述/标签命中即可 */
    Page<Product> searchActive(String keyword, Pageable pageable);

    /**
     * 后台新建商品：编码唯一、分类必须存在、图片协议白名单校验
     */
    Product createByForm(ProductDtos.Form form);

    /**
     * 后台编辑商品：只覆盖表单字段，销量/评分/库存等由业务写入的字段不接受前端输入
     */
    Product updateByForm(UUID id, ProductDtos.Form form);

    /**
     * 批量操作的逐条结果（G05/G06/G07）：一次请求里每条商品各自的成败与原因，
     * 页面必须能指出「哪一条为什么没改成」，而不是只报一个总数
     */
    record BatchOutcome(int succeeded, int skipped, int failed, List<java.util.Map<String, Object>> items) {

        public static java.util.Map<String, Object> toMap(BatchOutcome outcome) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("succeeded", outcome.succeeded());
            body.put("skipped", outcome.skipped());
            body.put("failed", outcome.failed());
            body.put("total", outcome.items().size());
            body.put("items", outcome.items());
            return body;
        }
    }

    /** 批量上下架：条件更新逐条执行，返回每条商品的成败与新旧状态 */
    BatchOutcome changeActiveStatus(List<UUID> ids, boolean isActive);

    /**
     * 批量改价（G06）：policy 决定百分比/固定值、取整位数与下限；
     * previewOnly 时只算不发 UPDATE，供弹窗把改前改后列给运营过目
     */
    BatchOutcome batchAdjustPrice(List<UUID> ids, BatchPricePolicy policy, boolean previewOnly);

    /**
     * 批量调库存（G07）：set=设为该值，increase/reduce=在当前值上增减；
     * 减少不足时该条失败，不会把库存压成负数
     */
    BatchOutcome batchAdjustStock(List<UUID> ids, String mode, int quantity);

    /**
     * 库存调整：increase 直接加，reduce 走 CAS 防负库存
     */
    Product adjustStock(UUID id, ProductDtos.StockRequest request);

    /**
     * 删除商品：已被订单/评价引用时改为下架（归档），保证历史订单可追溯
     *
     * @return deleted=已物理删除，archived=已下架归档
     */
    String deleteOrArchive(UUID id);

    /**
     * 批量删除，逐条走 deleteOrArchive 规则，返回每条的处置方式（G12）
     */
    BatchOutcome batchDeleteOrArchive(List<UUID> ids);

    /**
     * 删除前的引用体检（G12）：订单数、成交订单数、累计销量、评价数、收藏数，
     * 页面据此把「删不掉」说成「会被归档」，而不是让运营点到 500 才知道
     */
    Map<String, Object> deleteImpact(UUID id);
}