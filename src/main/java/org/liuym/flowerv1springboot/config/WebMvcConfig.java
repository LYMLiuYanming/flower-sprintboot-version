package org.liuym.flowerv1springboot.config;

import org.liuym.flowerv1springboot.security.AdminAuditFilter;
import org.liuym.flowerv1springboot.security.CaptchaService;
import org.liuym.flowerv1springboot.security.LoginAttemptService;
import org.liuym.flowerv1springboot.security.RememberMeService;
import org.liuym.flowerv1springboot.security.SameOriginFilter;
import org.liuym.flowerv1springboot.security.TraceIdFilter;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.liuym.flowerv1springboot.service.UserService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册统一鉴权拦截器与横切过滤器（traceId、同源校验）
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor())
                .addPathPatterns(
                        // 管理端页面与管理端 API（拦截器内额外校验 admin 角色）
                        "/admin", "/admin/**",
                        "/api/admin/**",
                        // 用户中心页面与 API
                        "/user/**",
                        "/garden",
                        "/api/garden/**",
                        "/api/user/**",
                        "/auth/api/user/**",
                        // 购物车、订单、地址、收藏、评价、优惠券（需登录）
                        "/cart",
                        "/order/**",
                        "/api/cart/**",
                        "/api/orders/**",
                        "/api/addresses/**",
                        "/api/favorites/**",
                        "/api/reviews/**",
                        "/api/coupons/**"
                )
                .excludePathPatterns(
                        // 角标查询允许匿名访问，未登录时返回 0
                        "/api/cart/count",
                        // 券中心列表游客可见，领取时才要求登录
                        "/api/coupons/receivable"
                );
        // 公开路径不拦截：/、/index、/products、/product/{id}、/auth/**、
        // /api/products/**、/api/categories/**、/api/banners/**、/api/notices/**、/api/auth/**、静态资源
    }

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>(new TraceIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        bean.addUrlPatterns("/*");
        return bean;
    }

    @Bean
    public FilterRegistrationBean<SameOriginFilter> sameOriginFilter() {
        FilterRegistrationBean<SameOriginFilter> bean = new FilterRegistrationBean<>(new SameOriginFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 「记住我」续登要在鉴权拦截器之前完成，故与业务过滤器同层注册
     */
    @Bean
    public FilterRegistrationBean<RememberMeFilter> rememberMeFilter(RememberMeService rememberMeService,
                                                                    UserService userService) {
        FilterRegistrationBean<RememberMeFilter> bean =
                new FilterRegistrationBean<>(new RememberMeFilter(rememberMeService, userService));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        bean.addUrlPatterns("/*");
        return bean;
    }

    /**
     * 后台写操作留痕：放在鉴权之后（过滤器链最末），确保只记录真正被受理的请求
     */
    @Bean
    public FilterRegistrationBean<AdminAuditFilter> adminAuditFilter(AdminAuditService adminAuditService) {
        FilterRegistrationBean<AdminAuditFilter> bean = new FilterRegistrationBean<>(new AdminAuditFilter(adminAuditService));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 30);
        bean.addUrlPatterns("/api/admin/*");
        return bean;
    }
}
