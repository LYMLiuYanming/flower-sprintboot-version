package org.liuym.flowerv1springboot.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 接口契约文档：认证走会话 Cookie，故以 apiKey(cookie) 描述登录态，
 * Swagger UI 里带上浏览器会话即可直接调试
 */
@Configuration
public class OpenApiConfig {

    private static final String SESSION_SCHEME = "sessionCookie";

    /** 显式声明分组顺序，Swagger UI 按此列表排布，而非按控制器扫描先后 */
    private static final List<String[]> TAGS = List.of(
            new String[]{"通用 · 验证码", "登录/注册前置的人机校验"},
            new String[]{"通用 · 账户与登录态", "注册、个人资料与改密，需登录"},
            new String[]{"前台 · 商品与搜索", "商品列表、详情、分类筛选与关键词检索"},
            new String[]{"前台 · 分类", "启用分类的树形数据"},
            new String[]{"前台 · 轮播图", "首页轮播"},
            new String[]{"前台 · 公告", "前台可见公告"},
            new String[]{"前台 · 购物车", "加购、改量、删除与清空"},
            new String[]{"前台 · 收货地址", "地址簿增删改查与默认地址"},
            new String[]{"前台 · 收藏", "商品收藏与取消收藏"},
            new String[]{"前台 · 优惠券", "领券中心、我的券包与结算试算"},
            new String[]{"前台 · 下单与订单", "下单、支付、取消、确认收货与订单查询"},
            new String[]{"前台 · 评价", "已完成后订单的商品评价"},
            new String[]{"后台 · 商品管理", "商品 CRUD、库存与批量上下架"},
            new String[]{"后台 · 分类管理", "分类 CRUD 与排序"},
            new String[]{"后台 · 订单管理", "订单查询、发货与详情"},
            new String[]{"后台 · 用户管理", "用户查询、状态与重置密码"},
            new String[]{"后台 · 优惠券管理", "券模板 CRUD 与发批"},
            new String[]{"后台 · 轮播图管理", "轮播图 CRUD 与启停"},
            new String[]{"后台 · 公告管理", "公告 CRUD 与启停"},
            new String[]{"后台 · 统计看板", "概览、趋势与排行"},
            new String[]{"后台 · 操作日志", "管理端写操作审计"});

    @Bean
    public OpenAPI flowerv1OpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("花语轩鲜花销售系统 API")
                        .version("0.0.1-SNAPSHOT")
                        .description("前台商城 + 后台管理接口。统一响应体 Result{code,msg,data,count}，"
                                + "code=200 成功，401 未登录，403 无权限，404 不存在，400/500 业务或参数错误")
                        .contact(new Contact().name("liuym")))
                .tags(TAGS.stream().map(t -> new Tag().name(t[0]).description(t[1])).toList())
                .components(new Components().addSecuritySchemes(SESSION_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("FLOWERSESSIONID")
                                .description("登录后由服务端下发的会话 Cookie")))
                .addSecurityItem(new SecurityRequirement().addList(SESSION_SCHEME));
    }
}
