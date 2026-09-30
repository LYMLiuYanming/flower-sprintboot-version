package org.liuym.flowerv1springboot.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 退款状态沿用与 order.status 一致的小写 code 存取，避免同一张表里出现 PENDING/pending 两套写法
 */
@Converter(autoApply = false)
public class RefundStatusConverter implements AttributeConverter<RefundStatus, String> {

    @Override
    public String convertToDatabaseColumn(RefundStatus status) {
        return status == null ? null : status.getCode();
    }

    @Override
    public RefundStatus convertToEntityAttribute(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return RefundStatus.fromCode(code);
        } catch (IllegalArgumentException e) {
            // 历史脏数据不能让详情页 500，按「无退款申请」降级
            return null;
        }
    }
}
