package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
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
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/categories")
@Tag(name = "后台 · 分类管理")
public class CategoryAdminController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 后台列表（G14）：默认按 sortOrder 升序，父分类在前；
     * tree=true 时一次给全量并按「父在前、子紧随」的层级序排好，供拖拽排序与父级下拉共用
     */
    @GetMapping
    public Result<List<CategoryView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) Boolean tree) {
        if (Boolean.TRUE.equals(tree)) {
            List<CategoryView> rows = CategoryView.from(categoryService.treeOrdered());
            return Result.ok(rows);
        }
        if (name != null && !name.isBlank()) {
            List<Category> matched = categoryService.searchByName(name.trim());
            return Result.page(CategoryView.from(matched), matched.size());
        }
        Page<Category> result = categoryService.findByPage(
                Pages.of(page, limit, Sort.Direction.ASC, "sortOrder"));
        return Result.page(CategoryView.from(result.getContent()), result.getTotalElements());
    }

    /**
     * 拖拽排序（G13）：把拖完的 id 顺序交回服务端重排 sortOrder。
     * 用 POST 而不是逐个 PATCH：一次请求一次留痕，中途失败也不会出现半套顺序。
     * 入参记录就放在本类里——CategoryDtos 由分类批次共用，不为了一个新端点去动别人的文件
     */
    public record ReorderRequest(@NotEmpty(message = "请至少拖动两个分类") List<UUID> ids) {
    }

    @PostMapping("/reorder")
    public Result<Map<String, Integer>> reorder(@Valid @RequestBody ReorderRequest request) {
        int affected = categoryService.reorder(request.ids());
        return Result.ok("已按新顺序排列 " + affected + " 个分类", Map.of("affected", affected));
    }

    /** 子分类列表：层级表格里展开某一行时按需取 */
    @GetMapping("/{id}/children")
    public Result<List<CategoryView>> children(@PathVariable UUID id) {
        return Result.ok(CategoryView.from(categoryService.findChildCategories(id)));
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

    /** 后台列表的「挂载商品」列数据源：一次取回全部计数，页面合并进表格 */
    @GetMapping("/product-counts")
    public Result<Map<UUID, Long>> productCounts() {
        return Result.ok(categoryService.productCountByCategory());
    }

    /** A25：删除前把影响面摊开——商品数、子分类数、能否删除与下一步动作 */
    @GetMapping("/{id}/impact")
    public Result<Map<String, Object>> impact(@PathVariable UUID id) {
        return Result.ok(categoryService.deleteImpact(id));
    }

    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable UUID id) {
        categoryService.deleteSafely(id);
        return Result.ok("删除成功", null);
    }
}
