package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Banner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BannerRepository extends JpaRepository<Banner, UUID> {

    /**
     * 前台可见轮播：状态启用且落在投放时间窗内（窗口字段为空视为不限）
     */
    @Query("""
            SELECT b FROM Banner b
            WHERE b.status = :status
              AND (b.startTime IS NULL OR b.startTime <= :now)
              AND (b.endTime IS NULL OR b.endTime >= :now)
            ORDER BY b.sortOrder DESC, b.createdAt DESC
            """)
    List<Banner> findDisplayable(@Param("status") String status, @Param("now") LocalDateTime now);

    Page<Banner> findByTitleContaining(String title, Pageable pageable);

    Optional<Banner> findByTitle(String title);

    /** 后台列表：状态「空即不限」，关键词命中标题或描述 */
    @Query("""
            SELECT b FROM Banner b
            WHERE (:status = '' OR b.status = :status)
              AND (:keyword = '' OR LOWER(b.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(b.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            ORDER BY b.sortOrder DESC, b.createdAt DESC
            """)
    Page<Banner> searchAdmin(@Param("status") String status, @Param("keyword") String keyword, Pageable pageable);

    /** 当前排在最前的 sortOrder，新增记录取 max+10 才会顶到第一位，而不是插在最后 */
    @Query("SELECT COALESCE(MAX(b.sortOrder), 0) FROM Banner b")
    int maxSortOrder();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Banner b SET b.status = :status, b.updatedAt = CURRENT_TIMESTAMP WHERE b.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    long countByStatus(String status);

    @Query("SELECT COUNT(b) FROM Banner b WHERE b.status = :status "
            + "AND (b.startTime IS NULL OR b.startTime <= :now) "
            + "AND (b.endTime IS NULL OR b.endTime >= :now)")
    long countDisplayable(@Param("status") String status, @Param("now") LocalDateTime now);
}
