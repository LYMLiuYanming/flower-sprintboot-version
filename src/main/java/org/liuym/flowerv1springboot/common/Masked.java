package org.liuym.flowerv1springboot.common;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * VO 字段级脱敏标记（L01）：标注在 record 组件或 getter 上，序列化时按 {@link Kind} 走
 * {@link Masking} 的统一口径，业务代码不必在每个 VO 里自己拼星号。
 *
 * <pre>
 * public record OrderBrief(@Masked(Masked.Kind.PHONE) String receiverPhone, ...) {}
 * </pre>
 *
 * <p>record 组件上的注解会随组件传递到访问器，Jackson 能读到，因此不需要额外写 getter。
 */
@JacksonAnnotationsInside
@JsonSerialize(using = MaskingSerializer.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.TYPE_USE})
public @interface Masked {

    /** 打码类型，取值与 {@link Masking} 的方法一一对应 */
    Kind value() default Kind.PHONE;

    enum Kind {
        PHONE,
        ADDRESS,
        NAME,
        EMAIL,
        ID_CARD,
        FINGERPRINT;

        /** 由注解值分派到 Masking，集中一处方便单测覆盖 */
        public String apply(String raw) {
            return switch (this) {
                case PHONE -> Masking.phone(raw);
                case ADDRESS -> Masking.address(raw);
                case NAME -> Masking.name(raw);
                case EMAIL -> Masking.email(raw);
                case ID_CARD -> Masking.idCard(raw);
                case FINGERPRINT -> Masking.fingerprint(raw);
            };
        }
    }
}
