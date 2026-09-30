package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 花田成长流水（I14）：每次开坑、浇水、天气加成、闲置回落与终结动作都留一行，成长曲线因此是历史事实而不是前端缓存。
 *
 * <p>只写不改：花田的当前态在 {@code flower_plot}，这张表承担「为什么涨/跌」的解释职责，
 * 所以把天气与加成拆解一起快照下来，事后回看才知道那天多长的 8 点里有 2 点来自雨天。
 */
@Data
@Entity
@Table(name = "plot_growth_log", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class PlotGrowthLog {

    public static final String SOURCE_PLANT = "plant";
    public static final String SOURCE_WATER = "water";
    public static final String SOURCE_DECAY = "decay";
    public static final String SOURCE_MATURE = "mature";
    public static final String SOURCE_REDEEM = "redeem";
    public static final String SOURCE_GIFT = "gift";

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "plot_id", nullable = false, columnDefinition = "uuid")
    private UUID plotId;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "slot_no", nullable = false)
    private Integer slotNo = 1;

    /** 本次变化后的成长值（已回落口径），曲线纵轴用它 */
    @Column(nullable = false)
    private Integer growth = 0;

    @Column(nullable = false, length = 16)
    private String stage = "seed";

    @Column(nullable = false)
    private Integer delta = 0;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(length = 30)
    private String weather;

    @Column(name = "temperature")
    private Integer temperature;

    @Column(name = "sun_bonus")
    private Integer sunBonus;

    @Column(name = "water_bonus")
    private Integer waterBonus;

    @Column
    private Integer stress;

    @Column(name = "logged_at", nullable = false)
    private LocalDateTime loggedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
