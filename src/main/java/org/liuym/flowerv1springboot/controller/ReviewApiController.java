package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.service.ReviewService.Filter;
import org.liuym.flowerv1springboot.service.ReviewService.ModerationResult;
import org.liuym.flowerv1springboot.service.ReviewService.ModerationStats;
import org.liuym.flowerv1springboot.service.ReviewService.ReviewTag;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewCard;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 评价读取与后台审核接口。
 *
 * <p>读取全部挂在 /api/showcase/** 下：AuthInterceptor 只拦 /api/reviews/**，
 * 所以晒单广场与商品评价区可以匿名浏览，写入仍然必须登录。
 * <p>后台动作挂在 /api/admin/reviews/**：除拦截器的管理员校验外，
 * 隐藏与删除都会额外写一条业务审计（照抄 AdminAuditFilter 的字段口径），
 * 让「谁隐藏了哪条评价、理由是什么」在操作日志页可检索。
 */
@RestController
@Tag(name = "评价 · 晒单与后台审核")
public class ReviewApiController {

    /** 晒单广场单页最多 60 条：再多前端要渲染上百张图，首屏会明显变慢 */
    private static final int SHOWCASE_MAX_LIMIT = 60;

    @Autowired
    private ReviewService reviewService;

    @Autowired
    private AdminAuditService adminAuditService;

    // ------------------------------------------------------------------
    // 前台读取
    // ------------------------------------------------------------------

    /** F05 标签字典：晒单广场与详情页的筛选瓦片都读这里，避免前端硬编码一份 */
    @GetMapping("/api/showcase/tags")
    public Result<List<ReviewTag>> tags() {
        return Result.ok(ReviewService.TAGS);
    }

