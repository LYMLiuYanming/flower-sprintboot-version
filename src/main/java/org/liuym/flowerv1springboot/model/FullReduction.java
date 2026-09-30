package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 满减活动（E14）：无需领券，金额达到门槛自动减免。
 *
 * <p>与券的关系由 stackWithCoupon 决定：false 时同单只取更省的一方，true 时先满减再用券。
 */
@Data
@Entity
@Table(name = "full_reduction", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class FullReduction {

    public static final String SCOPE_ALL = "all";
    public static final String SCOPE_CATEGORY = "category";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "scope", nullable = false, length = 20)
    private String scope = SCOPE_ALL;

    /** 限品类多选，逗号分隔 category.id；scope=all 时忽略 */
    @Column(name = "category_ids", length = 500)
    private String categoryIds;

    /** 阶梯条款 "199:20,399:60"，取最优档 */
    @Column(name = "ladder_rule", nullable = false, length = 200)
    private String ladderRule;

    /** 是否允许与优惠券叠加 */
    @Column(name = "stack_with_coupon", nullable = false)
    private Boolean stackWithCoupon = false;

    /** 多个活动同时命中时的优先级，数值大者优先 */
    @Column(name = "priority", nullable = false)
    private Integer priority = 0;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 是否在给定时刻生效：满减是按单自动命中，窗口判定必须与服务端口径一致 */
    public boolean activeAt(LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return false;
        }
        if (startTime != null && startTime.isAfter(now)) {
            return false;
        }
        return endTime == null || !endTime.isBefore(now);
    }
}
