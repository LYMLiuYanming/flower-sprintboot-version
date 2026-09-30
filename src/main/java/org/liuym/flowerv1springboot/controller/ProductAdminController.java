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
 * 后台商品维护接口：全部挂在 /api/admin/** 下，由 AuthInterceptor 统一校验管理员身份。
 * 批量改价/调库存/导出这类新端点在同前缀的 {@link AdminProductApiController} 里，本类只管单条与批量上下架。
 */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "后台 · 商品管理")
public class ProductAdminController {

    @Autowired
    private ProductService productService;

    /**
     * 列表查询（G03/G04）：关键词 + 分类 + 上下架状态 + 库存告急组合筛选，
     * sort/order 走服务端白名单排序（价格、库存、销量、时间都能排）
     */
    @GetMapping
    public Result<List<ProductView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean isActive,
            @RequestParam(required = false) Boolean lowStockOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String order,
            @RequestParam(required = false) String name) {
        // 旧页面用 name 传关键词，这里与 keyword 等价兼容
        String kw = (keyword != null && !keyword.isBlank()) ? keyword : name;
        Sort sorted = ProductService.adminSortOf(sort, order);
        Page<Product> result = productService.searchAdmin(categoryId, isActive, lowStockOnly, kw,
                Pages.of(page, limit, sorted));
        return Result.page(ProductView.from(result.getContent()), result.getTotalElements())
                .with("lowStockTotal", productService.countLowStock());
    }

    @GetMapping("/{id}")
    public Result<ProductView> detail(@PathVariable UUID id) {
        return productService.findById(id)
                .map(p -> Result.ok(ProductView.from(p)))
                .orElseGet(() -> Result.notFound("商品不存在"));
    }

    /** 产地下拉选项（A15/G11）：只列启用中的直采基地，后台据此给商品挂主产地 */
    @GetMapping("/origins")
    public Result<List<Map<String, Object>>> origins() {
        return Result.ok(productService.originOptions());
    }

    /** 编码留空时将被分配的号，新建表单里先让运营看到结果再保存（A22） */
    @GetMapping("/next-code")
    public Result<String> nextCode() {
        return Result.ok(productService.previewCode());
    }

    /** 删除前的引用体检（G12）：告诉运营这一条删下去是「真删」还是「下架归档」 */
    @GetMapping("/{id}/impact")
    public Result<Map<String, Object>> impact(@PathVariable UUID id) {
        return Result.ok(productService.deleteImpact(id));
    }

    @PostMapping
    public Result<ProductView> create(@Valid @RequestBody ProductDtos.Form form, HttpSession session) {
        CurrentUser.requireAdmin(session);
        Product product = productService.createByForm(form);
        return Result.ok("添加成功", ProductView.from(product));
    }

    @PutMapping("/{id}")
    public Result<ProductView> update(@PathVariable UUID id, @Valid @RequestBody ProductDtos.Form form,
                                      HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok("更新成功", ProductView.from(productService.updateByForm(id, form)));
    }

    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam Boolean isActive, HttpSession session) {
        CurrentUser.requireAdmin(session);
        return productService.updateActiveStatus(id, isActive)
                ? Result.ok(isActive ? "商品已上架" : "商品已下架", null)
                : Result.error("状态更新失败");
    }

    /**
     * 批量上下架（G05）：返回逐条成败明细，页面能指出哪一条没改成、为什么
     */
    @PostMapping("/batch-status")
    public Result<Map<String, Object>> batchStatus(@Valid @RequestBody ProductDtos.BatchStatusRequest request,
                                                   HttpSession session) {
        CurrentUser.requireAdmin(session);
        ProductService.BatchOutcome outcome = productService.changeActiveStatus(request.ids(), request.isActive());
        return Result.ok(summary(request.isActive() ? "上下架" : "上下架", outcome),
                ProductService.BatchOutcome.toMap(outcome));
    }

    @PostMapping("/{id}/stock")
    public Result<ProductView> adjustStock(@PathVariable UUID id,
                                          @Valid @RequestBody ProductDtos.StockRequest request,
                                          HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok("库存已调整", ProductView.from(productService.adjustStock(id, request)));
    }

    /** 批量删除（G12）：有订单/评价引用的自动下架归档，逐条回报处置方式 */
    @PostMapping("/batch-delete")
    public Result<Map<String, Object>> batchDelete(@Valid @RequestBody ProductDtos.BatchIdsRequest request,
                                                   HttpSession session) {
        CurrentUser.requireAdmin(session);
        ProductService.BatchOutcome outcome = productService.batchDeleteOrArchive(request.ids());
        return Result.ok(summary("删除", outcome), ProductService.BatchOutcome.toMap(outcome));
    }

    @DeleteMapping("/{id}")
    public Result<String> delete(@PathVariable UUID id, HttpSession session) {
        CurrentUser.requireAdmin(session);
        String mode = productService.deleteOrArchive(id);
        return Result.ok("deleted".equals(mode) ? "删除成功" : "该商品已有订单或评价引用，已下架归档", mode);
    }

    /**
     * 审计与提示共用的汇总口径：留痕里要能一眼看出「成功几条、跳过几条、失败几条」
     */
    private static String summary(String verb, ProductService.BatchOutcome outcome) {
        StringBuilder msg = new StringBuilder("已").append(verb).append(' ').append(outcome.succeeded()).append(" 条");
        if (outcome.skipped() > 0) {
            msg.append("，").append(outcome.skipped()).append(" 条无需处理");
        }
        if (outcome.failed() > 0) {
            msg.append("，").append(outcome.failed()).append(" 条失败");
        }
        return msg.toString();
    }
}
