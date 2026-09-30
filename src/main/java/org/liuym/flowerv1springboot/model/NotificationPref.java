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
 * 订阅偏好（U08）：类别 × 渠道的一行开关。
 *
 * <p>只有 inbox（站内信）允许用户自己打开；sms/email 行永远保持关闭，
 * 服务端对这两个渠道不做任何外呼——项目已明确「真短信/邮件通道」不在范围内。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "notification_pref", schema = "public")
public class NotificationPref {

    public static final String CHANNEL_INBOX = "inbox";
    /** 展示用但不可开启：页面按「未开通」渲染，任何写 true 的请求都被服务端拒绝 */
    public static final String CHANNEL_SMS = "sms";
    public static final String CHANNEL_EMAIL = "email";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "category", nullable = false, length = 20)
    private String category;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel = CHANNEL_INBOX;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 短信/邮件属于「显示但未开通」的渠道，服务端不接受开启请求 */
    public boolean isChannelAvailable() {
        return CHANNEL_INBOX.equals(channel);
    }
}
