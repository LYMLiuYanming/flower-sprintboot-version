package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.BannerService;
import org.liuym.flowerv1springboot.vo.ContentViews.BannerView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/banners")
@Tag(name = "前台 · 轮播图")
public class BannerController {

    @Autowired
    private BannerService bannerService;

    /**
     * 首页轮播：仅返回启用且处于投放时间窗内的记录（F13）。
     * 响应字段与改版前一致，首页与任何旧调用方都不需要改；
     * 新增的 linkType/linkTarget 只是多给一份语义，linkUrl 仍然可用。
     */
    @GetMapping("/active")
    public Result<List<BannerView>> getActiveBanners(@RequestParam(defaultValue = "0") int limit) {
        List<BannerView> banners = BannerView.from(bannerService.findDisplayableBanners());
        if (limit > 0 && banners.size() > limit) {
            banners = banners.subList(0, Math.min(limit, Pages.MAX_SIZE));
        }
        return Result.ok(banners);
    }

    /** 单张轮播的跳转信息：外部页面按 id 取跳转地址，不必自己拼 /product/{uuid} */
    @GetMapping("/{id}")
    public Result<BannerView> detail(@PathVariable UUID id) {
        return bannerService.findById(id)
                .map(banner -> Result.ok(BannerView.from(banner)))
                .orElseGet(() -> Result.notFound("轮播图不存在"));
    }
}
