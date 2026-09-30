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
 * SLA 规则（U14）：类型 × 优先级 → 首响与解决时限（分钟）。
 *
 * <p>时限用「分钟」而不是「小时」存：30 分钟的 urgent 首响是门店真实口径，
 * 用小时存就得写小数，判定与展示两头都要再转一次。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ticket_sla_rule", schema = "public")
public class TicketSlaRule {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "priority", nullable = false, length = 20)
    private String priority;

    @Column(name = "first_response_minutes", nullable = false)
    private Integer firstResponseMinutes;

    @Column(name = "resolve_minutes", nullable = false)
    private Integer resolveMinutes;

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
}
