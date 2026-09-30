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

    /**
     * 收藏列表（D11/D12）：支持按分类筛选、按时间/价格/销量排序、以及「只看有货」过滤。
     * 分类下拉与筛选条由前端调用本接口带参获取，服务端过滤排序保证与分页一致。
     */
    @GetMapping
    public Result<List<FavoriteView>> list(@RequestParam(required = false) UUID categoryId,
                                           @RequestParam(required = false, defaultValue = "newest") String sort,
                                           @RequestParam(required = false, defaultValue = "false") boolean inStock,
                                           HttpSession session) {
        return Result.ok(favoriteService.list(userId(session), categoryId, sort, inStock));
    }

    /**
     * 收藏/取消收藏切换，返回切换后的状态供前端直接更新按钮
     */
    @PostMapping("/toggle")
    public Result<Boolean> toggle(@RequestParam UUID productId, HttpSession session) {
        boolean favorited = favoriteService.toggle(userId(session), productId);
        return Result.ok(favorited ? "已加入收藏" : "已取消收藏", favorited);
    }

    /**
     * 批量取消收藏（配合收藏夹多选）：body 传收藏记录 id 列表，返回实际删除条数
     */
    @PostMapping("/batch-remove")
    public Result<Integer> batchRemove(@RequestBody List<UUID> ids, HttpSession session) {
        int removed = favoriteService.removeBatch(userId(session), ids);
        return Result.ok("已取消 " + removed + " 件收藏", removed);
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
