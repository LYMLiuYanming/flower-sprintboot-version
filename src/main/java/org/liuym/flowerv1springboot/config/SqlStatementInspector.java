package org.liuym.flowerv1springboot.config;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.liuym.flowerv1springboot.common.SqlTrace;

/**
 * Hibernate 语句拦截点（L08）：把每条即将执行的 SQL 交给 {@link SqlTrace} 计数。
 *
 * <p>由 Hibernate 自己按类名实例化（见 {@link SqlTraceConfig}），所以这里必须留公有无参构造，
 * 也不能依赖 Spring 注入——它不归容器管。状态全部落在 {@link SqlTrace} 的 ThreadLocal 上。
 *
 * <p>本类必须极薄：它在所有 SQL 的热路径上，任何一次异常或慢逻辑都会放大到整站。
 * 因此这里 try-catch 吞掉一切，绝不允许观测代码把业务查询带崩。
 */
public class SqlStatementInspector implements StatementInspector {

    @Override
    public String inspect(String sql) {
        try {
            SqlTrace.recordSql(sql);
        } catch (RuntimeException ignored) {
            // 观测失败不影响 SQL 本身
        }
        return sql;
    }
}
