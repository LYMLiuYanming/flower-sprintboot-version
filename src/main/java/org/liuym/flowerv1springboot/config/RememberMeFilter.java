package org.liuym.flowerv1springboot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
        if (session == null || CurrentUser.of(session) == null) {
            rememberMeService.read(request).ifPresent(token ->
                    userService.findById(token.userId())
                            .filter(user -> User.STATUS_ACTIVE.equals(user.getStatus()))
                            .filter(user -> rememberMeService.matchesPassword(token, user.getPassword()))
                            .ifPresent(user -> request.getSession(true).setAttribute(CurrentUser.SESSION_KEY, user)));
        }
        chain.doFilter(request, response);
    }
}
