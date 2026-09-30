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
 * 促销位（E19）：首页/详情页领券条等营销入口，后台控制开关与排序。
 * 关联 coupon 时前端直接展示券面条款，不配置券则退化成纯文案跳转位。
 */
@Data
@Entity
@Table(name = "promotion_slot", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class PromotionSlot {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    /** 首页领券条 */
    public static final String POSITION_HOME = "home";
    /** 商品详情页领券条 */
    public static final String POSITION_PDP = "pdp";
    /** 领券中心顶部说明位 */
    public static final String POSITION_CENTER = "center";
    /** 购物车凑单位：提示「再买 X 元够下一档」 */
    public static final String POSITION_CART = "cart";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "position", nullable = false, length = 20)
    private String position = POSITION_HOME;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "coupon_id", columnDefinition = "uuid")
    private UUID couponId;

    @Column(name = "title", nullable = false, length = 80)
    private String title;

    @Column(name = "subtitle", length = 160)
    private String subtitle;

    @Column(name = "link_url", length = 300)
    private String linkUrl;

    @Column(name = "image_url", length = 300)
    private String imageUrl;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_ACTIVE;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean displayAt(LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return false;
        }
        if (startTime != null && startTime.isAfter(now)) {
            return false;
        }
        return endTime == null || !endTime.isBefore(now);
    }
}
