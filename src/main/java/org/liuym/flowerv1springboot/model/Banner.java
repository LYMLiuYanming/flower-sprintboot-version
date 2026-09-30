package org.liuym.flowerv1springboot.model;

import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "banner", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Banner {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imageUrl;

    /**
     * 兼容旧页面与首页的跳转地址：保存时按 linkType/linkTarget 解析回填，
     * 运营也可以直接填一个站内路径（linkType = page）
     */
    @Column(name = "link_url", length = 500)
    private String linkUrl;

    /** F13 跳转类型：none 不跳转 / product 商品 / category 分类 / url 外链 / page 站内页面 */
    @Column(name = "link_type", nullable = false, length = 20)
    private String linkType = LINK_NONE;

    /** 商品或分类的 UUID 字符串；url/page 类型时为空，直接看 linkUrl */
    @Column(name = "link_target", length = 100)
    private String linkTarget;

    /** F14 预览缩略图：留空时后台按 imageUrl 等比裁切显示 */
    @Column(name = "thumb_url", length = 500)
    private String thumbUrl;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

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

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    public static final String LINK_NONE = "none";
    public static final String LINK_PRODUCT = "product";
    public static final String LINK_CATEGORY = "category";
    public static final String LINK_URL = "url";
    public static final String LINK_PAGE = "page";

    /** 展示态：轮播位一次只放时间窗内的启用记录，后台用它解释未生效原因 */
    public static final String STATE_SCHEDULED = "scheduled";
    public static final String STATE_LIVE = "live";
    public static final String STATE_EXPIRED = "expired";
    public static final String STATE_DISABLED = "disabled";

    public String displayState(LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return STATE_DISABLED;
        }
        if (startTime != null && startTime.isAfter(now)) {
            return STATE_SCHEDULED;
        }
        if (endTime != null && endTime.isBefore(now)) {
            return STATE_EXPIRED;
        }
        return STATE_LIVE;
    }
}
