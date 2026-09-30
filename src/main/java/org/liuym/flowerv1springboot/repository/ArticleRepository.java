package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Article;
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

public interface ArticleRepository extends JpaRepository<Article, UUID> {

    /**
     * 前台列表：已发布 + 已到发布时间 + 未过下线时间；分类、关键词与花材都是「空即不限」
     */
    @Query("""
            SELECT a FROM Article a
            WHERE a.status = 'published'
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (a.offlineAt IS NULL OR a.offlineAt >= :now)
              AND (:category = '' OR a.category = :category)
              AND (:keyword = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(a.summary) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(a.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:material = '' OR LOWER(COALESCE(a.materials, '')) LIKE LOWER(CONCAT('%', :material, '%')))
            """)
    Page<Article> searchPublished(@Param("category") String category,
                                  @Param("keyword") String keyword,
                                  @Param("material") String material,
                                  @Param("now") LocalDateTime now,
                                  Pageable pageable);

    /** 后台列表：状态与分类可选，keyword 命中标题/摘要/花材 */
    @Query("""
            SELECT a FROM Article a
            WHERE (:status = '' OR a.status = :status)
              AND (:category = '' OR a.category = :category)
              AND (:keyword = '' OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                              OR LOWER(COALESCE(a.materials, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Article> searchAdmin(@Param("status") String status,
                              @Param("category") String category,
                              @Param("keyword") String keyword,
                              Pageable pageable);

    /** 分类瓦片计数：只统计前台可见的文章，避免点进去是空列表 */
    @Query("""
            SELECT a.category, COUNT(a.id) FROM Article a
            WHERE a.status = 'published'
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (a.offlineAt IS NULL OR a.offlineAt >= :now)
            GROUP BY a.category
            """)
    List<Object[]> categoryCounts(@Param("now") LocalDateTime now);

    /** F16 推荐候选：一次取出已发布的文章，命中打分在服务端按花材/标签/分类做 */
    @Query("""
            SELECT a FROM Article a
            WHERE a.status = 'published'
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (a.offlineAt IS NULL OR a.offlineAt >= :now)
            """)
    List<Article> findDisplayable(@Param("now") LocalDateTime now, Pageable pageable);

    /** 同分类或共享花材的相关文章，排除自身 */
    @Query("""
            SELECT a FROM Article a
            WHERE a.status = 'published'
              AND a.id <> :selfId
              AND (a.category = :category
                   OR LOWER(COALESCE(a.materials, '')) LIKE LOWER(CONCAT('%', :material, '%')))
            ORDER BY a.isTop DESC, a.sortOrder DESC, a.viewCount DESC
            """)
    List<Article> findRelated(@Param("selfId") UUID selfId,
                              @Param("category") String category,
                              @Param("material") String material,
                              Pageable pageable);

    /** 标题查重：知识库文章没有编号，标题就是运营辨认自己的稿子的方式 */
    Optional<Article> findByTitle(String title);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Article a SET a.viewCount = a.viewCount + 1, a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Article a SET a.status = :status, "
            + "a.publishAt = COALESCE(a.publishAt, CASE WHEN :status = 'published' THEN CURRENT_TIMESTAMP ELSE a.publishAt END), "
            + "a.updatedAt = CURRENT_TIMESTAMP WHERE a.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    long countByStatus(String status);
}
