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
 * 预约时段运力：一行代表「某天某小时」可接多少单，容量按门店运力配置，占用数随下单/关单增减。
 *
 * <p>用条件 UPDATE（used &lt; capacity）占用，容量在数据库层兜底，两个客户抢最后一个名额时只有一人成功。
 */
@Data
@Entity
@Table(name = "delivery_slot_quota", schema = "public",
        uniqueConstraints = @UniqueConstraint(name = "uq_delivery_slot", columnNames = {"slot_date", "slot_hour"}))
@EntityListeners(AuditingEntityListener.class)
public class DeliverySlotQuota {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "slot_date", nullable = false)
    private LocalDate slotDate;

    /** 整点小时，取值与 ShippingPolicy 的可约时段窗口一致 */
    @Column(name = "slot_hour", nullable = false)
    private Integer slotHour;

    @Column(nullable = false)
    private Integer capacity = 0;

    @Column(nullable = false)
    private Integer used = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
