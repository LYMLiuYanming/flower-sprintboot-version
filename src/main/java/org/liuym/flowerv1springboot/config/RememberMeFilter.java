package org.liuym.flowerv1springboot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.security.RememberMeService;
import org.liuym.flowerv1springboot.service.UserService;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 会话过期后凭「记住我」Cookie 静默续登：在 AuthInterceptor 之前把登录态写回 HttpSession，
 * 页面与 API 共用同一份会话，因此各自的取用户逻辑无需改动。
 *
 * <p>D19：Cookie 存在但续登失败（已过期 / 签名被篡改 / 改写作废 / 账号被禁用）时，
 * 不能让用户对着一个毫无提示的登录页反复刷新，故清掉失效 Cookie 并在会话里留一条可读提示，
 * 由登录页消费后清除。
 */
public class RememberMeFilter extends OncePerRequestFilter {

    private final RememberMeService rememberMeService;
    private final UserService userService;

    public RememberMeFilter(RememberMeService rememberMeService, UserService userService) {
        this.rememberMeService = rememberMeService;
        this.userService = userService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        boolean alreadyLoggedIn = session != null && CurrentUser.of(session) != null;
        if (!alreadyLoggedIn && hasRememberMeCookie(request)) {
            String notice = tryRestore(request);
            if (notice != null) {
                // 令牌已不可信，立即清除避免每次请求都重试与重复提示
                rememberMeService.clear(request, response);
                request.getSession(true).setAttribute(CurrentUser.REMEMBER_NOTICE_KEY, notice);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 续登成功返回 null；失败返回一句给用户看的提示语。
     */
    private String tryRestore(HttpServletRequest request) {
        var tokenOpt = rememberMeService.read(request);
        if (tokenOpt.isEmpty()) {
            return "登录状态已过期，请重新登录";
        }
        var token = tokenOpt.get();
        User user = userService.findById(token.userId()).orElse(null);
        if (user == null) {
            return "登录状态已失效，请重新登录";
        }
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            return "账号已被禁用或注销，请重新登录";
        }
        if (!rememberMeService.matchesPassword(token, user.getPassword())) {
            // 改密或管理员重置后旧令牌作废，属正常安全行为
            return "为保护账户安全，密码变更后需重新登录";
        }
        request.getSession(true).setAttribute(CurrentUser.SESSION_KEY, user);
        return null;
    }

    private boolean hasRememberMeCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (RememberMeService.COOKIE_NAME.equals(cookie.getName())
                    && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return true;
            }
        }
        return false;
    }
}
