package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.service.BannerService;
import org.liuym.flowerv1springboot.vo.ContentViews.BannerView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台轮播图管理：/api/admin/** 由 AuthInterceptor 校验管理员，AdminAuditFilter 自动留痕。
 */
@RestController
@RequestMapping("/api/admin/banners")
@Tag(name = "后台 · 轮播图管理")
public class BannerAdminController {

    @Autowired
    private BannerService bannerService;

    /** 后台列表：拖拽排序需要一次看到全量，页面按 limit=100（Pages 上限）取 */
    @GetMapping
    public Result<List<BannerView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "") String status) {
        Page<Banner> result = bannerService.searchAdmin(status, keyword,
                Pages.of(page, limit, Sort.Direction.DESC, "sortOrder"));
        return Result.page(BannerView.from(result.getContent()), result.getTotalElements())
                .with("displayableCount", bannerService.countDisplayable());
    }

    /** F14 预览：按前台真实播放顺序返回当前生效的轮播，后台弹窗直接照这个渲染 */
    @GetMapping("/preview")
    public Result<List<BannerView>> preview() {
        return Result.ok(BannerView.from(bannerService.findDisplayableBanners()));
    }

    /** 新建表单里的「插到第一位」按钮取这个值，避免运营猜排序号 */
    @GetMapping("/next-sort")
    public Result<Integer> nextSort() {
        return Result.ok(bannerService.nextTopSortOrder());
    }

    /**
     * 拖拽排序：ids 为页面上从上到下的新顺序
     */
    @PostMapping("/sort")
    public Result<Void> sort(@Valid @RequestBody ContentDtos.BannerSortRequest request) {
        bannerService.resort(request.ids());
        return Result.ok("排序已保存", null);
    }

    @GetMapping("/{id}")
    public Result<BannerView> detail(@PathVariable UUID id) {
        return bannerService.findById(id)
                .map(banner -> Result.ok(BannerView.from(banner)))
                .orElseGet(() -> Result.notFound("轮播图不存在"));
    }

    @PostMapping
    public Result<BannerView> create(@Valid @RequestBody ContentDtos.BannerForm form) {
        return Result.ok("创建成功", BannerView.from(bannerService.createByForm(form)));
    }

    @PutMapping("/{id}")
    public Result<BannerView> update(@PathVariable UUID id, @Valid @RequestBody ContentDtos.BannerForm form) {
        return Result.ok("更新成功", BannerView.from(bannerService.updateByForm(id, form)));
    }

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam String status) {
        if (!Banner.STATUS_ACTIVE.equals(status) && !Banner.STATUS_INACTIVE.equals(status)) {
            return Result.error("状态取值不合法");
        }
        return bannerService.updateStatus(id, status)
                ? Result.ok("状态更新成功", null)
                : Result.error("状态更新失败");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        return bannerService.deleteById(id) ? Result.ok("删除成功", null) : Result.error("删除失败");
    }

    @GetMapping("/count")
    public Result<Long> count() {
        return Result.ok(bannerService.count());
    }
}
