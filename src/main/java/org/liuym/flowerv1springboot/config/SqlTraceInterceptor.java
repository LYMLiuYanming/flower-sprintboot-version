package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.common.SqlTrace;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 请求级 SQL 跟踪的起止点（L08）。
 *
 * <p>收尾放在 {@code afterCompletion} 而不是 postHandle：postHandle 之后还要渲染模板，
 * 页面渲染期间懒加载触发的 SQL 会被漏计，而 afterCompletion 在渲染完成后才跑。
 */
@Component
public class SqlTraceInterceptor implements HandlerInterceptor {

    /** 诊断响应头开关：只在排查时打开，常态给前端看 SQL 条数等于给探测者递线索 */
    private static final String HEADER_SQL_COUNT = "X-Sql-Count";
    private static final String HEADER_REQUEST_MS = "X-Request-Ms";

    private final boolean exposeHeader;

    public SqlTraceInterceptor(
            @org.springframework.beans.factory.annotation.Value("${app.sql-trace.expose-header:false}") boolean exposeHeader) {
        this.exposeHeader = exposeHeader;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = pathOf(request);
        if (looksStatic(path)) {
            // 静态资源不建上下文：否则诊断页会被几十条 css/js 记录挤满，真正的慢接口看不见
            SqlTrace.abandon();
            return true;
        }
        SqlTrace.begin(request.getMethod(), path);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SqlTrace.Snapshot snapshot = SqlTrace.finish();
        if (snapshot == null || !exposeHeader || response.isCommitted()) {
            return;
        }
        response.setHeader(HEADER_SQL_COUNT, String.valueOf(snapshot.sqlCount()));
        response.setHeader(HEADER_REQUEST_MS, String.valueOf(snapshot.requestMs()));
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    /** 带静态资源后缀的请求不参与 SQL 观测 */
    private static boolean looksStatic(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot < path.lastIndexOf('/')) {
            return false;
        }
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "css", "js", "mjs", "map", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
                    "woff", "woff2", "ttf", "eot", "json", "txt" -> true;
            default -> false;
        };
    }
}
