package org.liuym.flowerv1springboot.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.liuym.flowerv1springboot.model.User;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 序列化层的硬红线（L01）：实体口令在任何接口里都不允许出现。
 *
 * <p>各 VO 已经刻意不带 password 字段，但「刻意」不等于「不会漏」——只要有一次直接返回实体
 * （新增接口图省事、日志里 objectMapper.writeValueAsString(user)）就会把 BCrypt 摘要甩出网。
 * 这里用 Jackson mixin 在映射层一次性封死，不改实体、也不依赖每个调用方自觉。
 *
 * <p>只影响 JSON 收发，不影响 JPA：实体落库走的是 Hibernate 的字段访问，与 Jackson 无关。
 */
@Configuration
public class JacksonHardeningConfig {

    /** 抽象类里的方法签名要与目标类一致，Jackson 按名字把注解「贴」到 User 上 */
    abstract static class UserMixin {
        @JsonIgnore
        abstract String getPassword();
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer passwordNeverSerialized() {
        return builder -> builder.mixIn(User.class, UserMixin.class);
    }
}
