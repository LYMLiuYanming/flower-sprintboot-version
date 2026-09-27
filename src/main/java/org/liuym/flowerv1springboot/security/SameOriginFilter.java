package org.liuym.flowerv1springboot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * CSRF 防线：所有写操作（POST/PUT/PATCH/DELETE）要求同源。
 * 浏览器发起跨站表单/AJAX 写请求时必然带上与其页面源不一致的 Origin/Referer，直接拒绝；
 * 非浏览器客户端无 Origin，属正常服务端调用，放行并配合 Cookie 的 SameSite=Lax 收敛风险。
 */
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SameOriginFilter.class);
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final List<String> SKIP_PREFIXES = List.of("/actuator/");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("拒绝跨站写请求 {} {} origin={}", request.getMethod(), request.getRequestURI(), request.getHeader("Origin"));
        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"msg\":\"请求来源不合法\"}");
    }

    private boolean isAllowed(HttpServletRequest request) {
        if (!WRITE_METHODS.contains(request.getMethod().toUpperCase())) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String skip : SKIP_PREFIXES) {
            if (uri.startsWith(skip)) {
                return true;
            }
        }
        String origin = request.getHeader("Origin");
        String source = (origin == null || origin.isBlank()) ? request.getHeader("Referer") : origin;
        if (source == null || source.isBlank()) {
            return true;
        }
        String host = hostOf(source);
        if (host == null) {
            return false;
        }
        return allowedHosts(request).stream().anyMatch(allowed -> allowed.equalsIgnoreCase(host));
    }

    /**
     * 反向代理场景下 serverPort 是内部端口，优先取 X-Forwarded-Port
     */
    private Set<String> allowedHosts(HttpServletRequest request) {
        String name = request.getServerName();
        int port = request.getServerPort();
        String forwardedPort = request.getHeader("X-Forwarded-Port");
        if (forwardedPort != null && forwardedPort.matches("^\\d{1,5}$")) {
            port = Integer.parseInt(forwardedPort);
        }
        return Set.of(name + ":" + port, name + ":80", name + ":443");
    }

    private String hostOf(String source) {
        try {
            URI uri = URI.create(source.trim());
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            return port == -1 ? uri.getHost() + ":" + defaultPort(uri.getScheme()) : uri.getHost() + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }
}
