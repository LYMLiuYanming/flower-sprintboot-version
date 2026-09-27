package org.liuym.flowerv1springboot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.common.Result;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;
import java.util.Map;

/**
 * /error 统一出口：未匹配到控制器的请求（如写错的接口路径）不会经过 {@link GlobalExceptionHandler}，
 * 必须由这里兜住——接口请求返回 Result JSON，页面请求渲染 4xx/5xx 视图，
 * 且不输出 Spring 默认的 timestamp/trace，避免泄露堆栈与框架信息。
 */
@Controller
public class ErrorPageController implements ErrorController {

    private final ErrorAttributes errorAttributes;
    private final ObjectMapper objectMapper;

    public ErrorPageController(ErrorAttributes errorAttributes, ObjectMapper objectMapper) {
        this.errorAttributes = errorAttributes;
        this.objectMapper = objectMapper;
    }

    @RequestMapping("${server.error.path:/error}")
    public ModelAndView handleError(HttpServletRequest request, HttpServletResponse response) throws IOException {
        int status = status(request);
        if (expectsJson(request)) {
            // 与 GlobalExceptionHandler 一致：HTTP 恒为 200，业务结果由 body.code 表达，前端才不会误判成网络异常
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getOutputStream(),
                    Result.error(status, status == HttpServletResponse.SC_NOT_FOUND ? "接口不存在" : "请求处理失败"));
            return null;
        }
        return new ModelAndView(status >= HttpServletResponse.SC_INTERNAL_SERVER_ERROR ? "error/5xx" : "error/4xx")
                .addObject("status", status)
                .addObject("path", originalUri(request));
    }

    private int status(HttpServletRequest request) {
        Map<String, Object> attributes = errorAttributes.getErrorAttributes(
                new ServletWebRequest(request), ErrorAttributeOptions.defaults());
        Object status = attributes.get("status");
        return status instanceof Number number ? number.intValue() : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
    }

    /**
     * ERROR 转发下 getRequestURI() 变成 /error，原始路径只能从容器属性取
     */
    private String originalUri(HttpServletRequest request) {
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return uri instanceof String path && !path.isBlank() ? path : request.getRequestURI();
    }

    private boolean expectsJson(HttpServletRequest request) {
        if (originalUri(request).startsWith("/api/")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        return accept != null && accept.contains("application/json") && !accept.contains("text/html");
    }
}
