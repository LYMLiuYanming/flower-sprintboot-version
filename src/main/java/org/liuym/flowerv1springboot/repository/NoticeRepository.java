package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Notice;
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

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    List<Notice> findByStatusOrderByIsTopDescCreatedAtDesc(String status);

    Page<Notice> findByTitleContaining(String title, Pageable pageable);

    Optional<Notice> findByTitle(String title);

    /**
     * F11 前台可见公告：状态启用 + 已到上线时间 + 未过下线时间。
     * 置顶优先，其次按实际上线时间（publishAt 缺省回退到创建时间），
     * 排序放在库内做，避免把全部公告拉进内存再排。
     */
    @Query("""
            SELECT n FROM Notice n
            WHERE n.status = :status
              AND (n.publishAt IS NULL OR n.publishAt <= :now)
              AND (n.offlineAt IS NULL OR n.offlineAt >= :now)
            ORDER BY n.isTop DESC, COALESCE(n.publishAt, n.createdAt) DESC
            """)
    List<Notice> findDisplayable(@Param("status") String status, @Param("now") LocalDateTime now);

    /** 后台列表：状态与关键词都是「空即不限」，keyword 命中标题 */
    @Query("""
            SELECT n FROM Notice n
            WHERE (:status = '' OR n.status = :status)
              AND (:keyword = '' OR LOWER(n.title) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<Notice> searchAdmin(@Param("status") String status, @Param("keyword") String keyword, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notice n SET n.status = :status, n.updatedAt = CURRENT_TIMESTAMP WHERE n.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notice n SET n.viewCount = n.viewCount + 1 WHERE n.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    /**
     * F12 已读明细落库：同一人重复阅读只累加次数（PostgreSQL 的 upsert 一步完成，
     * 先查后插在并发下会写出两行，唯一键才是判定依据）。id 由应用生成，避免依赖扩展函数。
     */
    @Modifying
    @Query(value = """
            INSERT INTO notice_read (id, notice_id, user_id, read_times, first_read_at, last_read_at)
            VALUES (CAST(:id AS uuid), CAST(:noticeId AS uuid), CAST(:userId AS uuid), 1, now(), now())
            ON CONFLICT (notice_id, user_id)
            DO UPDATE SET read_times = notice_read.read_times + 1, last_read_at = now()
            """, nativeQuery = true)
    int upsertRead(@Param("id") UUID id, @Param("noticeId") UUID noticeId, @Param("userId") UUID userId);

    /** 把 notice_read 聚合回写到大伙都查得到的两个冗余列，列表页不必逐行子查询 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE notice n
               SET read_user_count = COALESCE((SELECT COUNT(*) FROM notice_read r WHERE r.notice_id = n.id), 0),
                   read_times      = COALESCE((SELECT SUM(r.read_times) FROM notice_read r WHERE r.notice_id = n.id), 0)
             WHERE n.id = CAST(:noticeId AS uuid)
            """, nativeQuery = true)
    int refreshReadStats(@Param("noticeId") UUID noticeId);

    /** 当前用户是否已读过该公告（详情页用于区分「首次阅读」与「回看」） */
    @Query(value = """
            SELECT COUNT(*) FROM notice_read r
             WHERE r.notice_id = CAST(:noticeId AS uuid) AND r.user_id = CAST(:userId AS uuid)
            """, nativeQuery = true)
    long countReadBy(@Param("noticeId") UUID noticeId, @Param("userId") UUID userId);

    @Query("SELECT COUNT(n) FROM Notice n WHERE n.status = :status "
            + "AND (n.publishAt IS NULL OR n.publishAt <= :now) "
            + "AND (n.offlineAt IS NULL OR n.offlineAt >= :now)")
    long countDisplayable(@Param("status") String status, @Param("now") LocalDateTime now);

    long countByStatus(String status);
}
