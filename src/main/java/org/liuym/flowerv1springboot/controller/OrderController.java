package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户侧订单 API：金额与库存全部由服务端裁决，越权访问在 Service 层拦截
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "前台 · 下单与订单")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/create")
    public Result<OrderView> createOrder(@Valid @RequestBody OrderDtos.CreateRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("订单创建成功", orderService.create(loginUser.getId(), request));
    }

    @GetMapping
    public Result<List<OrderView>> getOrders(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int limit,
                                             HttpSession session) {
        User loginUser = CurrentUser.require(session);
        List<OrderView> orders = orderService.listForUser(loginUser.getId(), parseStatus(status));
        int from = Math.max(0, (Math.max(page, 1) - 1) * Math.min(Math.max(limit, 1), 50));
        List<OrderView> slice = orders.stream().skip(from).limit(Math.min(Math.max(limit, 1), 50)).toList();
        return Result.page(slice, orders.size());
    }

    @GetMapping("/recent")
    public Result<List<OrderView>> recent(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(orderService.listForUser(loginUser.getId(), null).stream().limit(3).toList());
    }

    @GetMapping("/{id}")
    public Result<OrderView> getOrder(@PathVariable UUID id, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(orderService.detailForUser(id, loginUser.getId()));
    }

    @PostMapping("/{id}/pay")
    public Result<OrderView> payOrder(@PathVariable UUID id,
                                      @RequestBody(required = false) OrderDtos.PayRequest request,
                                      HttpSession session) {
        User loginUser = CurrentUser.require(session);
        String payMethod = request == null ? null : request.payMethod();
        return Result.ok("支付成功", orderService.pay(id, loginUser.getId(), payMethod));
    }

    @PostMapping("/{id}/cancel")
    public Result<OrderView> cancelOrder(@PathVariable UUID id,
                                         @RequestBody(required = false) Map<String, String> body,
                                         HttpSession session) {
        User loginUser = CurrentUser.require(session);
        String reason = body == null ? null : body.get("reason");
        return Result.ok("订单已取消", orderService.cancelByUser(id, loginUser.getId(), reason));
    }

    @PostMapping("/{id}/confirm-receipt")
    public Result<OrderView> confirmReceipt(@PathVariable UUID id, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("已确认收货", orderService.confirmReceipt(id, loginUser.getId()));
    }

    @GetMapping("/count")
    public Result<Long> count(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(orderService.countByUserId(loginUser.getId()));
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return OrderStatus.fromCode(status);
    }
}
