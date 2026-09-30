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
 * 消息保留期清理的归档计数（U10）：物理删除前按「用户 + 月份 + 类别」聚合一行。
 *
 * <p>留计数而不是留原文：90 天后原文已经没有业务价值（订单、工单各有自己的主表可查），
 * 但运营要能回答「这个月为某用户清了多少条」，一行的成本几乎为零。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "message_archive_stat", schema = "public")
public class MessageArchiveStat {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    /** 形如 2026-07：消息创建时间所在月份，按它分桶便于回答「哪个月积压最多」 */
    @Column(name = "bucket_month", nullable = false, length = 7)
    private String bucketMonth;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "message_count", nullable = false)
    private Integer messageCount;

    @Column(name = "archived_at", nullable = false)
    private LocalDateTime archivedAt;
}
