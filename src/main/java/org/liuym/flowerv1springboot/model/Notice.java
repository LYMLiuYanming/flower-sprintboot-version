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
@Table(name = "notice", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Notice {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "notice_type", length = 20)
    private String noticeType = "general";

    @Column(name = "is_top", nullable = false)
    private Boolean isTop = false;

    /** 浏览次数：含匿名访客，每次打开详情页 +1 */
    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    /** 已读人数：登录用户去重，来源 notice_read */
    @Column(name = "read_user_count", nullable = false)
    private Integer readUserCount = 0;

    /** 已读次数：同一人重复阅读会累加 */
    @Column(name = "read_times", nullable = false)
    private Integer readTimes = 0;

    /** F11 定时上线：为空表示立即上线 */
    @Column(name = "publish_at")
    private LocalDateTime publishAt;

    /** F11 定时下线：为空表示长期有效 */
    @Column(name = "offline_at")
    private LocalDateTime offlineAt;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    public static final String TYPE_GENERAL = "general";
    public static final String TYPE_ACTIVITY = "activity";
    public static final String TYPE_SYSTEM = "system";

    /** 展示态：不是持久化列，由状态 + 时间窗推导，后台列表用它说明「为什么前台看不到」 */
    public static final String STATE_SCHEDULED = "scheduled";
    public static final String STATE_LIVE = "live";
    public static final String STATE_EXPIRED = "expired";
    public static final String STATE_DISABLED = "disabled";

    /**
     * 是否在给定时刻前台可见：启用 + 已到上线时间 + 未过下线时间
     */
    public boolean displayableAt(LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return false;
        }
        if (publishAt != null && publishAt.isAfter(now)) {
            return false;
        }
        return offlineAt == null || !offlineAt.isBefore(now);
    }

    public String displayState(LocalDateTime now) {
        if (!STATUS_ACTIVE.equals(status)) {
            return STATE_DISABLED;
        }
        if (publishAt != null && publishAt.isAfter(now)) {
            return STATE_SCHEDULED;
        }
        if (offlineAt != null && offlineAt.isBefore(now)) {
            return STATE_EXPIRED;
        }
        return STATE_LIVE;
    }
}
