package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 评价提交入口（需登录）；商品评价列表由 GET /api/products/{id}/reviews 匿名提供
 */
@RestController
@RequestMapping("/api/reviews")
@Tag(name = "前台 · 评价")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    public Result<ReviewView> submit(@Valid @RequestBody ContentDtos.ReviewForm form, HttpSession session) {
        ReviewView view = reviewService.submit(CurrentUser.require(session).getId(), form);
        return Result.ok("感谢您的评价", view);
    }
}
