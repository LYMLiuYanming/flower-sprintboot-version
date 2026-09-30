package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 我的花田：一块地一种花，浇水与当日天气累积成长值，成熟后兑换优惠券或带寄语转赠好友。
 *
 * <p>同时可培育的地块数由会员等级决定（见 {@link org.liuym.flowerv1springboot.common.GardenPolicy#slotsFor}），
 * 「同一块地只能有一株在培育」由 V23 的部分唯一索引（status = 'growing' 且按 slot_no 区分）在数据库层保证。
 */
@Data
@Entity
@Table(name = "flower_plot", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class FlowerPlot {

    public static final String STATUS_GROWING = "growing";
    public static final String STATUS_MATURE = "mature";
    public static final String STATUS_REDEEMED = "redeemed";
    public static final String STATUS_GIFTED = "gifted";

    /** 转赠确认态：送出后先到 pending，收礼方确认才真正发券（I13） */
    public static final String GIFT_NONE = "none";
    public static final String GIFT_PENDING = "pending";
    public static final String GIFT_ACCEPTED = "accepted";
    public static final String GIFT_DECLINED = "declined";
    public static final String GIFT_CANCELED = "canceled";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    /** 地块序号，从 1 开始；第二块地只对会员开放 */
    @Column(name = "slot_no", nullable = false)
    private Integer slotNo = 1;

    @Column(name = "seed_code", nullable = false, length = 30)
    private String seedCode;

    /** 培育所依托的直采产地，地图与花田由此连成一条线 */
    @Column(name = "origin_id", columnDefinition = "uuid")
    private UUID originId;

    @Column(nullable = false)
    private Integer growth = 0;

    @Column(nullable = false, length = 16)
    private String stage = "seed";

    @Column(nullable = false, length = 16)
    private String status = STATUS_GROWING;

    @Column(name = "water_times", nullable = false)
    private Integer waterTimes = 0;

    /** 当天已浇次数，跨天由 waterTimesToday 归零（判定见 GardenPolicy） */
    @Column(name = "water_times_today", nullable = false)
    private Integer waterTimesToday = 0;

    /** 最近一次浇水的本地日期，同时是连续天数与闲置回落的锚点 */
    @Column(name = "watered_on")
    private LocalDate wateredOn;

    @Column(name = "streak_days", nullable = false)
    private Integer streakDays = 0;

    @Column(name = "mature_on")
    private LocalDateTime matureOn;

    @Column(name = "redeemed_coupon_id", columnDefinition = "uuid")
    private UUID redeemedCouponId;

    /** 转赠对象：受益人拿到的奖励券记在它名下，这里只留这段关系 */
    @Column(name = "gift_user_id", columnDefinition = "uuid")
    private UUID giftUserId;

    /** 转赠确认态：pending 时券还没发出去，收礼方确认或婉拒才终结 */
    @Column(name = "gift_state", length = 16)
    private String giftState;

    @Column(name = "gift_message", length = 200)
    private String giftMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
