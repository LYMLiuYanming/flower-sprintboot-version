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
 * 延时消息（U20/U21）：签收后第 2/4/7 天的养护提醒就落在这张表里。
 *
 * <p>投递前必须走 {@code pending → sending} 的条件 UPDATE 抢占；影响行数 0 说明
 * 别的线程（或重启后的同一任务）已经把这条拿走了，直接跳过，因此不会出现重复推送。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "scheduled_message", schema = "public")
public class ScheduledMessage {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_SENDING = "sending";
    public static final String STATUS_SENT = "sent";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_CANCELLED = "cancelled";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "category", nullable = false, length = 20)
    private String category = UserMessage.CATEGORY_SYSTEM;

    @Column(name = "template_code", length = 40)
    private String templateCode;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "link_url", length = 500)
    private String linkUrl;

    /** 生成时刻的变量快照（JSON 文本）：排障时能还原「当初到底传了什么」 */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "due_at", nullable = false)
    private LocalDateTime dueAt;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Integer maxAttempts = 3;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "dedup_key", length = 140)
    private String dedupKey;

    @Column(name = "biz_type", length = 40)
    private String bizType;

    @Column(name = "biz_id", length = 64)
    private String bizId;

    /** U22：false 表示没命中花材知识库、用的是通用文案，页面要标注「未定制」而不是留空 */
    @Column(name = "customised", nullable = false)
    private Boolean customised = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isRetryable() {
        return attempts != null && maxAttempts != null && attempts < maxAttempts;
    }
}
