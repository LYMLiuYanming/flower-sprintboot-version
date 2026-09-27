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
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 前台商品只读接口：写操作全部收敛到 /api/admin/products，避免匿名改库
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "前台 · 商品与搜索")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    /** 上架列表默认序：最新优先 */
    private static final Sort BY_RECOMMENDED = Sort.by(Sort.Direction.DESC, "createdAt");

    /** 分类 / 检索默认序：销量优先 */
    private static final Sort BY_SALES = Sort.by(Sort.Direction.DESC, "salesCount");

    private static final Sort BY_RATING = Sort.by(Sort.Direction.DESC, "rating")
            .and(Sort.by(Sort.Direction.DESC, "reviewCount"));

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

    @GetMapping("/active")
    public Result<List<ProductView>> getActiveProducts(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        Page<Product> result = productService.findByActivePage(
                Pages.of(page, limit, sortOf(sort, BY_RECOMMENDED)));
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements());
    }

    @GetMapping("/featured")
    public Result<List<ProductView>> getFeaturedProducts(@RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findFeaturedProducts(Math.min(Math.max(limit, 1), 50))));
    }

    @GetMapping("/new")
    public Result<List<ProductView>> getNewProducts(@RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findNewProducts(Math.min(Math.max(limit, 1), 50))));
    }

    @GetMapping("/bestsellers")
    public Result<List<ProductView>> getBestSellers(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "8") int limit) {
        return Result.ok(ProductView.from(productService.findBestSellers(categoryId, Math.min(Math.max(limit, 1), 50))));
    }

    @GetMapping("/category/{categoryId}")
    public Result<List<ProductView>> getProductsByCategory(
            @PathVariable UUID categoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            @RequestParam(required = false) String sort) {
        Page<Product> result = productService.findByCategoryId(categoryId,
                Pages.of(page, limit, sortOf(sort, BY_SALES)));
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements());
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
        Page<Product> result = productService.searchActive(kw, Pages.of(page, limit, sortOf(sort, BY_SALES)));
        recordSearchWord(kw);
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements());
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
        Page<Product> result = productService.findByPriceRange(minPrice, maxPrice,
                Pages.of(page, limit, Sort.Direction.ASC, "price"));
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements());
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

    /**
     * 前台排序白名单：只认这几种语义，非法值回落到端点默认序，避免任意字段进入 ORDER BY
     */
    private static Sort sortOf(String key, Sort fallback) {
        if (key == null || key.isBlank() || "default".equals(key)) {
            return fallback;
        }
        return switch (key) {
            case "new" -> BY_RECOMMENDED;
            case "sales" -> BY_SALES;
            case "price-asc" -> Sort.by(Sort.Direction.ASC, "price");
            case "price-desc" -> Sort.by(Sort.Direction.DESC, "price");
            case "rating" -> BY_RATING;
            default -> fallback;
        };
    }
}
