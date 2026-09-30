package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.CheckoutService;
import org.liuym.flowerv1springboot.vo.CheckoutViews;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 结算页试算 API（B08-B16、B21、B23）。
 *
 * <p>未登录也放行：游客能看到按库中真实价格与运费算出的金额，只有提交订单才要求登录。
 * 该路径不在鉴权拦截清单内，因此这里不能出现任何用户私有数据（券、积分按登录态给）。
 */
@RestController
@RequestMapping("/api/checkout")
@Tag(name = "前台 · 结算试算")
public class CheckoutController {

    private final CheckoutService checkoutService;

    public CheckoutController(CheckoutService checkoutService) {
        this.checkoutService = checkoutService;
    }

    @PostMapping("/summary")
    @Operation(summary = "结算试算", description = "服务端一次性返回金额、券、积分、配送对比、时段与天气建议")
    public Result<CheckoutViews.Summary> summary(@Valid @RequestBody OrderDtos.CheckoutRequest request,
                                                 HttpSession session) {
        User loginUser = CurrentUser.of(session);
        return Result.ok(checkoutService.summary(loginUser == null ? null : loginUser.getId(), request));
    }

    /**
     * 试算失败也要给出字段名（B18），否则结算页只能整页 toast，用户找不到哪一格填错
     */
    @ExceptionHandler(CheckoutPolicy.CheckoutValidation.class)
    public Result<Void> handleValidation(CheckoutPolicy.CheckoutValidation e) {
        return Result.<Void>error(e.getCode(), e.getMessage())
                .with("field", e.field())
                .with("errors", e.issues());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<FieldError> errors = e.getBindingResult().getFieldErrors();
        errors.forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        String message = fields.values().stream().filter(java.util.Objects::nonNull)
                .findFirst().orElse("参数校验失败");
        return Result.<Void>error(400, message)
                .with("field", fields.keySet().stream().findFirst().orElse(null))
                .with("errors", fields.entrySet().stream()
                        .map(entry -> entry.getValue() == null ? entry.getKey() : entry.getValue()).toList());
    }
}