    /** F10 晒单广场：全站带图/好评评价流，可按分类、星级、标签、有图筛选 */
    @GetMapping("/api/showcase/stream")
    public Result<List<ReviewCard>> stream(@RequestParam(required = false) UUID categoryId,
                                           @RequestParam(required = false) Integer minRating,
                                           @RequestParam(required = false) Integer maxRating,
                                           @RequestParam(required = false) Boolean hasImage,
                                           @RequestParam(required = false) String tag,
                                           @RequestParam(required = false) String sort,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "12") int limit) {
        Page<ReviewCard> result = reviewService.queryShowcase(
                new Filter(null, categoryId, rating(minRating), rating(maxRating), hasImage, tag, sort),
                Pages.of(page, Math.min(limit, SHOWCASE_MAX_LIMIT)));
        return page(result, page, Math.min(Math.max(limit, 1), SHOWCASE_MAX_LIMIT));
    }

    /** F06 商品评价列表：星级 / 只看有图 / 标签筛选 */
    @GetMapping("/api/showcase/product/{productId}")
    public Result<List<ReviewCard>> productReviews(@PathVariable UUID productId,
                                                   @RequestParam(required = false) Integer minRating,
                                                   @RequestParam(required = false) Integer maxRating,
                                                   @RequestParam(required = false) Boolean hasImage,
                                                   @RequestParam(required = false) String tag,
                                                   @RequestParam(required = false) String sort,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int limit) {
        Page<ReviewCard> result = reviewService.queryByProduct(
                new Filter(productId, null, rating(minRating), rating(maxRating), hasImage, tag, sort),
                Pages.of(page, limit));
        return page(result, page, Pages.sizeOf(limit));
    }

    /** F05/F09 商品评价摘要：均分、星级分布、标签占比、带图与追评数 */
    @GetMapping("/api/showcase/product/{productId}/summary")
    public Result<ReviewSummary> summary(@PathVariable UUID productId) {
        return Result.ok(reviewService.summaryOf(productId));
    }

    // ------------------------------------------------------------------
    // 后台审核（F04 / F08 / F09）
    // ------------------------------------------------------------------

    @GetMapping("/api/admin/reviews")
    public Result<List<ReviewCard>> adminList(@RequestParam(required = false) UUID productId,
                                              @RequestParam(required = false) Boolean visible,
                                              @RequestParam(required = false) Integer minRating,
                                              @RequestParam(required = false) String keyword,
                                              @RequestParam(defaultValue = "1") int page,
                                              @RequestParam(defaultValue = "10") int limit) {
        Page<ReviewCard> result = reviewService.queryAdmin(productId, visible, rating(minRating), keyword,
                Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    @GetMapping("/api/admin/reviews/stats")
    public Result<ModerationStats> adminStats() {
        return Result.ok(reviewService.moderationStats());
    }

    @GetMapping("/api/admin/reviews/{id}")
    public Result<ReviewCard> adminDetail(@PathVariable UUID id) {
        return reviewService.cardById(id)
                .map(card -> Result.ok(card))
                .orElseGet(() -> Result.notFound("评价不存在"));
    }

    /** F04 商家回复 */
    @PostMapping("/api/admin/reviews/{id}/reply")
    public Result<ReviewCard> reply(@PathVariable UUID id,
                                    @Valid @RequestBody ContentDtos.ReviewReplyForm form,
                                    HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        ReviewCard card = reviewService.reply(id, form, operator);
        record(request, operator, "回复评价", id,
                "商品：" + card.productName() + "｜回复内容：" + form.content());
        return Result.ok("回复已发布", card);
    }

    /** F08 隐藏 / 恢复：理由同时写进评价行与审计日志 */
    @PostMapping("/api/admin/reviews/{id}/moderation")
    public Result<Map<String, Object>> moderate(@PathVariable UUID id,
                                                @Valid @RequestBody ContentDtos.ReviewModerationForm form,
                                                HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        ModerationResult outcome = reviewService.moderate(id, form, operator);
        record(request, operator, outcome.action(), id, detail(outcome));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("visible", outcome.visibleAfter());
        data.put("productId", outcome.productId());
        return Result.ok(outcome.action() + "成功", data);
    }

    /** F08 删除：内容快照进审计，删掉的文字仍然查得到 */
    @DeleteMapping("/api/admin/reviews/{id}")
    public Result<Void> delete(@PathVariable UUID id,
                               @RequestParam(required = false) String reason,
                               HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        ModerationResult outcome = reviewService.deleteReview(id, reason, operator);
        record(request, operator, outcome.action(), id, detail(outcome));
        return Result.ok("评价已删除", null);
    }

    /** F09 修复：把 product.rating / review_count 与真实评价重新对齐 */
    @PostMapping("/api/admin/reviews/recalc")
    public Result<Integer> recalc(@RequestParam(required = false) UUID productId,
                                  HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        int fixed = reviewService.recalcRatings(productId == null ? List.of() : List.of(productId));
        record(request, operator, "重算评分", productId,
                "本次修正 " + fixed + " 个商品的评分与评价数");
        return Result.ok("已重算 " + fixed + " 个商品", fixed);
    }

    /** F08 留痕回溯：这条评价上发生过哪些后台动作 */
    @GetMapping("/api/admin/reviews/{id}/audit")
    public Result<List<AdminAuditLog>> auditTrail(@PathVariable UUID id,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int limit,
                                                  HttpSession session) {
        CurrentUser.requireAdmin(session);
        Page<AdminAuditLog> result = adminAuditService.search(id.toString(), "评价",
                Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    // ------------------------------------------------------------------

    private static Integer rating(Integer value) {
        if (value == null) {
            return null;
        }
        return Math.min(Math.max(value, 1), 5);
    }

    private Result<List<ReviewCard>> page(Page<ReviewCard> result, int page, int size) {
        return Result.page(result.getContent(), result.getTotalElements())
                .with("page", result.getNumber() + 1)
                .with("pageSize", size)
                .with("totalPages", result.getTotalPages());
    }

    private static String detail(ModerationResult outcome) {
        return "商品：" + outcome.productName()
                + "｜评分：" + outcome.rating() + " 星"
                + "｜内容：" + outcome.contentExcerpt()
                + "｜状态：" + (outcome.visibleBefore() ? "展示" : "隐藏") + " → "
                + (outcome.visibleAfter() ? "展示" : "隐藏")
                + "｜理由：" + outcome.reason();
    }

    /**
     * 业务级审计：AdminAuditFilter 已经会记一条通用留痕，这里补的是「为什么做」——
     * 把评价内容快照与理由写进 detail，删掉的评价才追得回原文。
     */
    private void record(HttpServletRequest request, User operator, String action,
                        Object targetId, String detail) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setOperatorId(operator == null ? null : operator.getId());
        entry.setOperatorName(operator == null ? "未登录" : operator.getUsername());
        entry.setModule("评价");
        entry.setAction(action);
        entry.setMethod(request.getMethod());
        entry.setUri(truncate(request.getRequestURI() + (targetId == null ? "" : " → 评价 " + targetId), 300));
        entry.setDetail(truncate(detail, 2000));
        entry.setResultCode(200);
        entry.setResultMsg(action + "成功");
        entry.setIp(truncate(clientIp(request), 64));
        adminAuditService.record(entry);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + '…';
    }
}
