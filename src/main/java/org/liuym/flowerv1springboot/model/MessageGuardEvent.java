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
 * 跳转地址越权留痕（U05）：外链、{@code javascript:} 这类地址拒绝入库，但必须留下可数的一行，
 * 否则后台只知道「没发出去」，不知道有多少人试过越权。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "message_guard_event", schema = "public")
public class MessageGuardEvent {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "reason", nullable = false, length = 60)
    private String reason;

    /** 原始地址只截断存一份供排障：它永远不会被回显成可点链接 */
    @Column(name = "raw_url", length = 600)
    private String rawUrl;

    @Column(name = "source", length = 40)
    private String source;

    @Column(name = "operator", length = 60)
    private String operator;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
