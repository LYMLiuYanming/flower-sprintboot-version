package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.ReviewGuardQueryRepository.ReviewableItem;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewCard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 评价写入入口（需登录，/api/reviews/** 由 AuthInterceptor 统一拦下）。
 * 公开读取（晒单广场、商品评价列表、评分摘要）在 /api/showcase/** 下，见 ReviewApiController。
 */
@RestController
@RequestMapping("/api/reviews")
@Tag(name = "前台 · 评价")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    /**
     * 提交评价（F01/F02/F05/F07）。
     * 请求体同时兼容旧版「只有评分 + 文字」的载荷：新增字段全部可选，订单详情页不需要改造。
     */
    @PostMapping
    public Result<ReviewCard> submit(@Valid @RequestBody ContentDtos.ReviewSubmit form, HttpSession session) {
        User user = CurrentUser.require(session);
        ReviewCard card = reviewService.submitCard(user.getId(), form);
        return Result.ok("感谢您的评价", card);
    }

    /** F03 买家追评：一条评价只能追加一次 */
    @PostMapping("/{id}/append")
    public Result<ReviewCard> append(@PathVariable UUID id,
                                     @Valid @RequestBody ContentDtos.ReviewAppendForm form,
                                     HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok("追评已发布", reviewService.append(user.getId(), id, form));
    }

    /** F07 数据源：当前用户在这款商品上还没评价过的已完成订单明细 */
    @GetMapping("/reviewable")
    public Result<List<ReviewableItem>> reviewable(@RequestParam(required = false) UUID productId,
                                                   @RequestParam(defaultValue = "5") int limit,
                                                   HttpSession session) {
        User user = CurrentUser.require(session);
        int size = Math.min(Math.max(limit, 1), Pages.MAX_SIZE);
        return Result.ok(reviewService.reviewableOf(user.getId(), productId, size));
    }

    /** 我的评价：含被后台隐藏的记录与隐藏理由 */
    @GetMapping("/mine")
    public Result<List<ReviewCard>> mine(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int limit,
                                         HttpSession session) {
        User user = CurrentUser.require(session);
        Page<ReviewCard> result = reviewService.queryMine(user.getId(), Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements())
                .with("page", page)
                .with("pageSize", Pages.sizeOf(limit))
                .with("totalPages", result.getTotalPages());
    }
}
