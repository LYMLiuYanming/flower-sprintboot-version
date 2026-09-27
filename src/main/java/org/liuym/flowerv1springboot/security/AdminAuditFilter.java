package org.liuym.flowerv1springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 后台写操作留痕：拦截 /api/admin/** 的 POST/PUT/PATCH/DELETE，
 * 记录操作人、模块、动作、请求摘要与业务返回码，供「操作日志」页追溯。
 * 旁路能力——落库失败只打应用日志，绝不影响业务请求。
 */
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String PREFIX = "/api/admin/";
    private static final int DETAIL_LIMIT = 1800;

    /** 路径第二段 → 模块中文名 */
    private static final Map<String, String> MODULES = new LinkedHashMap<>();
    /** 路径尾段 → 具体动作，优先于 HTTP 方法推断 */
    private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

    static {
        MODULES.put("products", "商品");
        MODULES.put("categories", "分类");
        MODULES.put("orders", "订单");
        MODULES.put("users", "用户");
        MODULES.put("banners", "轮播图");
        MODULES.put("notices", "公告");
        MODULES.put("coupons", "优惠券");
        MODULES.put("stats", "统计看板");
        MODULES.put("audit-logs", "操作日志");

        ACTIONS.put("status", "启停");
        ACTIONS.put("ship", "发货");
        ACTIONS.put("sort", "排序");
        ACTIONS.put("stock", "调整库存");
        ACTIONS.put("batch-status", "批量上下架");
        ACTIONS.put("batch-delete", "批量删除");
        ACTIONS.put("reset-password", "重置密码");
        ACTIONS.put("refresh", "刷新缓存");
        ACTIONS.put("issue", "定向发券");
    }

    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(\"(?:[\\w-]*(?:password|passwd|secret|token|captcha)\\w*)\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULT_CODE = Pattern.compile("\"code\"\\s*:\\s*(\\d+)");
    private static final Pattern RESULT_MSG = Pattern.compile("\"msg\"\\s*:\\s*\"([^\"]{0,200})\"");

    private final AdminAuditService adminAuditService;

    public AdminAuditFilter(AdminAuditService adminAuditService) {
        this.adminAuditService = adminAuditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathOf(request);
        if (!shouldRecord(request, path)) {
            chain.doFilter(request, response);
            return;
        }
        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cachedRequest, cachedResponse);
        } finally {
            try {
                adminAuditService.record(of(cachedRequest, cachedResponse, path));
            } catch (RuntimeException e) {
                log.warn("操作留痕异常 {} {}: {}", request.getMethod(), path, e.getMessage());
            }
            cachedResponse.copyBodyToResponse();
        }
    }

    private boolean shouldRecord(HttpServletRequest request, String path) {
        return WRITE_METHODS.contains(request.getMethod().toUpperCase())
                && path.startsWith(PREFIX)
                && !path.startsWith(PREFIX + "audit-logs");
    }

    private AdminAuditLog of(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, String path) {
        AdminAuditLog entry = new AdminAuditLog();
        User operator = CurrentUser.of(request.getSession());
        entry.setOperatorId(operator == null ? null : operator.getId());
        entry.setOperatorName(operator == null ? "未登录" : operator.getUsername());
        entry.setMethod(request.getMethod().toUpperCase());
        entry.setModule(moduleOf(path));
        entry.setAction(actionOf(entry.getMethod(), path));
        entry.setUri(truncate(path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()), 300));
        entry.setDetail(detailOf(request));
        entry.setIp(ipOf(request));
        String body = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        Matcher code = RESULT_CODE.matcher(body);
        if (code.find()) {
            entry.setResultCode(Integer.valueOf(code.group(1)));
        }
        Matcher msg = RESULT_MSG.matcher(body);
        if (msg.find()) {
            entry.setResultMsg(truncate(msg.group(1), 200));
        }
        return entry;
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private String moduleOf(String path) {
        String rest = path.substring(PREFIX.length());
        String key = rest.split("/")[0];
        return MODULES.getOrDefault(key, key);
    }

    /**
     * 末段命中专用动作用专用动作，否则按 HTTP 方法推断；带 {id} 的 PUT/DELETE 分别视为编辑/删除
     */
    private String actionOf(String method, String path) {
        String[] segments = path.substring(PREFIX.length()).split("/");
        String tail = segments.length > 1 ? segments[segments.length - 1] : "";
        String special = ACTIONS.get(tail);
        if (special != null) {
            return special;
        }
        return switch (method) {
            case "POST" -> segments.length > 1 ? "变更" : "新增";
            case "PUT", "PATCH" -> "编辑";
            case "DELETE" -> "删除";
            default -> method;
        };
    }

    /** 请求体摘要：密码/验证码类字段一律掩码，超长截断 */
    private String detailOf(ContentCachingRequestWrapper request) {
        StringBuilder detail = new StringBuilder();
        if (isJson(request.getContentType())) {
            String body = new String(request.getContentAsByteArray(), StandardCharsets.UTF_8);
            detail.append(SECRET_FIELD.matcher(body).replaceAll("$1\"******\""));
        } else if (request.getContentType() != null) {
            detail.append("[非 JSON 请求体 ").append(request.getContentType()).append(']');
        }
        return detail.length() == 0 ? null : truncate(detail.toString(), DETAIL_LIMIT);
    }

    private boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    private String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 64);
        }
        return truncate(request.getRemoteAddr(), 64);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + '…';
    }
}
