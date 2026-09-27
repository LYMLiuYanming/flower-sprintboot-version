package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    Page<Product> findByIsActiveTrue(Pageable pageable);

    Page<Product> findByCategoryIdAndIsActiveTrue(UUID categoryId, Pageable pageable);

    List<Product> findByIsFeaturedTrueAndIsActiveTrue(Pageable pageable);

    List<Product> findByIsNewTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByNameContainingAndIsActiveTrue(String name, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.price BETWEEN :minPrice AND :maxPrice")
    Page<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.category.id = :categoryId ORDER BY p.salesCount DESC")
    List<Product> findBestSellers(@Param("categoryId") UUID categoryId, Pageable pageable);

    Optional<Product> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * 关键词检索：名称/描述/标签任一命中即返回，小写比对以兼容中文与大小写混排
     */
    @Query("""
            SELECT p FROM Product p WHERE p.isActive = true
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.tags, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Product> searchActive(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 输入联想用：命中的在售商品名（按销量优先）与标签
     */
    @Query("""
            SELECT p.name FROM Product p
            WHERE p.isActive = true AND LOWER(p.name) LIKE LOWER(CONCAT('%', :term, '%'))
            ORDER BY p.salesCount DESC
            """)
    List<String> findNamesMatching(@Param("term") String term, Pageable pageable);

    @Query("""
            SELECT p.tags FROM Product p
            WHERE p.isActive = true AND p.tags IS NOT NULL
              AND LOWER(p.tags) LIKE LOWER(CONCAT('%', :term, '%'))
            """)
    List<String> findTagsMatching(@Param("term") String term);

    /**
     * 后台列表：分类/状态/关键词组合筛选
     * keyword 用空串而非 NULL 表示"不过滤"：PostgreSQL 无法为 null 参数推断类型，
     * 出现 LOWER(CONCAT('%', :keyword, '%')) 时会报 lower(bytea) 不存在
     */
    @Query("""
            SELECT p FROM Product p
            WHERE (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:isActive IS NULL OR p.isActive = :isActive)
              AND (:keyword = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Product> searchAdmin(@Param("categoryId") UUID categoryId,
                              @Param("isActive") Boolean isActive,
                              @Param("keyword") String keyword,
                              Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock - :quantity WHERE p.id = :id AND p.stock >= :quantity")
    int reduceStock(@Param("id") UUID id, @Param("quantity") Integer quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = p.stock + :quantity WHERE p.id = :id")
    int increaseStock(@Param("id") UUID id, @Param("quantity") Integer quantity);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.salesCount = p.salesCount + :count WHERE p.id = :id")
    int increaseSalesCount(@Param("id") UUID id, @Param("count") Integer count);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.salesCount = CASE WHEN p.salesCount >= :count THEN p.salesCount - :count ELSE 0 END WHERE p.id = :id")
    int decreaseSalesCount(@Param("id") UUID id, @Param("count") Integer count);

    /**
     * 评价聚合：评分与评价数由 review 表实时汇总后回写，避免前端展示静态种子值
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.rating = :rating, p.reviewCount = :reviewCount, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    int updateRatingStats(@Param("id") UUID id,
                          @Param("rating") BigDecimal rating,
                          @Param("reviewCount") Long reviewCount);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.isActive = :isActive, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id IN :ids")
    int updateActiveStatusBatch(@Param("ids") List<UUID> ids, @Param("isActive") Boolean isActive);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.isActive = :isActive, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    int updateActiveStatus(@Param("id") UUID id, @Param("isActive") Boolean isActive);

    long countByCategoryId(UUID categoryId);

    long countByIsActiveTrue();

    long countByCategoryIdIn(List<UUID> categoryIds);
}