package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.BannerService;
import org.liuym.flowerv1springboot.vo.ContentViews.BannerView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/banners")
@Tag(name = "前台 · 轮播图")
public class BannerController {

    @Autowired
    private BannerService bannerService;

    /**
     * 首页轮播：仅返回启用且处于投放时间窗内的记录
     */
    @GetMapping("/active")
    public Result<List<BannerView>> getActiveBanners() {
        return Result.ok(BannerView.from(bannerService.findDisplayableBanners()));
    }
}
