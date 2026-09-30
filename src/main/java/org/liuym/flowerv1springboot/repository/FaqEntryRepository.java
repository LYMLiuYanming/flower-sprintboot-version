package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.FaqEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** FAQ 词条（U19） */
public interface FaqEntryRepository extends JpaRepository<FaqEntry, UUID> {

    @Query("SELECT f FROM FaqEntry f WHERE f.enabled = true ORDER BY f.sortOrder DESC, f.hitCount DESC")
    List<FaqEntry> findEnabled(Pageable pageable);

    /**
     * 后台清单：关键词命中问题、答案与关键词本身。
     * keyword 传空串（PostgreSQL 无法为 null 参数推断 LIKE 的类型，与既有 Repository 同口径）。
     */
    @Query("""
            SELECT f FROM FaqEntry f
            WHERE (:category = '' OR f.category = :category)
              AND (:enabledOnly = false OR f.enabled = true)
              AND (:keyword = '' OR LOWER(f.question) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(f.answer) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(f.keywords) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<FaqEntry> searchAdmin(@Param("category") String category,
                               @Param("keyword") String keyword,
                               @Param("enabledOnly") boolean enabledOnly,
                               Pageable pageable);

    /** 命中计数：只做累加，不做「先查再写」，并发命中同一词条时计数不会丢 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FaqEntry f SET f.hitCount = f.hitCount + 1 WHERE f.id = :id")
    int incrementHit(@Param("id") UUID id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE FaqEntry f SET f.enabled = :enabled, f.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE f.id = :id AND f.enabled IS DISTINCT FROM :enabled")
    int updateEnabledCas(@Param("id") UUID id, @Param("enabled") boolean enabled);

    /** 长期零命中的词条：后台据此判断关键词写得对不对 */
    @Query("SELECT f FROM FaqEntry f WHERE f.enabled = true AND f.hitCount = 0 ORDER BY f.createdAt DESC")
    List<FaqEntry> findNeverHit(Pageable pageable);

    long countByEnabledTrue();
}
