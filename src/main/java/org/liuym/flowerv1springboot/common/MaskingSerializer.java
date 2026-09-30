package org.liuym.flowerv1springboot.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;

import java.io.IOException;

/**
 * {@link Masked} 的序列化实现：把注解上的 {@link Masked.Kind} 解析成 {@link Masking} 的一次调用。
 *
 * <p>实现 ContextualSerializer 是因为 Kind 属于「每个字段各不相同」的信息——
 * 不按上下文创建实例的话，第一个字段的类型会串到所有共用实例的字段上。
 */
public class MaskingSerializer extends JsonSerializer<String> implements ContextualSerializer {

    private final Masked.Kind kind;

    public MaskingSerializer() {
        this(Masked.Kind.PHONE);
    }

    private MaskingSerializer(Masked.Kind kind) {
        this.kind = kind;
    }

    @Override
    public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        gen.writeString(kind.apply(value));
    }

    @Override
    public JsonSerializer<?> createContextual(SerializerProvider prov, BeanProperty property)
            throws JsonMappingException {
        if (property == null) {
            return this;
        }
        Masked masked = property.getAnnotation(Masked.class);
        if (masked == null) {
            masked = property.getContextAnnotation(Masked.class);
        }
        return masked == null ? this : new MaskingSerializer(masked.value());
    }
}
