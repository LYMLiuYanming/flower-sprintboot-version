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

    /** 产地卡片上的「在售 N 款」计数 */
    long countByOriginIdAndIsActiveTrue(UUID originId);

    Page<Product> findByCategoryIdInAndIsActiveTrue(List<UUID> categoryIds, Pageable pageable);

    List<Product> findByIsFeaturedTrueAndIsActiveTrue(Pageable pageable);

    List<Product> findByIsNewTrueAndIsActiveTrue(Pageable pageable);

    Page<Product> findByNameContainingAndIsActiveTrue(String name, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.price BETWEEN :minPrice AND :maxPrice")
    Page<Product> findByPriceRange(@Param("minPrice") BigDecimal minPrice,
                                   @Param("maxPrice") BigDecimal maxPrice,
                                   Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.isActive = true AND p.category.id = :categoryId ORDER BY p.salesCount DESC")
    List<Product> findBestSellers(@Param("categoryId") UUID categoryId, Pageable pageable);

    /**
     * 关键词加权检索：商品名命中最重，花材/标签次之，花语/描述/场景再次，最后用销量做同分裁决。
     * 之前只按「任一字段 LIKE 命中」返回并按销量排，导致搜「玫瑰」时描述里提过玫瑰的郁金香排在前，
     * 这里把命中位置的分值直接写进 ORDER BY，前端不再需要二次排序
     */
    @Query("""
            SELECT p FROM Product p
            WHERE p.isActive = true
              AND (LOWER(p.name) LIKE :like OR LOWER(COALESCE(p.material, '')) LIKE :like
                OR LOWER(COALESCE(p.tags, '')) LIKE :like OR LOWER(COALESCE(p.description, '')) LIKE :like
                OR LOWER(COALESCE(p.flowerLanguage, '')) LIKE :like OR LOWER(COALESCE(p.suitableFor, '')) LIKE :like)
            ORDER BY (CASE WHEN LOWER(p.name) LIKE :like THEN 40 ELSE 0 END
                    + CASE WHEN LOWER(COALESCE(p.material, '')) LIKE :like THEN 16 ELSE 0 END
                    + CASE WHEN LOWER(COALESCE(p.tags, '')) LIKE :like THEN 12 ELSE 0 END
                    + CASE WHEN LOWER(COALESCE(p.suitableFor, '')) LIKE :like THEN 8 ELSE 0 END
                    + CASE WHEN LOWER(COALESCE(p.flowerLanguage, '')) LIKE :like THEN 6 ELSE 0 END
                    + CASE WHEN LOWER(COALESCE(p.description, '')) LIKE :like THEN 4 ELSE 0 END
                    + COALESCE(p.salesCount, 0) / 50) DESC,
                   p.salesCount DESC, p.createdAt DESC
            """)
    Page<Product> searchRanked(@Param("like") String likeLower, Pageable pageable);

    /** 同分类价格带近似款：详情页「看了又看」，价格 ±pct 之内 */
    @Query("""
            SELECT p FROM Product p
            WHERE p.isActive = true AND p.id <> :id AND p.category.id = :categoryId
              AND p.price BETWEEN :low AND :high
            ORDER BY ABS(p.price - :price) ASC, p.salesCount DESC
            """)
    List<Product> findSimilarInCategory(@Param("id") UUID id, @Param("categoryId") UUID categoryId,
                                        @Param("price") BigDecimal price, @Param("low") BigDecimal low,
                                        @Param("high") BigDecimal high, Pageable pageable);

    Optional<Product> findByCode(String code);

    boolean existsByCode(String code);

    /** 系统生成编码用：当日已建档数，决定流水号起点 */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.code LIKE CONCAT(:prefix, '%')")
    long countByCodePrefix(@Param("prefix") String prefix);

    /** 分类删除守卫用：一次把所有分类的挂载商品数取回，避免后台列表逐行 count 打成 N+1 */
    @Query("SELECT p.category.id, COUNT(p) FROM Product p WHERE p.category.id IS NOT NULL GROUP BY p.category.id")
    List<Object[]> countGroupedByCategory();

    /** 前台分类瓦片上的「在售 N 款」：同样一次取回，不做 N+1 */
    @Query("SELECT p.category.id, COUNT(p) FROM Product p WHERE p.isActive = true AND p.category.id IS NOT NULL GROUP BY p.category.id")
    List<Object[]> countActiveGroupedByCategory();

    /** 上架且有货的商品数：分类停用前提示「停用后这些款仍可售」 */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.category.id = :categoryId AND p.isActive = true")
    long countActiveByCategory(@Param("categoryId") UUID categoryId);

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
     * 后台组合筛选（G04）：分类 / 上下架状态 / 关键词三条件可任意组合，空值即不过滤。
     * 关键词口径与前台 /api/products/browse 对齐——除名称与编码外，花材/标签/描述同样命中，
     * 否则运营按「玫瑰」搜不到只把玫瑰写在花材里的款。
     * keyword 用空串而非 NULL 表示"不过滤"：PostgreSQL 无法为 null 参数推断类型，
     * 出现 LOWER(CONCAT('%', :keyword, '%')) 时会报 lower(bytea) 不存在
     */
    @Query("""
            SELECT p FROM Product p
            WHERE (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:isActive IS NULL OR p.isActive = :isActive)
              AND (:lowStockOnly IS NULL OR (:lowStockOnly = TRUE AND p.stock <= 5))
              AND (:keyword = '' OR LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.code, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.material, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.tags, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(p.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Product> searchAdmin(@Param("categoryId") UUID categoryId,
                              @Param("isActive") Boolean isActive,
                              @Param("lowStockOnly") Boolean lowStockOnly,
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
    @Query("UPDATE Product p SET p.isActive = :isActive, p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id")
    int updateActiveStatus(@Param("id") UUID id, @Param("isActive") Boolean isActive);

    /**
     * 批量上下架（G05）：条件带上「当前状态不等于目标状态」，影响行数即为真实变更条数。
     * 不加这个条件就没法区分「已经是上架」和「这条根本没改」，逐条明细也报不准
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.isActive = :isActive, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.id = :id AND p.isActive IS DISTINCT FROM :isActive")
    int updateActiveStatusCas(@Param("id") UUID id, @Param("isActive") Boolean isActive);

    /**
     * 批量改价（G06）：WHERE 里带上读到的旧价，价格被别的会话改过就一行都更新不到，
     * 据此把「丢更新」变成一条明确的失败明细而不是静默覆盖
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.price = :price, p.originalPrice = :originalPrice, "
            + "p.updatedAt = CURRENT_TIMESTAMP WHERE p.id = :id AND p.price = :expectedPrice")
    int updatePriceIfUnchanged(@Param("id") UUID id,
                               @Param("expectedPrice") BigDecimal expectedPrice,
                               @Param("price") BigDecimal price,
                               @Param("originalPrice") BigDecimal originalPrice);

    /**
     * 批量调库存（G07）：设定值走旧值比对；减少时额外要求库存够扣，不会出现负库存
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = :targetStock, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.id = :id AND p.stock = :expectedStock")
    int updateStockIfUnchanged(@Param("id") UUID id,
                               @Param("expectedStock") Integer expectedStock,
                               @Param("targetStock") Integer targetStock);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Product p SET p.stock = :targetStock, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.id = :id AND :targetStock >= 0")
    int forceStock(@Param("id") UUID id, @Param("targetStock") Integer targetStock);

    /** 上架中且库存告急（≤5）的款数：后台列表「库存告急」筛选的计数口径 */
    @Query("SELECT COUNT(p) FROM Product p WHERE p.isActive = true AND p.stock <= 5")
    long countLowStockActive();

    long countByCategoryId(UUID categoryId);

    long countByIsActiveTrue();

    long countByCategoryIdIn(List<UUID> categoryIds);
}