package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ProductDtos;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.vo.ProductView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台商品维护接口：全部挂在 /api/admin/** 下，由 AuthInterceptor 统一校验管理员身份
 */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "后台 · 商品管理")
public class ProductAdminController {

    @Autowired
    private ProductService productService;

    @GetMapping
    public Result<List<ProductView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String name) {
        // 旧页面用 name 传关键词，这里与 keyword 等价兼容
        String kw = (keyword != null && !keyword.isBlank()) ? keyword : name;
        Page<Product> result = productService.searchAdmin(categoryId, isActive, kw,
                Pages.of(page, limit, Sort.Direction.DESC, "createdAt"));
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements());
    }

    @GetMapping("/{id}")
    public Result<ProductView> detail(@PathVariable UUID id) {
        return productService.findById(id)
                .map(p -> Result.ok(ProductView.from(p)))
                .orElseGet(() -> Result.notFound("商品不存在"));
    }

    @PostMapping
    public Result<ProductView> create(@Valid @RequestBody ProductDtos.Form form, HttpSession session) {
        CurrentUser.requireAdmin(session);
        Product product = productService.createByForm(form);
        return Result.ok("添加成功", ProductView.from(product));
    }

    @PutMapping("/{id}")
    public Result<ProductView> update(@PathVariable UUID id, @Valid @RequestBody ProductDtos.Form form) {
        return Result.ok("更新成功", ProductView.from(productService.updateByForm(id, form)));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam Boolean isActive) {
        return productService.updateActiveStatus(id, isActive)
                ? Result.ok(isActive ? "商品已上架" : "商品已下架", null)
                : Result.error("状态更新失败");
    }

    @PostMapping("/batch-status")
    public Result<Integer> batchStatus(@Valid @RequestBody ProductDtos.BatchStatusRequest request) {
        int affected = productService.changeActiveStatus(request.ids(), request.isActive());
        return Result.ok("已更新 " + affected + " 条记录", affected);
    }

    @PostMapping("/{id}/stock")
    public Result<ProductView> adjustStock(@PathVariable UUID id,
                                          @Valid @RequestBody ProductDtos.StockRequest request) {
        return Result.ok("库存已调整", ProductView.from(productService.adjustStock(id, request)));
    }

    @PostMapping("/batch-delete")
    public Result<Map<String, Integer>> batchDelete(@Valid @RequestBody ProductDtos.BatchIdsRequest request) {
        Map<String, Integer> counts = productService.batchDeleteOrArchive(request.ids());
        return Result.ok("已删除 " + counts.get("deleted") + " 个，归档 " + counts.get("archived") + " 个", counts);
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable UUID id) {
        String mode = productService.deleteOrArchive(id);
        return Result.ok("deleted".equals(mode) ? "删除成功" : "该商品已产生订单，已自动下架归档", mode);
    }
}
