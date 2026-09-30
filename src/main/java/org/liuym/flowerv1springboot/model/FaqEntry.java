package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * FAQ 词条（U19）：本地词表关键词匹配，不引模型、不外呼。
 *
 * <p>{@code hitCount} 只统计「命中并被顾客标记有帮助」之外的原始匹配次数，
 * 后台按 hitCount 低、提问量高的方向找「该补哪条 FAQ」，比拍脑袋加条目可靠。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "faq_entry", schema = "public")
public class FaqEntry {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "question", nullable = false, length = 300)
    private String question;

    @Column(name = "answer", nullable = false, columnDefinition = "TEXT")
    private String answer;

    /** 关键词逗号串：匹配按「命中词个数」打分，命中越多排越前 */
    @Column(name = "keywords", nullable = false, length = 300)
    private String keywords;

    @Column(name = "category", nullable = false, length = 20)
    private String category = Ticket.CATEGORY_OTHER;

    @Column(name = "hit_count", nullable = false)
    private Integer hitCount = 0;

    @Column(name = "helpful_count", nullable = false)
    private Integer helpfulCount = 0;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    public List<String> keywordList() {
        if (keywords == null || keywords.isBlank()) {
            return List.of();
        }
        return Arrays.stream(keywords.split("[,，、;；]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
