package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 地理网络节点：一张表同时表达直采产地、分拨中心与自提门店，地图按 kind 分层显示。
 *
 * <p>坐标用 GCJ-02（高德底图同坐标系），换底图需要整体纠偏。
 */
@Data
@Entity
@Table(name = "flower_origin", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class FlowerOrigin {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 60)
    private String name;

    /** origin 直采产地 / hub 分拨中心 / store 自提门店 */
    @Column(nullable = false, length = 16)
    private String kind = "origin";

    @Column(length = 40)
    private String province;

    @Column(length = 40)
    private String city;

    @Column(length = 12)
    private String adcode;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal lat;

    /** 海拔（米），产地卡片用它解释品质差异 */
    private Integer altitude;

    @Column(length = 255)
    private String flowers;

    @Column(length = 120)
    private String feature;

    @Column(length = 500)
    private String story;

    @Column(name = "season", length = 60)
    private String season;

    /** 节点配图（I01）：站内相对路径，后台可填也可留空，前台卡片留空时不占位 */
    @Column(name = "image_url", length = 255)
    private String imageUrl;

    /** 门店详细地址：自提地图与到店指引要用，产地/分拨中心可空 */
    @Column(length = 160)
    private String address;

    @Column(length = 20)
    private String phone;

    /** 营业时间文本，如「09:00-21:00」 */
    @Column(name = "open_hours", length = 60)
    private String openHours;

    /** 下单到可到店自取的备花时长（分钟），就近推荐用它算「几点能来取」 */
    @Column(name = "pickup_ready_minutes")
    private Integer pickupReadyMinutes;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
