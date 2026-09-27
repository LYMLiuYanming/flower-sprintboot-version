package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.StatsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台看板统计：口径全部下沉到 StatsService 的数据库聚合，页面不再拉全表
 */
@RestController
@RequestMapping("/api/admin/stats")
@Tag(name = "后台 · 统计看板")
public class StatsController {

    @Autowired
    private StatsService statsService;

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.ok(statsService.overview());
    }

    @GetMapping("/sales-trend")
    public Result<List<Map<String, Object>>> salesTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.salesTrend(days));
    }

    @GetMapping("/top-products")
    public Result<List<Map<String, Object>>> topProducts(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(statsService.topProducts(limit));
    }

    @GetMapping("/top-spenders")
    public Result<List<Map<String, Object>>> topSpenders(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(statsService.topSpenders(limit));
    }

    /** 看板「刷新」按钮：先失效 60s 统计缓存，再让前端重新拉取，避免读到旧值 */
    @PostMapping("/refresh")
    public Result<Void> refresh() {
        statsService.clearCache();
        return Result.ok("统计数据已刷新", null);
    }
}
