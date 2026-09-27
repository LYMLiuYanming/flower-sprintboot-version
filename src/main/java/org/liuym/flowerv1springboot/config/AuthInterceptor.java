package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.User;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一登录鉴权拦截器
 * 1）校验会话登录态与账号状态；
 * 2）管理端路径（/admin/**、/api/admin/**）追加 admin 角色校验；
 * 3）页面请求记录回跳地址，登录后回到原页面（未登录加购场景）
 */
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession();
        User loginUser = CurrentUser.of(session);

        boolean loggedIn = loginUser != null && User.STATUS_ACTIVE.equals(loginUser.getStatus());
        if (!loggedIn) {
            session.removeAttribute(CurrentUser.SESSION_KEY);
            if (isApiRequest(request)) {
                writeJson(response, 401, "请先登录");
            } else {
                rememberTarget(request, session);
                response.sendRedirect(contextPath(request) + "/auth/login");
            }
            return false;
        }

        if (requiresAdmin(request) && !loginUser.isAdmin()) {
            if (isApiRequest(request)) {
                writeJson(response, 403, "无管理员权限");
            } else {
                response.sendRedirect(contextPath(request) + "/index");
            }
            return false;
        }

        return true;
    }

    /**
     * 管理端判定统一收敛到 /admin 前缀与 /api/admin 前缀，避免历史遗留的 /api/xxx/admin 路径漏检
     */
    private boolean requiresAdmin(HttpServletRequest request) {
        String uri = path(request);
        return uri.startsWith("/admin") || uri.startsWith("/api/admin");
    }

    private void rememberTarget(HttpServletRequest request, HttpSession session) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            return;
        }
        String query = request.getQueryString();
        session.setAttribute(CurrentUser.REDIRECT_KEY, query == null ? uri : uri + "?" + query);
    }

    private String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && uri.startsWith(context)) {
            return uri.substring(context.length());
        }
        return uri;
    }

    /**
     * 判断是否为 AJAX/REST 请求（此类请求返回 JSON 而非重定向页面）
     */
    private boolean isApiRequest(HttpServletRequest request) {
        String requestedWith = request.getHeader("X-Requested-With");
        String accept = request.getHeader("Accept");
        return path(request).startsWith("/api/")
                || "XMLHttpRequest".equals(requestedWith)
                || (accept != null && accept.contains("application/json"));
    }

    private String contextPath(HttpServletRequest request) {
        String context = request.getContextPath();
        return context == null ? "" : context;
    }

    private void writeJson(HttpServletResponse response, int code, String msg) throws Exception {
        // 状态码与响应体 code 保持一致，前端统一按 body.code 判定业务结果
        response.setStatus(code);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\"}");
    }
}
