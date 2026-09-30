package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    @Query("SELECT r FROM Review r JOIN FETCH r.user p JOIN FETCH r.product prod WHERE r.product.id = :productId "
            + "AND r.visible = true ORDER BY r.createdAt DESC")
    Page<Review> findVisibleByProductId(@Param("productId") UUID productId, Pageable pageable);

    @Query("SELECT r FROM Review r JOIN FETCH r.product prod JOIN FETCH r.user u WHERE r.order.id = :orderId ORDER BY r.createdAt")
    List<Review> findByOrderId(@Param("orderId") UUID orderId);

    @Query("SELECT r FROM Review r JOIN FETCH r.product prod WHERE r.user.id = :userId ORDER BY r.createdAt DESC")
    List<Review> findByUserId(@Param("userId") UUID userId);

    boolean existsByOrderItemIdAndProductId(UUID orderItemId, UUID productId);

    @Query("SELECT r.orderItem.id FROM Review r WHERE r.order.id IN :orderIds")
    List<UUID> findReviewedItemIds(@Param("orderIds") java.util.Collection<UUID> orderIds);

    @Query("SELECT COALESCE(AVG(r.rating), 5), COUNT(r.id) FROM Review r WHERE r.product.id = :productId AND r.visible = true")
    List<Object[]> ratingSummary(@Param("productId") UUID productId);

    /**
     * F06 商品评价筛选：星级下限 / 只看有图 / 标签（tagPattern 形如 %,fresh,%，空串表示不限）。
     * categoryId 固定传 NULL，与晒单广场共用同一段 SQL，避免两处筛选口径漂移。
     */
    @Query("""
            SELECT r FROM Review r
            JOIN FETCH r.user u
            JOIN FETCH r.product p
            LEFT JOIN FETCH p.category c
            WHERE r.visible = true
              AND (p.id = :productId OR :productId IS NULL)
              AND (c.id = :categoryId OR :categoryId IS NULL)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:maxRating IS NULL OR r.rating <= :maxRating)
              AND (:hasImage IS NULL OR :hasImage = false OR (r.images IS NOT NULL AND r.images <> ''))
              AND (:tagPattern = '' OR (r.tags IS NOT NULL AND CONCAT(',', r.tags, ',') LIKE :tagPattern))
            """)
    Page<Review> searchVisible(@Param("productId") UUID productId,
                               @Param("categoryId") UUID categoryId,
                               @Param("minRating") Integer minRating,
                               @Param("maxRating") Integer maxRating,
                               @Param("hasImage") Boolean hasImage,
                               @Param("tagPattern") String tagPattern,
                               Pageable pageable);

    /** 我的评价（含被后台隐藏的，用户要能看到自己写过什么以及为何不展示） */
    @Query("""
            SELECT r FROM Review r
            JOIN FETCH r.user u
            JOIN FETCH r.product p
            LEFT JOIN FETCH p.category c
            WHERE r.user.id = :userId
            """)
    Page<Review> searchByUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * 后台审核列表：可见性、商品、星级都可筛，keyword 命中评价正文或商品名。
     * keyword 传空串而不是 null（PostgreSQL 无法为 null 参数推断 LIKE 的类型）。
     */
    @Query("""
            SELECT r FROM Review r
            JOIN FETCH r.user u
            JOIN FETCH r.product p
            LEFT JOIN FETCH p.category c
            WHERE (:productId IS NULL OR p.id = :productId)
              AND (:visible IS NULL OR r.visible = :visible)
              AND (:minRating IS NULL OR r.rating >= :minRating)
              AND (:keyword = '' OR LOWER(COALESCE(r.content, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(r.reply, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Review> searchAdmin(@Param("productId") UUID productId,
                             @Param("visible") Boolean visible,
                             @Param("minRating") Integer minRating,
                             @Param("keyword") String keyword,
                             Pageable pageable);

    /** 后台概览：待处理（可见且未回复）与已隐藏的数量，审核台首屏用 */
    @Query("SELECT COUNT(r.id) FROM Review r WHERE r.visible = true AND (r.reply IS NULL OR r.reply = '')")
    long countUnreplied();

    @Query("SELECT COUNT(r.id) FROM Review r WHERE r.visible = false")
    long countHidden();

    /** 商品评分聚合（F09）：只统计前台可见的评价 */
    @Query("SELECT r.rating, COUNT(r.id) FROM Review r WHERE r.product.id = :productId AND r.visible = true GROUP BY r.rating")
    List<Object[]> starDistribution(@Param("productId") UUID productId);

    /**
     * F05 标签占比：tags 是逗号串，交给 PostgreSQL 的 string_to_array + unnest 在库内拆开统计，
     * 不把整表评价拉回 JVM
     */
    @Query(value = """
            SELECT t.tag, COUNT(*) AS hits
            FROM review r, UNNEST(STRING_TO_ARRAY(r.tags, ',')) AS t(tag)
            WHERE r.visible AND r.tags IS NOT NULL AND r.tags <> '' AND r.product_id = CAST(:productId AS uuid)
            GROUP BY t.tag
            """, nativeQuery = true)
    List<Object[]> tagDistribution(@Param("productId") UUID productId);

    /** 带图评价数与追评数：摘要卡用 */
    @Query("""
            SELECT COUNT(CASE WHEN r.images IS NOT NULL AND r.images <> '' THEN 1 END),
                   COUNT(CASE WHEN r.appendContent IS NOT NULL THEN 1 END),
                   COUNT(r.id)
            FROM Review r WHERE r.product.id = :productId AND r.visible = true
            """)
    List<Object[]> mediaSummary(@Param("productId") UUID productId);

    /** F07 防刷：用户在给定时间之后提交过多少条评价（窗口内超阈值即拒绝） */
    @Query("SELECT COUNT(r.id) FROM Review r WHERE r.user.id = :userId AND r.createdAt >= :since")
    long countSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * F09 修复口径：product.review_count 与真实可见评价数不一致的商品。
     * 后台「重算评分」按钮先扫出这批商品，再逐个回写，不做无意义的全表 UPDATE。
     */
    @Query(value = """
            SELECT p.id FROM product p
            LEFT JOIN (SELECT product_id, COUNT(*) AS c FROM review WHERE visible GROUP BY product_id) rv
                   ON rv.product_id = p.id
            WHERE COALESCE(p.review_count, 0) <> COALESCE(rv.c, 0)
            LIMIT 200
            """, nativeQuery = true)
    List<UUID> productsWithStaleCount();
}
