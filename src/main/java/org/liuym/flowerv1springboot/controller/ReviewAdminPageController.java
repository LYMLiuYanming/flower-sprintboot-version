package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台评价审核页（F04/F08/F09 的入口）：
 * <p>只提供页面路由与审核台概览；列表、回复、隐藏、删除、重算的接口都在
 * {@link ReviewApiController}（那是 @RestController，不能返回视图名）。
 * <p>路径 /admin/** 由 AuthInterceptor 拦管理员，本类只做业务级 requireAdmin 兜底。
 */
@Controller
@Tag(name = "后台 · 评价审核页")
public class ReviewAdminPageController {

    @Autowired
    private ReviewService reviewService;

    @GetMapping("/admin/review-list")
    public String reviewListPage() {
        return "admin/review-list";
    }

    /**
     * 审核台待办计数：总数 / 展示中 / 待回复 / 已隐藏，一次拉全，
     * 避免前端逐指标 fetch 时因不同 SQL 时刻看到不同数字。
     */
    @ResponseBody
    @GetMapping("/api/admin/reviews/todo")
    public Result<ReviewService.ModerationStats> todo(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(reviewService.moderationStats());
    }
}
