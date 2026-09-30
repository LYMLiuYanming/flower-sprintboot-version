package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/**
 * 消息触达档案（U09）：主键即 user_id，一人一行，存放免打扰时段与营销总开关。
 *
 * <p>免打扰窗口允许跨午夜（默认 22:00-08:00）：库里存的就是两个本地时间，
 * 「是否落在窗口内」的判定交给 {@code NotificationPolicy}，那里有跨午夜的单测。
 */
@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "notification_profile", schema = "public")
public class NotificationProfile {

    /** 主键即用户 id：一行只描述一个人的触达档案，新建时由服务层直接赋值，不走 UUID 生成器 */
    @Id
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "dnd_enabled", nullable = false)
    private Boolean dndEnabled = true;

    @Column(name = "dnd_start", nullable = false)
    private LocalTime dndStart = LocalTime.of(22, 0);

    @Column(name = "dnd_end", nullable = false)
    private LocalTime dndEnd = LocalTime.of(8, 0);

    /** 营销消息总开关：与 notification_pref 的 marketing×inbox 取「与」，任一侧关就不发 */
    @Column(name = "marketing_paused", nullable = false)
    private Boolean marketingPaused = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
