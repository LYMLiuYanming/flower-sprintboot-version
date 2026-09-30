package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单商品评价：仅已完成订单可评价，落库后聚合回写 Product.rating / reviewCount
 */
@Data
@Entity
@Table(name = "review", schema = "public",
        indexes = {@Index(name = "idx_review_product_created", columnList = "product_id,created_at"),
                @Index(name = "idx_review_order", columnList = "order_id")})
@EntityListeners(AuditingEntityListener.class)
public class Review {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "images", columnDefinition = "TEXT")
    private String images;

    @Column(name = "visible", nullable = false)
    private Boolean visible = true;

    /** F02 匿名发布：为 true 时任何展示位都不输出昵称与头像首字 */
    @Column(name = "is_anonymous", nullable = false)
    private Boolean anonymous = false;

    /** F05 评价标签编码，逗号分隔，取值必须来自 ReviewService#TAGS */
    @Column(name = "tags", length = 200)
    private String tags;

    /** F03 买家追评：一条评价只允许追加一次，形成「初评 → 追评 → 商家回复」时间线 */
    @Column(name = "append_content", columnDefinition = "TEXT")
    private String appendContent;

    @Column(name = "append_images", columnDefinition = "TEXT")
    private String appendImages;

    @Column(name = "append_at")
    private LocalDateTime appendAt;

    /** F04 商家回复：只保留最新一条，回复人写入名称以便账号删除后仍可展示 */
    @Column(name = "reply", columnDefinition = "TEXT")
    private String reply;

    @Column(name = "reply_at")
    private LocalDateTime replyAt;

    @Column(name = "reply_by", columnDefinition = "uuid")
    private UUID replyBy;

    @Column(name = "reply_by_name", length = 50)
    private String replyByName;

    /** F08 后台隐藏/删除时填写的理由，与 admin_audit_log 互为佐证 */
    @Column(name = "hidden_reason", length = 200)
    private String hiddenReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
