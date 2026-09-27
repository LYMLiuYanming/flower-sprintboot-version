package org.liuym.flowerv1springboot.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态以库里既有的小写 code（pending/paid/...）存取，枚举只在 Java 侧参与流转校验
 */
@Converter(autoApply = true)
public class OrderStatusConverter implements AttributeConverter<OrderStatus, String> {

    @Override
    public String convertToDatabaseColumn(OrderStatus status) {
        return status == null ? null : status.getCode();
    }

    @Override
    public OrderStatus convertToEntityAttribute(String code) {
        return code == null || code.isBlank() ? null : OrderStatus.fromCode(code);
    }
}
