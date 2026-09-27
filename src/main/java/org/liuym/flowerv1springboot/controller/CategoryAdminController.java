package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.CategoryDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.liuym.flowerv1springboot.vo.ContentViews.CategoryView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/categories")
@Tag(name = "后台 · 分类管理")
public class CategoryAdminController {

    @Autowired
    private CategoryService categoryService;

    @GetMapping
    public Result<List<CategoryView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String name) {
        if (name != null && !name.isBlank()) {
            List<Category> matched = categoryService.searchByName(name.trim());
            return Result.page(CategoryView.from(matched), matched.size());
        }
        Page<Category> result = categoryService.findByPage(
                Pages.of(page, limit, Sort.Direction.ASC, "sortOrder"));
        return Result.page(CategoryView.from(result.getContent()), result.getTotalElements());
    }

    @GetMapping("/{id}")
    public Result<CategoryView> detail(@PathVariable UUID id) {
        return categoryService.findById(id)
                .map(category -> Result.ok(CategoryView.from(category)))
                .orElseGet(() -> Result.notFound("分类不存在"));
    }

    @PostMapping
    public Result<CategoryView> create(@Valid @RequestBody CategoryDtos.Form form) {
        return Result.ok("添加成功", CategoryView.from(categoryService.createByForm(form)));
    }

    @PutMapping("/{id}")
    public Result<CategoryView> update(@PathVariable UUID id, @Valid @RequestBody CategoryDtos.Form form) {
        return Result.ok("更新成功", CategoryView.from(categoryService.updateByForm(id, form)));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam Boolean isActive) {
        return categoryService.updateActiveStatus(id, isActive)
                ? Result.ok("状态更新成功", null)
                : Result.error("状态更新失败");
    }

    @PatchMapping("/{id}/sort")
    public Result<Void> updateSort(@PathVariable UUID id, @RequestParam Integer sortOrder) {
        return categoryService.updateSortOrder(id, sortOrder)
                ? Result.ok("排序更新成功", null)
                : Result.error("排序更新失败");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        categoryService.deleteSafely(id);
        return Result.ok("删除成功", null);
    }
}
