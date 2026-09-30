package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.security.CaptchaService;
import org.liuym.flowerv1springboot.security.LoginAttemptService;
import org.liuym.flowerv1springboot.security.RememberMeService;
import org.liuym.flowerv1springboot.service.SessionService;
import org.liuym.flowerv1springboot.service.UserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

/**
 * 登录/注册页面与退出。安全要点：
 * 1）连续登录失败按 IP+用户名 锁定；
 * 2）登录成功更换 sessionId，阻断会话固定攻击；
 * 3）注册需图形验证码；
 * 4）勾选「记住我」下发签名 Cookie，会话过期后由 RememberMeFilter 静默续登；
 * 5）每次登录登记一条设备会话（D18），供「登录设备」列表与「退出其他设备」使用。
 * 改密校验旧密码等 JSON 接口见 {@link AuthApiController}（需 @RestController 才能被全局异常处理收敛）。
 */
@Controller
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final LoginAttemptService loginAttemptService;
    private final CaptchaService captchaService;
    private final RememberMeService rememberMeService;
    private final SessionService sessionService;

    public AuthController(UserService userService,
                          LoginAttemptService loginAttemptService,
                          CaptchaService captchaService,
                          RememberMeService rememberMeService,
                          SessionService sessionService) {
        this.userService = userService;
        this.loginAttemptService = loginAttemptService;
        this.captchaService = captchaService;
        this.rememberMeService = rememberMeService;
        this.sessionService = sessionService;
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
        // D19：记住我 Cookie 已过期或失效时，续登会静默失败，这里给出可读提示而非停留在无提示的登录页
        Object notice = session.getAttribute(CurrentUser.REMEMBER_NOTICE_KEY);
        if (notice != null) {
            session.removeAttribute(CurrentUser.REMEMBER_NOTICE_KEY);
            model.addAttribute("noticeMsg", notice.toString());
        }
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
            // deleted 与 inactive/locked 一并挡在门外；注销冷静期内 status 仍为 active，可登录撤销
            model.addAttribute("errorMsg", "账号已被禁用、锁定或已注销，请联系管理员");
            return "auth/login";
        }

        boolean rememberMe = remember != null;
        // 会话固定攻击防护：认证通过后仅更换会话标识，属性随会话迁移
        String target = (String) session.getAttribute(CurrentUser.REDIRECT_KEY);
        request.changeSessionId();
        session = request.getSession();
        session.removeAttribute(CurrentUser.REDIRECT_KEY);
        session.removeAttribute(CurrentUser.REMEMBER_NOTICE_KEY);
        session.setAttribute(CurrentUser.SESSION_KEY, user);
        loginAttemptService.reset(ip, username);
        userService.touchLastLogin(user.getId());
        // D18：登记登录设备，token 写回会话作为「当前设备」标识
        UUID deviceToken = sessionService.recordLogin(user.getId(), session.getId(), ip,
                request.getHeader("User-Agent"), rememberMe);
        session.setAttribute(CurrentUser.DEVICE_TOKEN_KEY, deviceToken);
        if (rememberMe) {
            rememberMeService.issue(response, user);
        }

        return "redirect:" + safeRedirect(target);
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            // 主动退出：撤销当前设备台账记录后销毁会话
            Object token = session.getAttribute(CurrentUser.DEVICE_TOKEN_KEY);
            if (token instanceof UUID uuid) {
                sessionService.revoke(uuid);
            }
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
