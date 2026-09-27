package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.security.CaptchaService;
import org.liuym.flowerv1springboot.security.LoginAttemptService;
import org.liuym.flowerv1springboot.security.RememberMeService;
import org.liuym.flowerv1springboot.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * 登录/注册页面与退出。安全要点：
 * 1）连续登录失败按 IP+用户名 锁定；
 * 2）登录成功更换 sessionId，阻断会话固定攻击；
 * 3）注册需图形验证码；
 * 4）勾选「记住我」下发签名 Cookie，会话过期后由 RememberMeFilter 静默续登。
 * 改密校验旧密码等 JSON 接口见 {@link AuthApiController}（需 @RestController 才能被全局异常处理收敛）。
 */
@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final LoginAttemptService loginAttemptService;
    private final CaptchaService captchaService;
    private final RememberMeService rememberMeService;

    public AuthController(UserService userService,
                          LoginAttemptService loginAttemptService,
                          CaptchaService captchaService,
                          RememberMeService rememberMeService) {
        this.userService = userService;
        this.loginAttemptService = loginAttemptService;
        this.captchaService = captchaService;
        this.rememberMeService = rememberMeService;
    }

    @GetMapping("/login")
    public String loginPage(@RequestParam(required = false) String redirect, Model model, HttpSession session) {
        if (CurrentUser.of(session) != null) {
            return "redirect:/index";
        }
        // 未登录被拦截的功能（加购、结算）可携带回跳目标，登录成功后一步回到原位
        String target = safeRedirect(redirect);
        if (redirect != null && !redirect.isBlank()) {
            session.setAttribute(CurrentUser.REDIRECT_KEY, target);
        }
        model.addAttribute("errorMsg", "");
        return "auth/login";
    }

    @PostMapping("/login")
    public String loginSubmit(@RequestParam String username,
                              @RequestParam String password,
                              @RequestParam(name = "remember", required = false) String remember,
                              HttpServletRequest request,
                              HttpServletResponse response,
                              Model model) {
        HttpSession session = request.getSession();
        String ip = clientIp(request);

        long lockedSeconds = loginAttemptService.remainingLockSeconds(ip, username);
        if (lockedSeconds > 0) {
            model.addAttribute("errorMsg", "登录失败次数过多，请 " + (lockedSeconds / 60 + 1) + " 分钟后再试");
            return "auth/login";
        }

        Optional<User> userOpt = userService.authenticate(username, password);
        if (userOpt.isEmpty()) {
            loginAttemptService.recordFailure(ip, username);
            model.addAttribute("errorMsg", "用户名或密码错误");
            return "auth/login";
        }
        User user = userOpt.get();
        if (!User.STATUS_ACTIVE.equals(user.getStatus())) {
            model.addAttribute("errorMsg", "账号已被禁用或锁定，请联系管理员");
            return "auth/login";
        }

        // 会话固定攻击防护：认证通过后仅更换会话标识，属性随会话迁移
        String target = (String) session.getAttribute(CurrentUser.REDIRECT_KEY);
        request.changeSessionId();
        session.removeAttribute(CurrentUser.REDIRECT_KEY);
        HttpSession fresh = request.getSession();
        fresh.setAttribute(CurrentUser.SESSION_KEY, user);
        loginAttemptService.reset(ip, username);
        userService.touchLastLogin(user.getId());
        if (remember != null) {
            rememberMeService.issue(response, user);
        }

        return "redirect:" + safeRedirect(target);
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        rememberMeService.clear(request, response);
        return "redirect:/index";
    }

    @GetMapping("/register")
    public String registerPage(HttpSession session, Model model) {
        if (CurrentUser.of(session) != null) {
            return "redirect:/index";
        }
        model.addAttribute("captchaId", captchaService.issue().captchaId());
        return "auth/register";
    }

    /**
     * 只允许回跳站内路径，避免开放重定向
     */
    private String safeRedirect(String target) {
        if (target == null || target.isBlank() || !target.startsWith("/") || target.startsWith("//")) {
            return "/index";
        }
        return target;
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
