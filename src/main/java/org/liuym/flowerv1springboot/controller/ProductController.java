package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.FavoriteService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.service.SearchKeywordService;
import org.liuym.flowerv1springboot.vo.ProductView;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 前台商品只读接口：写操作全部收敛到 /api/admin/products，避免匿名改库
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "前台 · 商品与搜索")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    @Autowired
    private ProductService productService;

    @Autowired
    private SearchKeywordService searchKeywordService;

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private ReviewService reviewService;

    @GetMapping
    public Result<List<ProductView>> getAllProducts() {
        return Result.ok(ProductView.from(productService.findAll()));
    }

    /** 上架列表：与 /browse 同规则做越界收敛，页面不会再翻到空白页 */
    @GetMapping("/active")
    public Result<List<ProductView>> getActiveProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        Sort order = ProductService.sortOf(sort, ProductService.BY_RECOMMENDED);
        return paged(page, limit, current -> productService.findByActivePage(Pages.of(current, limit, order)));
    }

    @GetMapping("/featured")
    public Result<List<ProductView>> getFeaturedProducts(@RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findFeaturedProducts(ProductService.sizeOf(limit, 8))));
    }

    @GetMapping("/new")
    public Result<List<ProductView>> getNewProducts(@RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findNewProducts(ProductService.sizeOf(limit, 8))));
    }

    @GetMapping("/bestsellers")
    public Result<List<ProductView>> getBestSellers(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findBestSellers(categoryId, ProductService.sizeOf(limit, 8))));
    }

    @GetMapping("/category/{categoryId}")
    public Result<List<ProductView>> getProductsByCategory(
            @PathVariable UUID categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        Sort order = ProductService.sortOf(sort, ProductService.BY_SALES);
        return paged(page, limit,
                current -> productService.findByCategoryId(categoryId, Pages.of(current, limit, order)));
    }

    /**
     * 关键词检索：名称/描述/标签联合匹配，空关键词等价于浏览全部上架商品
     */
    @GetMapping("/search")
    public Result<List<ProductView>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        String kw = (keyword != null && !keyword.isBlank()) ? keyword : name;
        Sort order = ProductService.sortOf(sort, ProductService.BY_SALES);
        recordSearchWord(kw);
        return paged(page, limit,
                current -> productService.searchActive(kw, Pages.of(current, limit, order)));
    }

    /**
     * 统一检索入口：列表页所有筛选控件都走这里，避免「价格区间」「只看有货」各开一个端点各写一套语义。
     * 价格区间允许只填一端；page 越界时收敛到末页并把真实页码回传，前端分页条不会停在空白页。
     * 命中为 0 时附同类目热销兜底（A10），并回传真实价格分布（A11）供滑块收窄条件
     */
    @GetMapping("/browse")
    public Result<List<ProductView>> browse(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryIds,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(required = false) Boolean inStockOnly,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) Boolean newOnly,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) UUID originId,
            @RequestParam(required = false) String fields,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        ProductService.Query query = new ProductService.Query(
                keyword, parseIds(categoryIds), floor(minPrice), ceiling(maxPrice),
                inStockOnly, featured, newOnly, tag, scene, originId, ProductService.fieldsOf(fields));
        int size = ProductService.limitOf(limit);
        Sort order = ProductService.sortOf(sort, ProductService.BY_SALES);
        ProductService.Browsed browsed = productService.browseConverged(query, page, size, order);
        Page<Product> result = browsed.page();

        Result<List<ProductView>> response = Result.page(ProductView.from(result.getContent()), result.getTotalElements())
                .with("page", browsed.effectivePage())
                .with("pageSize", size)
                .with("totalPages", result.getTotalPages())
                .with("priceRange", priceRangeOf(query))
                .with("fields", query.fields())
                .with("sort", sort == null || sort.isBlank() ? "sales" : sort);
        if (result.isEmpty()) {
            List<ProductView> fallback = ProductView.from(productService.browseFallback(query, size));
            if (!fallback.isEmpty()) {
                response.with("fallback", fallback).with("fallbackReason", fallbackReason(query));
            }
        }
        return response;
    }

    /** 价格分布给前端是 {min,max}，无命中时为 null，页面据此决定滑块是否可用 */
    private Map<String, Object> priceRangeOf(ProductService.Query query) {
        BigDecimal[] stats = productService.priceStats(query);
        if (stats == null) {
            return null;
        }
        Map<String, Object> range = new java.util.LinkedHashMap<>();
        range.put("min", stats[0]);
        range.put("max", stats[1]);
        return range;
    }

    /** 兜底原因说人话：用户要知道「没找到」的是关键词还是筛选条件 */
    private static String fallbackReason(ProductService.Query query) {
        if (query.hasKeyword()) {
            return "没有匹配「" + query.keyword().trim() + "」的花礼，先看你筛的分类里卖得最好的";
        }
        return "当前筛选条件组合下没有可售花礼，先推荐同类目热卖";
    }

    /**
     * A07 通用收敛：page 越界就按末页重取一次，回传真实页码与总页数，口径与 /browse 一致——
     * 筛完条件只剩一页时，停在空白页是 bug 不是「没数据」
     */
    private Result<List<ProductView>> paged(int page, int limit, IntFunction<Page<Product>> loader) {
        int effective = Math.max(page, 1);
        Page<Product> result = loader.apply(effective);
        if (result.isEmpty() && result.getTotalPages() > 0 && effective > result.getTotalPages()) {
            effective = result.getTotalPages();
            result = loader.apply(effective);
        }
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements())
                .with("page", effective)
                .with("pageSize", Pages.sizeOf(limit))
                .with("totalPages", result.getTotalPages());
    }

    /** 同分类价格带相似款（±40% 不足时放宽到 ±80%，按价差排序） */
    @GetMapping("/{id}/similar")
    public Result<List<ProductView>> similar(@PathVariable UUID id,
                                             @RequestParam(defaultValue = "4") int limit) {
        return Result.ok(ProductView.from(productService.similar(id, ProductService.sizeOf(limit, 4))));
    }

    /** 共购推荐：买过本商品的人还买了什么 */
    @GetMapping("/{id}/co-purchased")
    public Result<List<ProductView>> coPurchased(@PathVariable UUID id,
                                                 @RequestParam(defaultValue = "4") int limit) {
        return Result.ok(ProductView.from(productService.coPurchased(id, ProductService.sizeOf(limit, 4))));
    }

    /** 近 N 天真实成交排行（首页畅销位） */
    @GetMapping("/bestsellers-recent")
    public Result<List<ProductView>> bestSellersRecent(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(
                productService.bestSellersRecent(Math.min(Math.max(days, 1), 365), categoryId,
                        ProductService.sizeOf(limit, 8))));
    }

    /** 逗号分隔的 id 串 → 列表；非法片段直接丢弃，不让一个坏参数拖垮整次检索 */
    private static List<UUID> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            try {
                if (!part.isBlank()) {
                    ids.add(UUID.fromString(part.trim()));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return ids;
    }

    /** 只填一端的价格区间：负数按 0 处理，避免把「-10」当成有效下限传进 SQL */
    private static BigDecimal floor(BigDecimal value) {
        return value == null ? null : value.max(BigDecimal.ZERO);
    }

    private static BigDecimal ceiling(BigDecimal value) {
        return value == null ? null : value.max(BigDecimal.ZERO);
    }

    /**
     * 输入联想：keyword 为空返回热门搜索词，非空返回搜索词库 + 商品名/标签/分类名的候选
     */
    @GetMapping("/suggest")
    public Result<List<String>> suggest(@RequestParam(required = false) String keyword,
                                        @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(searchKeywordService.suggest(keyword, limit));
    }

    /** 留痕失败不能拖垮检索，故整段兜住异常 */
    private void recordSearchWord(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        try {
            searchKeywordService.accumulate(keyword);
        } catch (RuntimeException e) {
            log.warn("累计搜索词失败 {}: {}", keyword, e.getMessage());
        }
    }

    @GetMapping("/price-range")
    public Result<List<ProductView>> getProductsByPriceRange(
            @RequestParam BigDecimal minPrice,
            @RequestParam BigDecimal maxPrice,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit) {
        BigDecimal low = floor(minPrice);
        BigDecimal high = ceiling(maxPrice);
        return paged(page, limit, current -> productService.findByPriceRange(low, high,
                Pages.of(current, limit, Sort.Direction.ASC, "price")));
    }

    /**
     * 商品详情：附带 favorited，供详情页收藏按钮初始态使用
     */
    @GetMapping("/{id}")
    public Result<ProductView> getProductById(@PathVariable UUID id, HttpSession session) {
        User loginUser = CurrentUser.of(session);
        return productService.findById(id)
                .map(p -> Result.ok(ProductView.from(p))
                        .with("favorited", loginUser != null && favoriteService.isFavorite(loginUser.getId(), id)))
                .orElseGet(() -> Result.notFound("商品不存在"));
    }

    @GetMapping("/{id}/reviews")
    public Result<List<ReviewView>> reviews(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit) {
        Page<ReviewView> result = reviewService.listByProduct(id, Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    @GetMapping("/count")
    public Result<Long> getProductCount() {
        return Result.ok(productService.countActive());
    }
}
