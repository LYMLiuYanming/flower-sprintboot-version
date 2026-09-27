package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * REST 层全局异常处理：把异常统一收敛成 Result JSON，控制器不再散落 try-catch。
 * HTTP 状态固定 200、业务结果由 body.code 表达，与既有前端约定保持一致
 * （页面级错误由 ErrorPageController 渲染 404/500 视图）。
 */
@RestControllerAdvice(annotations = RestController.class)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 {} {} code={} msg={}", request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * DTO 校验失败：取全部字段提示拼接，前端 toast 一次看清
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::fieldMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 {} {} -> {}", request.getMethod(), request.getRequestURI(), msg);
        return Result.error(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .collect(Collectors.joining("；"));
        return Result.error(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, IllegalArgumentException.class})
    public Result<Void> handleBadRequest(Exception e, HttpServletRequest request) {
        log.warn("请求参数错误 {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.error(400, friendlyBadRequest(e));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return Result.error(405, "不支持的请求方式：" + e.getMethod());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResource(NoResourceFoundException e) {
        return Result.notFound("接口不存在：" + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常 {} {}", request.getMethod(), request.getRequestURI(), e);
        return Result.error("服务开小差了，请稍后重试");
    }

    private static String fieldMessage(FieldError error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank() ? error.getField() + " 不合法" : message;
    }

    private static String friendlyBadRequest(Exception e) {
        if (e instanceof IllegalArgumentException && e.getMessage() != null && !e.getMessage().isBlank()) {
            return e.getMessage();
        }
        if (e instanceof MethodArgumentTypeMismatchException mismatch) {
            return mismatch.getName() + " 参数格式不正确";
        }
        if (e instanceof MissingServletRequestParameterException missing) {
            return "缺少必填参数：" + missing.getParameterName();
        }
        if (e instanceof HttpMessageNotReadableException) {
            return "请求体格式不正确";
        }
        return "请求参数不正确";
    }
}
