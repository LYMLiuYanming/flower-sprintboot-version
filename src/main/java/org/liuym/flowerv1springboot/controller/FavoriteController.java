package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.FavoriteService;
import org.liuym.flowerv1springboot.vo.SupportViews.FavoriteView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/favorites")
@Tag(name = "前台 · 收藏")
public class FavoriteController {

    @Autowired
    private FavoriteService favoriteService;

    @GetMapping
    public Result<List<FavoriteView>> list(HttpSession session) {
        return Result.ok(favoriteService.list(userId(session)));
    }

    /**
     * 收藏/取消收藏切换，返回切换后的状态供前端直接更新按钮
     */
    @PostMapping("/toggle")
    public Result<Boolean> toggle(@RequestParam UUID productId, HttpSession session) {
        boolean favorited = favoriteService.toggle(userId(session), productId);
        return Result.ok(favorited ? "已加入收藏" : "已取消收藏", favorited);
    }

    @GetMapping("/check")
    public Result<Boolean> check(@RequestParam UUID productId, HttpSession session) {
        return Result.ok(favoriteService.isFavorite(userId(session), productId));
    }

    @GetMapping("/count")
    public Result<Long> count(HttpSession session) {
        return Result.ok(favoriteService.count(userId(session)));
    }

    private UUID userId(HttpSession session) {
        return CurrentUser.require(session).getId();
    }
}
