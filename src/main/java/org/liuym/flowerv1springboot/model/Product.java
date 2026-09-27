package org.liuym.flowerv1springboot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "product", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Product {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "code", unique = true, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "price", nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "original_price", precision = 10, scale = 2)
    private BigDecimal originalPrice;

    @Column(name = "main_image", length = 255)
    private String mainImage;

    @Column(name = "images", columnDefinition = "TEXT")
    private String images;

    @ManyToOne(fetch = FetchType.LAZY)
    @Fetch(FetchMode.JOIN)
    @JsonIgnoreProperties({"hibernateLazyInitializer", "handler"})
    @JoinColumn(name = "category_id", referencedColumnName = "id")
    private Category category;

    @Column(name = "stock", nullable = false)
    private Integer stock = 0;

    @Column(name = "sales_count", nullable = false)
    private Integer salesCount = 0;

    @Column(name = "rating", precision = 2, scale = 1)
    private BigDecimal rating = BigDecimal.valueOf(5.0);

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount = 0;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "is_featured", nullable = false)
    private Boolean isFeatured = false;

    @Column(name = "is_new", nullable = false)
    private Boolean isNew = false;

    @Column(name = "unit", length = 20)
    private String unit = "束";

    @Column(name = "weight", length = 50)
    private String weight;

    @Column(name = "material", length = 100)
    private String material;

    @Column(name = "packaging", length = 100)
    private String packaging;

    @Column(name = "tags", length = 200)
    private String tags;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}