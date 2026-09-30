package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.AdminQueryRepository;
import org.liuym.flowerv1springboot.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台全局搜索（G28）：订单号 / 商品名 / 用户名一个框直达。
 * 只读，不改任何业务数据；订单与用户走 AdminQueryRepository 的只读查询，商品走本组自己的 searchAdmin。
 */
@RestController
@RequestMapping("/api/admin/search")
@Tag(name = "后台 · 全局搜索")
public class AdminSearchController {

    /** 每类最多给 5 条：全局搜索是用来定位的，不是再来一个列表页 */
    private static final int GROUP_SIZE = 5;

    /** 短于这个长度不查：一个「a」能把半个库捞出来，等用户多敲两个字更划算 */
    private static final int MIN_KEYWORD = 2;

    private final AdminQueryRepository adminQueryRepository;
    private final ProductService productService;

    public AdminSearchController(AdminQueryRepository adminQueryRepository, ProductService productService) {
        this.adminQueryRepository = adminQueryRepository;
        this.productService = productService;
    }

    @GetMapping
    public Result<Map<String, Object>> search(@RequestParam(required = false) String keyword) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orders", List.of());
        body.put("products", List.of());
        body.put("users", List.of());
        if (keyword == null || keyword.trim().length() < MIN_KEYWORD) {
            return Result.ok(body);
        }
        String term = keyword.trim();
        body.put("orders", orders(term));
        body.put("products", products(term));
        body.put("users", users(term));
        return Result.ok(body);
    }

    private List<Map<String, Object>> orders(String keyword) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : adminQueryRepository.searchOrders(keyword, GROUP_SIZE)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("orderNo", row[1]);
            item.put("status", row[2] instanceof OrderStatus status ? status.getCode() : String.valueOf(row[2]));
            item.put("statusLabel", row[2] instanceof OrderStatus status ? status.getLabel() : "");
            item.put("payAmount", row[3]);
            item.put("createdAt", row[4]);
            item.put("receiverName", row[5]);
            // 直达订单详情页；订单列表页由 H 批次维护，这里不假设它的筛选参数
            item.put("url", "/admin/order-detail/" + row[0]);
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> products(String keyword) {
        Page<Product> found = productService.searchAdmin(null, null, null, keyword,
                PageRequest.of(1, GROUP_SIZE, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Product product : found.getContent()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", product.getId());
            item.put("code", product.getCode());
            item.put("name", product.getName());
            item.put("price", product.getPrice());
            item.put("stock", product.getStock());
            item.put("isActive", product.getIsActive());
            // 商品没有独立详情页后台路由，按编码回列表页定位（列表页已支持关键词预填）
            item.put("url", "/admin/product-list?keyword=" + (product.getCode() == null
                    ? product.getName() : product.getCode()));
            rows.add(item);
        }
        return rows;
    }

    private List<Map<String, Object>> users(String keyword) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : adminQueryRepository.searchUsersByName(keyword, GROUP_SIZE)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row[0]);
            item.put("username", row[1]);
            item.put("fullName", row[2]);
            item.put("status", row[3]);
            item.put("memberLevel", row[4]);
            item.put("url", "/admin/user-detail/" + row[0]);
            rows.add(item);
        }
        return rows;
    }

    /** 供其它后台页拼跳转链接时复用，避免 UUID 字符串形态各处不一致 */
    public static String idOf(Object id) {
        return id instanceof UUID uuid ? uuid.toString() : String.valueOf(id);
    }
}
