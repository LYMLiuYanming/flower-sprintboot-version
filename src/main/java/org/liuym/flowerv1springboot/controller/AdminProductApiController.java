package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.liuym.flowerv1springboot.common.BatchPricePolicy;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.vo.ProductView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台商品的批量与导出端点（G06/G07/G08）。
 * 与 ProductAdminController 同前缀但不同路径，目的是把「新增能力」留在自己的文件里，
 * 不去改别人的控制器；写操作仍然全部落在 /api/admin/products/** 下，由 AdminAuditFilter 统一留痕。
 */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "后台 · 商品批量与导出")
public class AdminProductApiController {

    /** 导出上限：后台商品量级内一次取完，超过就提示分批，避免把整库拉进内存 */
    private static final int EXPORT_LIMIT = 5000;

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private ProductService productService;

    /** 批量改价入参（G06）：mode=percent/delta/set，roundTo 控制取整位数，minPrice 兜住降价下限 */
    public record BatchPriceRequest(
            @NotEmpty(message = "请至少选择一条记录") List<UUID> ids,
            @NotNull(message = "请选择改价方式") String mode,
            @NotNull(message = "请填写调整幅度") BigDecimal value,
            Integer roundTo,
            BigDecimal minPrice,
            Boolean preview) {
    }

    /** 批量调库存入参（G07）：mode=set/increase/reduce */
    public record BatchStockRequest(
            @NotEmpty(message = "请至少选择一条记录") List<UUID> ids,
            @NotNull(message = "请选择调整方式") String mode,
            @NotNull(message = "请填写调整数量") Integer quantity) {
    }

    /**
     * 批量改价预览：与正式提交共用同一条计算路径，只算不落库。
     * 弹窗里的「改前/改后」必须来自服务端口径，页面自己算一套迟早和落库结果对不上
     */
    @PostMapping("/batch-price-preview")
    public Result<Map<String, Object>> batchPricePreview(@Valid @RequestBody BatchPriceRequest request,
                                                          HttpSession session) {
        CurrentUser.requireAdmin(session);
        ProductService.BatchOutcome outcome = productService.batchAdjustPrice(request.ids(),
                policyOf(request), true);
        return Result.ok("预览：" + outcome.succeeded() + " 条将调整，" + outcome.skipped() + " 条价格不变",
                ProductService.BatchOutcome.toMap(outcome));
    }

    /** 批量改价（G06）：条件 UPDATE 逐条落库，返回逐条成败明细 */
    @PostMapping("/batch-price")
    public Result<Map<String, Object>> batchPrice(@Valid @RequestBody BatchPriceRequest request,
                                                  HttpSession session) {
        CurrentUser.requireAdmin(session);
        BatchPricePolicy policy = policyOf(request).requireValid();
        ProductService.BatchOutcome outcome = productService.batchAdjustPrice(request.ids(), policy, false);
        // 留痕摘要只带规则与条数，不带任何口令类字段
        return Result.ok("批量改价（" + policy.describe() + "）成功 " + outcome.succeeded()
                + " 条，跳过 " + outcome.skipped() + " 条，失败 " + outcome.failed() + " 条",
                ProductService.BatchOutcome.toMap(outcome));
    }

    /** 批量调库存（G07）：设为/增加/减少，减量不足的那条单独失败，不影响其余 */
    @PostMapping("/batch-stock")
    public Result<Map<String, Object>> batchStock(@Valid @RequestBody BatchStockRequest request,
                                                  HttpSession session) {
        CurrentUser.requireAdmin(session);
        if (request.quantity() == null || request.quantity() < 0) {
            throw new BusinessException("调整数量不能为负");
        }
        if (!List.of("set", "increase", "reduce").contains(request.mode())) {
            throw new BusinessException("库存调整方式只支持设为、增加、减少");
        }
        ProductService.BatchOutcome outcome = productService.batchAdjustStock(request.ids(),
                request.mode(), request.quantity());
        return Result.ok("批量调库存成功 " + outcome.succeeded() + " 条，跳过 " + outcome.skipped()
                + " 条，失败 " + outcome.failed() + " 条", ProductService.BatchOutcome.toMap(outcome));
    }

    /**
     * 商品列表导出 CSV（G08）：筛选口径与列表接口完全一致，带 UTF-8 BOM，Excel 双击即开
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) UUID categoryId,
                       @RequestParam(required = false) Boolean isActive,
                       @RequestParam(required = false) Boolean lowStockOnly,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String sort,
                       @RequestParam(required = false) String order,
                       HttpSession session,
                       HttpServletResponse response) throws IOException {
        CurrentUser.requireAdmin(session);
        Sort sorted = ProductService.adminSortOf(sort, order);
        Page<Product> page = productService.searchAdmin(categoryId, isActive, lowStockOnly,
                keyword == null ? "" : keyword, PageRequest.of(0, EXPORT_LIMIT, sorted));
        List<ProductView> rows = ProductView.from(page.getContent());

        String filename = URLEncoder.encode("products.csv", StandardCharsets.UTF_8);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        PrintWriter writer = response.getWriter();
        writer.write('\uFEFF');
        writer.println("商品编码,商品名称,副标题,分类,售价,划线价,折扣,库存,已售,评分,评价数,状态,精选,新品,"
                + "单位,规格,花材,包装,标签,适用场景,花语,养护贴士,主产地,创建时间");
        for (ProductView row : rows) {
            writer.println(String.join(",", List.of(
                    csv(row.code()), csv(row.name()), csv(row.subtitle()), csv(row.categoryName()),
                    csv(row.price()), csv(row.originalPrice()), csv(row.discountLabel()),
                    csv(row.stock()), csv(row.salesCount()), csv(row.rating()), csv(row.reviewCount()),
                    csv(Boolean.TRUE.equals(row.isActive()) ? "上架" : "下架"),
                    csv(Boolean.TRUE.equals(row.isFeatured()) ? "是" : ""),
                    csv(Boolean.TRUE.equals(row.isNew()) ? "是" : ""),
                    csv(row.unit()), csv(row.weight()), csv(row.material()), csv(row.packaging()),
                    csv(row.tags()), csv(row.suitableFor()), csv(row.flowerLanguage()), csv(row.careTip()),
                    csv(originName(row.originId())),
                    csv(row.createdAt() == null ? null : row.createdAt().format(DATE_TIME)))));
        }
        if (page.getTotalElements() > EXPORT_LIMIT) {
            writer.println("# 仅导出前 " + EXPORT_LIMIT + " 条，请缩小筛选条件后再导");
        }
        writer.flush();
    }

    /**
     * 产地列要把 UUID 翻译回名称：导出是给运营看的，不该出现一串没人认得的 id
     */
    private String originName(UUID originId) {
        if (originId == null) {
            return "";
        }
        return productService.originOptions().stream()
                .filter(option -> originId.equals(option.get("id")))
                .findFirst()
                .map(option -> String.valueOf(option.get("name")))
                .orElse("");
    }

    private static BatchPricePolicy policyOf(BatchPriceRequest request) {
        return BatchPricePolicy.of(request.mode(), request.value(), request.roundTo(), request.minPrice());
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }
}
