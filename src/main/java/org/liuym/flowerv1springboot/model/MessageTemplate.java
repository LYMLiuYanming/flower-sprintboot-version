package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 消息模板（U07）：标题/正文里的 {@code {slot}} 与 varKeys 一一对应。
 *
 * <p>varKeys 声明的是「必填槽位」：渲染时任一必填槽位缺值，整条消息不落库并返回错误，
 * 宁可不发也不能发出「订单  已发货」这种半成品文案。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "message_template", schema = "public")
public class MessageTemplate {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "code", nullable = false, length = 40, unique = true)
    private String code;

    @Column(name = "category", nullable = false, length = 20)
    private String category = UserMessage.CATEGORY_SYSTEM;

    @Column(name = "title_tpl", nullable = false, length = 200)
    private String titleTpl;

    @Column(name = "content_tpl", nullable = false, columnDefinition = "TEXT")
    private String contentTpl;

    /** 必填变量名，逗号分隔；空表示该模板不需要变量 */
    @Column(name = "var_keys", length = 300)
    private String varKeys;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "remark", length = 200)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 变量名清洗：运营在后台填的 " orderNo , amount " 与代码传的 key 必须能对上 */
    public java.util.List<String> requiredVars() {
        if (varKeys == null || varKeys.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(varKeys.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public boolean isDisabled() {
        return !Boolean.TRUE.equals(enabled);
    }
}
