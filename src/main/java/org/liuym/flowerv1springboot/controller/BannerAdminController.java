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

@RestController
@RequestMapping("/api/admin/banners")
@Tag(name = "后台 · 轮播图管理")
public class BannerAdminController {

    @Autowired
    private BannerService bannerService;

    @GetMapping
    public Result<List<BannerView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keyword) {
        Page<Banner> result = (keyword == null || keyword.isBlank())
                ? bannerService.findAll(Pages.of(page, limit, Sort.Direction.DESC, "sortOrder"))
                : bannerService.searchByTitle(keyword.trim(), Pages.of(page, limit));
        return Result.page(BannerView.from(result.getContent()), result.getTotalElements());
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
