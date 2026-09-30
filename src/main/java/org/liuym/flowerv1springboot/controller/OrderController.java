package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.OrderFlowPolicy;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.IdempotencyService;
import org.liuym.flowerv1springboot.service.OrderService;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.liuym.flowerv1springboot.vo.OrderView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 用户侧订单 API：金额与库存全部由服务端裁决，越权访问在 Service 层拦截
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "前台 · 下单与订单")
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 结算页领取一次性下单凭证：一证只换一单，重复提交由服务端回吐首次结果
     */
    @GetMapping("/idempotency-token")
    public Result<String> idempotencyToken(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(idempotencyService.issue(loginUser.getId()));
    }

    @PostMapping("/create")
    public Result<OrderView> createOrder(@Valid @RequestBody OrderDtos.CreateRequest request, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("订单创建成功", orderService.create(loginUser.getId(), request));
    }

    /**
     * 下单失败要带上出错字段（B18）：结算页表单很长，只弹一句 toast 用户找不到改哪里。
     * 控制器内的 handler 优先于全局 advice，因此这里保留 body.code 约定不变。
     */
    @ExceptionHandler(CheckoutPolicy.CheckoutValidation.class)
    public Result<Void> handleValidation(CheckoutPolicy.CheckoutValidation e) {
        return Result.<Void>error(e.getCode(), e.getMessage())
                .with("field", e.field())
                .with("errors", e.issues());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        String msg = errors.stream()
                .map(FieldError::getDefaultMessage)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("；"));
        return Result.<Void>error(400, msg.isEmpty() ? "参数校验失败" : msg)
                .with("field", errors.isEmpty() ? null : errors.get(0).getField())
                .with("errors", errors.stream().map(FieldError::getDefaultMessage).filter(Objects::nonNull).toList());
    }

    /**
     * 我的订单（C01/C02/C03）：状态分组 + 下单时间范围 + 订单号/收货人/手机号模糊词 + 排序一次查完。
     * status 传分组码（toship = 已付款+处理中），sort 只认 OrderFlowPolicy 白名单里的键
     */
    @GetMapping
    public Result<List<OrderView>> getOrders(@RequestParam(required = false) String status,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                             @RequestParam(required = false)
                                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                             @RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "10") int limit,
                                             HttpSession session) {
        User loginUser = CurrentUser.require(session);
        Page<OrderView> result = orderService.pageForUser(loginUser.getId(),
                new OrderDtos.UserOrderQuery(status, from, to, keyword, sort, page, Math.min(Math.max(limit, 1), 50)));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    /** 退款原因候选：申请弹窗与详情页共用，文案与落库口径同源（C19） */
    @GetMapping("/refund-reasons")
    public Result<List<OrderFlowPolicy.Reason>> refundReasons() {
        return Result.ok(OrderFlowPolicy.REFUND_REASONS);
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

    /**
     * 取消订单（C06/C07）：二次确认在页面做，这里强制要求带上原因（预设 code 或自由文本），
     * 原因原样落 order.cancel_reason，后台订单详情与轨迹都能看到
     */
    @PostMapping("/{id}/cancel")
    public Result<OrderView> cancelOrder(@PathVariable UUID id,
                                         @RequestBody(required = false) OrderDtos.CancelRequest request,
                                         HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("订单已取消", orderService.cancelByUser(id, loginUser.getId(), request));
    }

    /** 提交退款申请（C19）：金额由服务端按实付核定，前端传什么都不作数 */
    @PostMapping("/{id}/refund")
    public Result<OrderView> applyRefund(@PathVariable UUID id,
                                         @RequestBody(required = false) OrderDtos.RefundRequest request,
                                         HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("退款申请已提交，门店将在 1 个工作日内受理",
                orderService.applyRefund(id, loginUser.getId(), request));
    }

    /** 撤销在途退款申请（C19）：订单继续按原计划履约 */
    @PostMapping("/{id}/refund/revoke")
    public Result<OrderView> revokeRefund(@PathVariable UUID id, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok("退款申请已撤销", orderService.revokeRefund(id, loginUser.getId()));
    }

    /**
     * 售后待办（C19）：门店受理/驳回/打款都调这一条，管理员权限在此就地校验，
     * 因为 /api/orders 前缀本身只保证登录。后台订单页若要转发，直接改调本接口或
     * OrderService.reviewRefund 都可以，服务层用 CAS 保证重复审核只生效一次。
     */
    @PostMapping("/refund/{refundId}/review")
    public Result<OrderView> reviewRefund(@PathVariable UUID refundId,
                                          @Valid @RequestBody OrderDtos.RefundReviewRequest request,
                                          HttpSession session) {
        User operator = CurrentUser.requireAdmin(session);
        return Result.ok("退款进度已更新",
                orderService.reviewRefund(refundId, request.action(), request.note(), operator.getUsername()));
    }

    /** 原因字典（C06/C19）：取消与退款的预设文案随详情一次下发，页面不复制第二份 */

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
}
