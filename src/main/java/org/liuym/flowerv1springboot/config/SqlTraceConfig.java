package org.liuym.flowerv1springboot.config;

import jakarta.annotation.PostConstruct;
import org.hibernate.cfg.AvailableSettings;
import org.liuym.flowerv1springboot.common.SqlTrace;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SQL 观测装配（L08）：注册 Hibernate 语句拦截点 + 请求级跟踪拦截器。
 *
 * <p>与 {@link RateLimitConfig} 一样另起一个 {@link WebMvcConfigurer}，不改 {@link WebMvcConfig} 的既有注册；
 * order 用 HIGHEST_PRECEDENCE，让跟踪起点排在鉴权与限流之前，这样被 401/429 挡掉的请求
 * 也能留下「这次请求到底有没有走到 SQL」的证据。
 */
@Configuration
public class SqlTraceConfig implements WebMvcConfigurer {

    private final SqlTraceInterceptor sqlTraceInterceptor;
    private final boolean enabled;
    private final int repeatThreshold;
    private final int warnSqlCount;
    private final long warnSqlMillis;
    private final long warnRequestMillis;

    public SqlTraceConfig(SqlTraceInterceptor sqlTraceInterceptor,
                          @Value("${app.sql-trace.enabled:true}") boolean enabled,
                          @Value("${app.sql-trace.repeat-threshold:5}") int repeatThreshold,
                          @Value("${app.sql-trace.warn-sql-count:15}") int warnSqlCount,
                          @Value("${app.sql-trace.warn-sql-ms:500}") long warnSqlMillis,
                          @Value("${app.sql-trace.warn-request-ms:1500}") long warnRequestMillis) {
        this.sqlTraceInterceptor = sqlTraceInterceptor;
        this.enabled = enabled;
        this.repeatThreshold = repeatThreshold;
        this.warnSqlCount = warnSqlCount;
        this.warnSqlMillis = warnSqlMillis;
        this.warnRequestMillis = warnRequestMillis;
    }

    /** 阈值要先落到静态观测器，Hibernate 拦截器可能比任何 Bean 的 @PostConstruct 更早被调用 */
    @PostConstruct
    public void applyThresholds() {
        SqlTrace.configure(enabled, repeatThreshold, warnSqlCount, warnSqlMillis, warnRequestMillis);
    }

    /**
     * 以「类名字符串」的形式把 StatementInspector 交给 Hibernate：
     * Hibernate 用无参构造自己实例化，因此本类不依赖 Spring，也不会被延迟初始化时序坑到。
     */
    @Bean
    public HibernatePropertiesCustomizer sqlTraceInspectorCustomizer() {
        return properties -> {
            if (enabled) {
                properties.put(AvailableSettings.STATEMENT_INSPECTOR, SqlStatementInspector.class.getName());
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(sqlTraceInterceptor).addPathPatterns("/**").order(Ordered.HIGHEST_PRECEDENCE);
    }
}
