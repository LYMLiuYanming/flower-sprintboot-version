package org.liuym.flowerv1springboot.config;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * 全局模型增强：将 session 中的登录用户统一注入 Thymeleaf 模型，
 * 供各页面 header 公共片段判断登录态使用，避免每个页面控制器手工 addAttribute 漏传。
 *
 * <p>同时下发高德底图所需的浏览器侧密钥。只下发 Web 端 key 与其安全码（这两样本来就会出现在
 * 前端源码里，靠域名白名单约束），Web 服务端密钥只在 AmapClient 内部使用，任何页面都拿不到。
 */
@ControllerAdvice
public class GlobalModelAdvice {

    private final String amapWebKey;
    private final String amapWebSecureKey;

    public GlobalModelAdvice(@Value("${amap.web-key:}") String amapWebKey,
                             @Value("${amap.web-secure-key:}") String amapWebSecureKey) {
        this.amapWebKey = amapWebKey == null ? "" : amapWebKey.trim();
        this.amapWebSecureKey = amapWebSecureKey == null ? "" : amapWebSecureKey.trim();
    }

    @ModelAttribute("loginUser")
    public Object loginUser(HttpSession session) {
        return session.getAttribute("loginUser");
    }

    @ModelAttribute("amapWebKey")
    public String amapWebKey() {
        return amapWebKey;
    }

    /** 非空才下发安全码，未配置密钥的环境里页面直接走文字降级 */
    @ModelAttribute("amapWebSecureKey")
    public String amapWebSecureKey() {
        return amapWebKey.isEmpty() ? "" : amapWebSecureKey;
    }
}
