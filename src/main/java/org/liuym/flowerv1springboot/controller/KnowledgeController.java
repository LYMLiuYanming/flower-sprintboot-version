package org.liuym.flowerv1springboot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Article;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.liuym.flowerv1springboot.service.KnowledgeService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.vo.ContentViews.ArticleCategory;
import org.liuym.flowerv1springboot.vo.ContentViews.ArticleView;
import org.liuym.flowerv1springboot.vo.ContentViews.CategoryView;
import org.liuym.flowerv1springboot.vo.ContentViews.ProductOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 知识库（F15）与晒单广场（F10）的页面路由 + 知识库接口。
 *
 * <p>页面用 @Controller 渲染，接口方法逐个标 @ResponseBody；
 * 类内的 {@code @ExceptionHandler} 兜住接口异常并按 Result 返回——
 * 全局的 {@code GlobalExceptionHandler} 只作用于 @RestController，这里必须自己接。
 */
@Controller
@Tag(name = "前台 · 知识库与晒单")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;
    private final ProductService productService;
    private final CategoryService categoryService;
    private final ObjectMapper objectMapper;

    public KnowledgeController(KnowledgeService knowledgeService, ProductService productService,
                               CategoryService categoryService, ObjectMapper objectMapper) {
        this.knowledgeService = knowledgeService;
        this.productService = productService;
        this.categoryService = categoryService;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------
    // 页面
    // ------------------------------------------------------------------

    /** F10 晒单广场：评价流的展示口径全部交给 /api/showcase，页面只负责首屏骨架 */
    @GetMapping("/reviews")
    public String reviewsPage(@RequestParam(required = false) UUID categoryId,
                              @RequestParam(required = false) String tag,
                              Model model) {
        model.addAttribute("categories", CategoryView.from(categoryService.findActiveCategories()));
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedTag", tag);
        model.addAttribute("metaTitle", "晒单广场 · 花语轩 HUAYUXUAN");
        model.addAttribute("metaDescription", "真实顾客的花礼晒单：带图评价、星级与标签占比，来自已完成订单。");
        return "shop/reviews";
    }

    @GetMapping("/knowledge")
    public String knowledgePage(@RequestParam(required = false) String category,
                                @RequestParam(required = false) String keyword,
                                Model model) {
        model.addAttribute("categories", knowledgeService.categories());
        model.addAttribute("selectedCategory", category);
        model.addAttribute("keyword", keyword);
        model.addAttribute("metaTitle", "花语知识库 · 花语轩 HUAYUXUAN");
        model.addAttribute("metaDescription", "花语寓意、养护方法与产地故事，由门店花艺师整理。");
        return "shop/knowledge";
    }

    /** 文章详情：浏览数自增，同时把关联商品与相关文章一并取出，页面不再发二次请求 */
    @GetMapping("/article/{id}")
    public String articlePage(@PathVariable UUID id, Model model) {
        Article article = knowledgeService.findDisplayable(id)
                .orElseThrow(() -> BusinessException.notFound("文章不存在或已下线"));
        knowledgeService.incrementViewCount(id);
        model.addAttribute("article", article);
        model.addAttribute("viewCount", article.getViewCount() + 1);
        model.addAttribute("relatedProducts", relatedProducts(article));
        model.addAttribute("relatedArticles",
                ArticleView.from(knowledgeService.relatedOf(id, 4)));
        model.addAttribute("metaTitle", article.getTitle() + " · 花语轩知识库");
        model.addAttribute("metaDescription", article.getSummary());
        return "shop/article-detail";
    }

    /** 后台页面：路径在 /admin/** 下，AuthInterceptor 已经校验过管理员身份 */
    @GetMapping("/admin/article-list")
    public String articleListPage() {
        return "admin/article-list";
    }

    @GetMapping("/admin/article-edit")
    public String articleEditPage() {
        return "admin/article-edit";
    }

    // ------------------------------------------------------------------
    // 前台接口
    // ------------------------------------------------------------------

    @ResponseBody
    @GetMapping("/api/knowledge/articles")
    public Result<List<ArticleView>> list(@RequestParam(required = false) String category,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String material,
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "9") int limit) {
        Page<Article> result = knowledgeService.searchPublished(category, keyword, material,
                Pages.of(page, limit, Sort.Direction.DESC, "createdAt"));
        return Result.page(ArticleView.from(result.getContent()), result.getTotalElements())
                .with("page", result.getNumber() + 1)
                .with("pageSize", Pages.sizeOf(limit))
                .with("totalPages", result.getTotalPages());
    }

    @ResponseBody
    @GetMapping("/api/knowledge/articles/{id}")
    public Result<ArticleView> detail(@PathVariable UUID id) {
        return knowledgeService.findDisplayable(id)
                .map(article -> Result.ok(ArticleView.from(article, true)))
                .orElseGet(() -> Result.notFound("文章不存在或已下线"));
    }

    @ResponseBody
    @GetMapping("/api/knowledge/categories")
    public Result<List<ArticleCategory>> categories() {
        return Result.ok(knowledgeService.categories());
    }

    /** F16：商品详情页侧栏的花材相关推荐 */
    @ResponseBody
    @GetMapping("/api/knowledge/recommend")
    public Result<List<ArticleView>> recommend(@RequestParam UUID productId,
                                               @RequestParam(defaultValue = "4") int limit) {
        return Result.ok(ArticleView.from(knowledgeService.recommendForProduct(productId, limit)));
    }

    @ResponseBody
    @GetMapping("/api/knowledge/hot")
    public Result<List<ArticleView>> hot(@RequestParam(defaultValue = "4") int limit) {
        Page<Article> page = knowledgeService.searchPublished("", "", "",
                Pages.of(1, Math.min(Math.max(limit, 1), 12), Sort.Direction.DESC, "viewCount"));
        return Result.ok(ArticleView.from(page.getContent()));
    }

    // ------------------------------------------------------------------
    // 后台接口
    // ------------------------------------------------------------------

    @ResponseBody
    @GetMapping("/api/admin/articles")
    public Result<List<ArticleView>> adminList(@RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "10") int limit,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "") String category,
                                               @RequestParam(defaultValue = "") String status,
                                               HttpSession session) {
        CurrentUser.requireAdmin(session);
        Page<Article> result = knowledgeService.searchAdmin(status, category, keyword,
                Pages.of(page, limit, Sort.Direction.DESC, "updatedAt"));
        return Result.page(ArticleView.from(result.getContent()), result.getTotalElements())
                .with("publishedCount", knowledgeService.countPublished());
    }

    /** 编辑页取原文：后台可以读到草稿与已下线文章，这是唯一能读非 published 内容的入口 */
    @ResponseBody
    @GetMapping("/api/admin/articles/{id}")
    public Result<ArticleView> adminDetail(@PathVariable UUID id, HttpSession session) {
        CurrentUser.requireAdmin(session);
        return knowledgeService.findById(id)
                .map(article -> Result.ok(ArticleView.from(article, true)))
                .orElseGet(() -> Result.notFound("文章不存在"));
    }

    @ResponseBody
    @PostMapping("/api/admin/articles")
    public Result<ArticleView> adminCreate(@Valid @RequestBody ContentDtos.ArticleForm form, HttpSession session) {
        User operator = CurrentUser.requireAdmin(session);
        return Result.ok("已保存", ArticleView.from(knowledgeService.createByForm(form, operator.getUsername()), true));
    }

    @ResponseBody
    @PutMapping("/api/admin/articles/{id}")
    public Result<ArticleView> adminUpdate(@PathVariable UUID id, @Valid @RequestBody ContentDtos.ArticleForm form,
                                           HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok("已保存", ArticleView.from(knowledgeService.updateByForm(id, form), true));
    }

    @ResponseBody
    @PostMapping("/api/admin/articles/{id}/status")
    public Result<Void> adminStatus(@PathVariable UUID id, @RequestParam String status, HttpSession session) {
        CurrentUser.requireAdmin(session);
        return knowledgeService.updateStatus(id, status) ? Result.ok("状态更新成功", null) : Result.error("状态更新失败");
    }

    @ResponseBody
    @DeleteMapping("/api/admin/articles/{id}")
    public Result<Void> adminDelete(@PathVariable UUID id, HttpSession session) {
        CurrentUser.requireAdmin(session);
        return knowledgeService.deleteById(id) ? Result.ok("删除成功", null) : Result.error("删除失败");
    }

    @ResponseBody
    @GetMapping("/api/admin/articles/count")
    public Result<Long> adminCount(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(knowledgeService.count());
    }

    /** 关联商品选择器：后台按名称搜在售商品，避免让运营手填 UUID */
    @ResponseBody
    @GetMapping("/api/admin/articles/products")
    public Result<List<ProductOption>> productOptions(@RequestParam(required = false) String keyword,
                                                      @RequestParam(defaultValue = "12") int limit,
                                                      HttpSession session) {
        CurrentUser.requireAdmin(session);
        int size = Math.min(Math.max(limit, 1), 30);
        return Result.ok(productService.searchActive(keyword, Pages.of(1, size)).getContent().stream()
                .map(ProductOption::from).toList());
    }

    // ------------------------------------------------------------------

    /** 文章挂了几个商品就取几个，取不到的（已删除）静默跳过，不让文章页出现空卡片 */
    private List<Product> relatedProducts(Article article) {
        List<UUID> ids = knowledgeService.relatedProductIdsOf(article.getId());
        List<Product> out = new ArrayList<>();
        for (UUID id : ids) {
            if (out.size() >= 4) {
                break;
            }
            productService.findById(id).filter(Product::getIsActive).ifPresent(out::add);
        }
        return out;
    }

    /**
     * 接口异常按 Result 返回（HTTP 恒 200、业务码在 body 里），页面异常退回错误视图。
     * 全局 GlobalExceptionHandler 只覆盖 @RestController，本类是 @Controller，必须自己接住，
     * 否则页面抛错会掉进容器默认错误处理、接口抛错会丢掉给用户的提示文案。
     */
    @ExceptionHandler(Exception.class)
    public ModelAndView handleAll(Exception e, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        int code = e instanceof BusinessException business ? business.getCode() : 500;
        String msg = e instanceof BusinessException business ? business.getMessage() : "服务开小差了，请稍后重试";
        if (code >= 500) {
            log.error("知识库请求失败 {} {}", request.getMethod(), request.getRequestURI(), e);
        } else {
            log.warn("业务异常 {} {} code={} msg={}", request.getMethod(), request.getRequestURI(), code, msg);
        }
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(), Result.error(code, msg));
            return null;
        }
        return new ModelAndView(code >= 500 ? "error/5xx" : "error/4xx")
                .addObject("status", code)
                .addObject("path", request.getRequestURI());
    }
}
