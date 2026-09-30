package org.liuym.flowerv1springboot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * F6 会话外置化后的 Cookie 口径。
 *
 * <p>必须显式声明：Spring Session 接管会话后**不再读** application.properties 里的
 * {@code server.servlet.session.cookie.*}，默认会把 Cookie 名换成 {@code SESSION}、
 * SameSite 退回默认值。名字一变，原有 FLOWERSESSIONID 的在线会话全部失效，
 * 且 remember-me 兜底逻辑也会因为找不到会话 Cookie 而多绕一层。
 */
@Configuration
public class SessionConfig {

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("FLOWERSESSIONID");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        // 与改造前一致：lax 既挡住跨站 POST 带 cookie，又不破坏正常跳转登录
        serializer.setSameSite("lax");
        serializer.setUseSecureCookie(false);
        return serializer;
    }
}
