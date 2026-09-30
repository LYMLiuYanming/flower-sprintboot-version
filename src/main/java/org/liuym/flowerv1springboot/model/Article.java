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
 * 花语/养护知识库文章（F15）：一篇文章 = 正文 + 分类 + 关联花材 + 关联商品。
 * materials 存花材关键词（逗号分隔），详情页侧栏按 product.material 分词命中做自动推荐（F16）。
 */
@Data
@Entity
@Table(name = "article", schema = "public",
        indexes = {@Index(name = "idx_article_list", columnList = "status,category,publish_at"),
                @Index(name = "idx_article_top_sort", columnList = "status,is_top,sort_order")})
@EntityListeners(AuditingEntityListener.class)
public class Article {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "summary", length = 300)
    private String summary;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    /** 正文：后台富文本，入库前过 HtmlSanitizer 白名单 */
    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "category", nullable = false, length = 30)
    private String category = CATEGORY_CARE;

    @Column(name = "tags", length = 200)
    private String tags;

    /** 关联花材关键词，逗号分隔（玫瑰 / 康乃馨 / 百合…），F16 推荐的主要命中项 */
    @Column(name = "materials", length = 300)
    private String materials;

    /** 关联商品 UUID，逗号分隔；文章页据此带出可下单的花礼 */
    @Column(name = "related_product_ids", length = 600)
    private String relatedProductIds;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_DRAFT;

    /** 定时发布：为空表示发布后立即可见 */
    @Column(name = "publish_at")
    private LocalDateTime publishAt;

    @Column(name = "offline_at")
    private LocalDateTime offlineAt;

    @Column(name = "view_count", nullable = false)
    private Integer viewCount = 0;

    @Column(name = "is_top", nullable = false)
    private Boolean isTop = false;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "author_name", length = 50)
    private String authorName;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_OFFLINE = "offline";

    public static final String CATEGORY_CARE = "care";
    public static final String CATEGORY_LANGUAGE = "language";
    public static final String CATEGORY_STORY = "story";
    public static final String CATEGORY_GUIDE = "guide";
    public static final String CATEGORY_FESTIVAL = "festival";

    public boolean displayableAt(LocalDateTime now) {
        if (!STATUS_PUBLISHED.equals(status)) {
            return false;
        }
        if (publishAt != null && publishAt.isAfter(now)) {
            return false;
        }
        return offlineAt == null || !offlineAt.isBefore(now);
    }
}
