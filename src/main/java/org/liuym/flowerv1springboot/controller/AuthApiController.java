package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.security.CaptchaService;
import org.liuym.flowerv1springboot.service.UserService;
import org.liuym.flowerv1springboot.vo.SupportViews.UserView;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 认证与个人中心的 JSON 接口。独立于 AuthController 的页面路由，
 * 因为只有 @RestController 才会被 GlobalExceptionHandler（annotations = RestController.class）
 * 收敛成 HTTP 200 + body.code，否则业务异常会直接抛成 500。
 */
@RestController
@RequestMapping("/auth/api")
@Tag(name = "通用 · 账户与登录态")
public class AuthApiController {

    private final UserService userService;
    private final CaptchaService captchaService;

    public AuthApiController(UserService userService, CaptchaService captchaService) {
        this.userService = userService;
        this.captchaService = captchaService;
    }

    /**
     * 注册需图形验证码，密码策略为 8-32 位字母数字组合
     */
    @PostMapping("/register")
    public Result<UserView> register(@Valid @RequestBody UserDtos.RegisterRequest form) {
        if (!captchaService.verify(form.captchaId(), form.captcha())) {
            return Result.error(400, "验证码不正确或已过期");
        }
        if (userService.existsByUsername(form.username())) {
            return Result.error("用户名已被占用");
        }
        if (userService.existsByPhone(form.phone())) {
            return Result.error("手机号已被注册");
        }
        String email = blankToNull(form.email());
        if (email != null && userService.existsByEmail(email)) {
            return Result.error("邮箱已被注册");
        }

        User user = userService.register(form.username(), form.password(), form.fullName(), form.phone(), email);
        return Result.ok("注册成功", UserView.from(user));
    }

    @GetMapping("/user/profile")
    public Result<UserView> getProfile(HttpSession session) {
        User loginUser = CurrentUser.require(session);
        return Result.ok(userService.findById(loginUser.getId())
                .map(UserView::from)
                .orElseThrow(() -> BusinessException.notFound("用户不存在")));
    }

    @PutMapping("/user/profile")
    public Result<UserView> updateProfile(@Valid @RequestBody UserDtos.ProfileRequest form, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        User updated = userService.updateProfile(loginUser.getId(), form);
        session.setAttribute(CurrentUser.SESSION_KEY, updated);
        return Result.ok("保存成功", UserView.from(updated));
    }

    @PutMapping("/user/password")
    public Result<Void> changePassword(@Valid @RequestBody UserDtos.PasswordRequest form, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        userService.changePassword(loginUser.getId(), form.oldPassword(), form.newPassword());
        session.removeAttribute(CurrentUser.SESSION_KEY);
        return Result.ok("密码已修改，请使用新密码重新登录", null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
