package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局模型增强：将 session 中的登录用户统一注入 Thymeleaf 模型，
 * 供各页面 header 公共片段判断登录态使用，避免每个页面控制器手工 addAttribute 漏传
 */
@ControllerAdvice
public class GlobalModelAdvice {

    @ModelAttribute("loginUser")
    public Object loginUser(HttpSession session) {
        return session.getAttribute("loginUser");
    }
}
