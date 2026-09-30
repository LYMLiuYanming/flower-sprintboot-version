package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.liuym.flowerv1springboot.vo.ContentViews.CategoryView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 前台分类只读接口：写操作见 /api/admin/categories
 */
@RestController
@RequestMapping("/api/categories")
@Tag(name = "前台 · 分类")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    @GetMapping
    public Result<List<CategoryView>> getAllCategories() {
        return Result.ok(CategoryView.from(categoryService.findAll()));
    }

    @GetMapping("/active")
    public Result<List<CategoryView>> getActiveCategories() {
        return Result.ok(CategoryView.from(categoryService.findActiveCategories()));
    }

    @GetMapping("/root")
    public Result<List<CategoryView>> getRootCategories() {
        return Result.ok(CategoryView.from(categoryService.findRootCategories()));
    }

    @GetMapping("/count")
    public Result<Long> getCategoryCount() {
        return Result.ok(categoryService.count());
    }

    /**
     * 分类 → 在售商品数：前台分类瓦片用它显示真实库存款数，
     * 一次取回而不是让页面按分类逐个请求
     */
    @GetMapping("/product-counts")
    public Result<Map<UUID, Long>> productCounts() {
        return Result.ok(categoryService.activeProductCountByCategory());
    }

    @GetMapping("/{id}")
    public Result<CategoryView> getCategoryById(@PathVariable UUID id) {
        return categoryService.findById(id)
                .map(category -> Result.ok(CategoryView.from(category)))
                .orElseGet(() -> Result.notFound("分类不存在"));
    }
}
