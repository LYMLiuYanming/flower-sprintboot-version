package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台订单 API（/api/admin/** 由 AuthInterceptor 保证仅管理员可达）
 */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "后台 · 订单管理")
public class AdminOrderController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 5000;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OrderService orderService;

    public AdminOrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public Result<List<OrderView>> list(@RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int limit,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        Page<OrderView> result = orderService.listForAdmin(parseStatus(status), keyword,
                PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(limit, 1), MAX_PAGE_SIZE)));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    @GetMapping("/recent")
    public Result<List<OrderView>> recent(@RequestParam(defaultValue = "8") int limit) {
        Page<OrderView> result = orderService.listForAdmin(null, null,
                PageRequest.of(0, Math.min(Math.max(limit, 1), MAX_PAGE_SIZE)));
        return Result.ok(result.getContent());
    }

    @GetMapping("/{id}")
    public Result<OrderView> detail(@PathVariable java.util.UUID id) {
        return Result.ok(orderService.detailForAdmin(id));
    }

    @PostMapping("/{id}/status")
    public Result<OrderView> transit(@PathVariable java.util.UUID id,
                                     @Valid @RequestBody OrderDtos.StatusRequest request) {
        return Result.ok("状态更新成功",
                orderService.transitByAdmin(id, OrderStatus.fromCode(request.status()), null, request.reason()));
    }

    @PostMapping("/{id}/ship")
    public Result<OrderView> ship(@PathVariable java.util.UUID id,
                                  @Valid @RequestBody OrderDtos.ShipRequest request) {
        return Result.ok("发货成功", orderService.transitByAdmin(id, OrderStatus.SHIPPED, request, null));
    }

    /**
     * 订单导出 CSV：带 UTF-8 BOM，Excel 直接双击可正确识别中文
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       HttpServletResponse response) throws IOException {
        Page<OrderView> orders = orderService.listForAdmin(parseStatus(status), keyword,
                PageRequest.of(0, EXPORT_LIMIT));

        String filename = URLEncoder.encode("orders.csv", StandardCharsets.UTF_8);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");
        writer.println("订单号,下单用户,收货人,手机号,收货地址,商品总额,实付金额,状态,支付方式,物流公司,物流单号,下单时间,支付时间");
        for (OrderView order : orders.getContent()) {
            writer.println(String.join(",",
                    csv(order.orderNo()), csv(order.userName()), csv(order.receiverName()), csv(order.receiverPhone()),
                    csv(order.receiverAddress()), csv(order.totalAmount()), csv(order.payAmount()),
                    csv(order.statusLabel()), csv(order.payMethod()), csv(order.expressCompany()), csv(order.expressNo()),
                    csv(order.createdAt() == null ? null : order.createdAt().format(DATE_TIME)),
                    csv(order.payTime() == null ? null : order.payTime().format(DATE_TIME))));
        }
        writer.flush();
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return OrderStatus.fromCode(status);
    }
}
