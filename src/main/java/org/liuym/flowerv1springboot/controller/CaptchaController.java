package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.security.CaptchaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 注册页图形验证码：答案只留服务端，前端仅拿到 captchaId 与 base64 图片
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "通用 · 验证码")
public class CaptchaController {

    private final CaptchaService captchaService;

    public CaptchaController(CaptchaService captchaService) {
        this.captchaService = captchaService;
    }

    @GetMapping("/captcha")
    public Result<CaptchaService.Challenge> captcha() {
        return Result.ok(captchaService.issue());
    }
}
