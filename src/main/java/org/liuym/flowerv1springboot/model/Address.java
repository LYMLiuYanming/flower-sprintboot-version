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
 * 收货地址（结算页可直接选择，避免每次手填）
 */
@Data
@Entity
@Table(name = "address", schema = "public",
        indexes = {@Index(name = "idx_address_user", columnList = "user_id,is_default")})
@EntityListeners(AuditingEntityListener.class)
public class Address {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "receiver_name", nullable = false, length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "province", length = 50)
    private String province;

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "district", length = 50)
    private String district;

    @Column(name = "detail", nullable = false, length = 255)
    private String detail;

    @Column(name = "tag", length = 20)
    private String tag;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String fullAddress() {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{province, city, district, detail}) {
            if (part != null && !part.isBlank()) {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}
