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
 * 客服工单（U11/U13/U14/U17/U24/U26）。
 *
 * <p>状态机 {@code open → assigned → processing → resolved → closed}，允许 closed→open 重开一次；
 * 跃迁一律走带旧状态的条件 UPDATE，影响行数 0 即视为并发冲突，不重复通知。
 *
 * <p>{@code compensationType} 与 {@code satisfaction} 都是「非空即终局」的闸门列：
 * 补偿与评分各自只允许一次 CAS，双击或多标签页提交不会发出两张券、也不会覆盖首评。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ticket", schema = "public")
public class Ticket {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_ASSIGNED = "assigned";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_RESOLVED = "resolved";
    public static final String STATUS_CLOSED = "closed";

    public static final String PRIORITY_LOW = "low";
    public static final String PRIORITY_NORMAL = "normal";
    public static final String PRIORITY_HIGH = "high";
    public static final String PRIORITY_URGENT = "urgent";

    public static final String CATEGORY_QUALITY = "quality";
    public static final String CATEGORY_DELIVERY = "delivery";
    public static final String CATEGORY_REFUND = "refund";
    public static final String CATEGORY_CARD = "card";
    public static final String CATEGORY_SUBSCRIPTION = "subscription";
    public static final String CATEGORY_OTHER = "other";

    public static final String SOURCE_MANUAL = "manual";
    /** U18：差评自动开单，source_id = review.id，靠唯一索引保证同一评价只生成一张工单 */
    public static final String SOURCE_REVIEW = "review";
    public static final String SOURCE_ORDER = "order";

    /** closed→open 的重开上限：只允许一次（U13） */
    public static final int MAX_REOPEN = 1;

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "ticket_no", nullable = false, length = 24, unique = true)
    private String ticketNo;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "order_id", columnDefinition = "uuid")
    private UUID orderId;

    /** 提交时快照的联系电话：出网一律按 Masking 脱敏（U16），核身口径也不随订单改址漂移 */
    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "category", nullable = false, length = 20)
    private String category = CATEGORY_OTHER;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 附件站内路径，逗号分隔：上传复用第一轮 UploadController 端点与 L05 白名单 */
    @Column(name = "images", columnDefinition = "TEXT")
    private String images;

    @Column(name = "priority", nullable = false, length = 20)
    private String priority = PRIORITY_NORMAL;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_OPEN;

    @Column(name = "assignee_id", columnDefinition = "uuid")
    private UUID assigneeId;

    @Column(name = "assignee_name", length = 60)
    private String assigneeName;

    @Column(name = "sla_rule_id", columnDefinition = "uuid")
    private UUID slaRuleId;

    @Column(name = "first_response_due_at")
    private LocalDateTime firstResponseDueAt;

    @Column(name = "resolve_due_at")
    private LocalDateTime resolveDueAt;

    @Column(name = "first_response_at")
    private LocalDateTime firstResponseAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "reopen_count", nullable = false)
    private Integer reopenCount = 0;

    /** U14：逾期升级标记，避免定时任务每轮都再升一次优先级 */
    @Column(name = "escalated", nullable = false)
    private Boolean escalated = false;

    @Column(name = "escalated_at")
    private LocalDateTime escalatedAt;

    @Column(name = "escalate_note", length = 200)
    private String escalateNote;

    @Column(name = "solution", columnDefinition = "TEXT")
    private String solution;

    @Column(name = "compensation_type", length = 20)
    private String compensationType;

    @Column(name = "compensation_ref", length = 64)
    private String compensationRef;

    @Column(name = "compensated_at")
    private LocalDateTime compensatedAt;

    @Column(name = "compensation_note", length = 200)
    private String compensationNote;

    @Column(name = "satisfaction")
    private Integer satisfaction;

    @Column(name = "satisfaction_note", length = 300)
    private String satisfactionNote;

    @Column(name = "satisfaction_at")
    private LocalDateTime satisfactionAt;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType = SOURCE_MANUAL;

    @Column(name = "source_id", length = 64)
    private String sourceId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 未结单：SLA 计时与队列只关心这些状态 */
    public boolean isOpenFlow() {
        return STATUS_OPEN.equals(status) || STATUS_ASSIGNED.equals(status) || STATUS_PROCESSING.equals(status);
    }

    /** 可评分：只有处理完成且还没评过 */
    public boolean isRateable() {
        return STATUS_RESOLVED.equals(status) && satisfaction == null;
    }
}
