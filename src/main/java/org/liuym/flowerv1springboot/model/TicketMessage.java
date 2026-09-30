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
 * 工单往返消息（U16）：顾客侧与后台侧共用同一条时间线。
 *
 * <p>{@code authorType=system} 的行由状态机写入（受理、升级、解决、重开），
 * 时间线因此不需要在页面里二次拼「谁在什么时候做了什么」，两套口径不会分叉。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ticket_message", schema = "public")
public class TicketMessage {

    public static final String AUTHOR_CUSTOMER = "customer";
    public static final String AUTHOR_AGENT = "agent";
    public static final String AUTHOR_SYSTEM = "system";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "ticket_id", nullable = false, columnDefinition = "uuid")
    private UUID ticketId;

    @Column(name = "author_type", nullable = false, length = 20)
    private String authorType = AUTHOR_CUSTOMER;

    @Column(name = "author_id", columnDefinition = "uuid")
    private UUID authorId;

    /** 作者名快照：账号注销或改名后时间线仍然可读，与 Review.replyByName 同口径 */
    @Column(name = "author_name", length = 60)
    private String authorName;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    /** 客服内部备注：顾客侧时间线不返回这一行，用于门店与花艺师之间的协调记录 */
    @Column(name = "internal_note", nullable = false)
    private Boolean internalNote = false;

    /** 顾客还没看到的客服/系统回复：工单列表的红点据此来 */
    @Column(name = "unread_for_customer", nullable = false)
    private Boolean unreadForCustomer = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isFromCustomer() {
        return AUTHOR_CUSTOMER.equals(authorType);
    }

    /** 只有客服与系统的回复才算「等顾客看」的内部备注不占红点 */
    public boolean isReplyToCustomer() {
        return !AUTHOR_CUSTOMER.equals(authorType) && !Boolean.TRUE.equals(internalNote);
    }
}
