package org.liuym.flowerv1springboot.model;

import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 后台操作审计留痕：只追加不修改，故无 updatedAt 与外键（账号删除后日志仍要可查）
 */
@Data
@Entity
@Table(name = "admin_audit_log", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class AdminAuditLog {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "operator_id", columnDefinition = "uuid")
    private UUID operatorId;

    @Column(name = "operator_name", length = 50)
    private String operatorName;

    @Column(name = "module", nullable = false, length = 30)
    private String module;

    @Column(name = "action", nullable = false, length = 30)
    private String action;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "uri", nullable = false, length = 300)
    private String uri;

    @Column(name = "detail", length = 2000)
    private String detail;

    @Column(name = "result_code")
    private Integer resultCode;

    @Column(name = "result_msg", length = 200)
    private String resultMsg;

    @Column(name = "ip", length = 64)
    private String ip;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
