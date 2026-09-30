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
 * 站内信（U01/U06）：一行 = 一次真实触达。
 *
 * <p>刻意不建 User 的懒加载关联：触达大量走条件 UPDATE，{@code clearAutomatically} 之后
 * 访问懒加载属性会直接抛 LazyInitializationException，扁平外键反而让列表查询只打一条 SQL。
 *
 * <p>{@code dedupKey} 是「重复请求不双发」的最后防线：事件可能因重试或并发被投递两次，
 * 落库时由唯一索引拦下第二条，而不是靠应用层「先查再插」（并发下两条都查不到对方）。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "user_message", schema = "public")
public class UserMessage {

    public static final String CATEGORY_TRADE = "trade";
    public static final String CATEGORY_MARKETING = "marketing";
    public static final String CATEGORY_SYSTEM = "system";
    public static final String CATEGORY_TICKET = "ticket";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "category", nullable = false, length = 20)
    private String category = CATEGORY_SYSTEM;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 站内跳转路径：入库前由 MessageTargetPolicy 校验（U05），越权的不但落不了库还要计数告警 */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    /** 业务类型码：order_paid / order_shipped / coupon_expiring ... 与模板 code 同源 */
    @Column(name = "biz_type", length = 40)
    private String bizType;

    @Column(name = "biz_id", length = 64)
    private String bizId;

    @Column(name = "template_code", length = 40)
    private String templateCode;

    @Column(name = "dedup_key", length = 140)
    private String dedupKey;

    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isUnread() {
        return !Boolean.TRUE.equals(isRead);
    }
}
