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
 * 后台群发台账（U23）：一次群发一行，发送结果与各类跳过原因分开计数。
 *
 * <p>「失败原因统计」必须是列而不是日志：运营要能回答「这次为什么少发了 3200 人」，
 * 而退订、免打扰、每日上限三种跳过的处理动作完全不同（前两种不该重发，最后一种次日可补）。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "broadcast_campaign", schema = "public")
public class BroadcastCampaign {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "segment_code", nullable = false, length = 40)
    private String segmentCode;

    @Column(name = "segment_name", length = 60)
    private String segmentName;

    @Column(name = "category", nullable = false, length = 20)
    private String category = UserMessage.CATEGORY_MARKETING;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    @Column(name = "target_count", nullable = false)
    private Integer targetCount = 0;

    @Column(name = "sent_count", nullable = false)
    private Integer sentCount = 0;

    /** 退订/偏好关闭而跳过 */
    @Column(name = "skipped_pref", nullable = false)
    private Integer skippedPref = 0;

    /** 免打扰时段而顺延（已改期为窗口结束后投递） */
    @Column(name = "skipped_dnd", nullable = false)
    private Integer skippedDnd = 0;

    /** 当日营销上限已满而跳过 */
    @Column(name = "skipped_cap", nullable = false)
    private Integer skippedCap = 0;

    /** 周末营销停发被拦下（U09）：与「退订」「上限」分开计，运营才知道该不该下周补发 */
    @Column(name = "skipped_weekend", nullable = false)
    private Integer skippedWeekend = 0;

    @Column(name = "failed_count", nullable = false)
    private Integer failedCount = 0;

    @Column(name = "error_note", length = 300)
    private String errorNote;

    @Column(name = "created_by", length = 60)
    private String createdBy;

    @Column(name = "created_by_id", columnDefinition = "uuid")
    private UUID createdById;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 被策略拦下但消息仍然发出去的顺延数：算成功率时这些要计入已触达 */
    public int reachedCount() {
        return (sentCount == null ? 0 : sentCount) + (skippedDnd == null ? 0 : skippedDnd);
    }
}
