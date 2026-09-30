package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.UserMessage;
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

/**
 * 站内信读写（U01/U03/U04/U10/U23）。
 *
 * <p>{@link #insertIfAbsent} 是「重复请求不双发」的实现点：直接 INSERT + ON CONFLICT DO NOTHING，
 * 返回影响行数。先查再插在并发下两条都能通过检查，只有唯一索引能真正拦下来。
 */
public interface UserMessageRepository extends JpaRepository<UserMessage, UUID> {

    /** 幂等落库：dedup_key 撞唯一索引时返回 0，调用方据此知道「这条已经触达过了」 */
    @Modifying
    @Query(value = """
            INSERT INTO user_message (id, user_id, category, title, content, link_url, biz_type, biz_id,
                                      template_code, dedup_key, is_read, read_at, created_at, updated_at)
            VALUES (CAST(:id AS uuid), CAST(:userId AS uuid), :category, :title, :content, :linkUrl,
                    :bizType, :bizId, :templateCode, :dedupKey, false, NULL, now(), now())
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("category") String category,
                       @Param("title") String title,
                       @Param("content") String content,
                       @Param("linkUrl") String linkUrl,
                       @Param("bizType") String bizType,
                       @Param("bizId") String bizId,
                       @Param("templateCode") String templateCode,
                       @Param("dedupKey") String dedupKey);

    /** U03 角标：未读数走 (user_id, is_read, created_at) 打头的索引 */
    long countByUserIdAndIsReadFalse(UUID userId);

    /** 铃铛下拉：最近 N 条未读优先，不足再补已读，一个查询解决而不是两次往返 */
    @Query("""
            SELECT m FROM UserMessage m
            WHERE m.userId = :userId
            ORDER BY m.isRead ASC, m.createdAt DESC
            """)
    List<UserMessage> findRecent(@Param("userId") UUID userId, Pageable pageable);

    /** U04 列表：category 传空串表示不限，unreadOnly 为 true 时只看未读 */
    @Query("""
            SELECT m FROM UserMessage m
            WHERE m.userId = :userId
              AND (:category = '' OR m.category = :category)
              AND (:unreadOnly = false OR m.isRead = false)
            """)
    Page<UserMessage> searchForUser(@Param("userId") UUID userId,
                                    @Param("category") String category,
                                    @Param("unreadOnly") boolean unreadOnly,
                                    Pageable pageable);

    /** 后台触达明细：按类别 / 事件类型 / 收件人筛选，keyword 命中标题或正文 */
    @Query("""
            SELECT m FROM UserMessage m
            WHERE (:category = '' OR m.category = :category)
              AND (:bizType = '' OR m.bizType = :bizType)
              AND (:userId IS NULL OR m.userId = :userId)
              AND (:keyword = '' OR LOWER(m.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(COALESCE(m.content, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
            """)
    Page<UserMessage> searchForAdmin(@Param("category") String category,
                                     @Param("bizType") String bizType,
                                     @Param("userId") UUID userId,
                                     @Param("keyword") String keyword,
                                     Pageable pageable);

    Optional<UserMessage> findByIdAndUserId(UUID id, UUID userId);

    /**
     * 分类 tab 上的角标：一次分组拿回「每类的总量 + 未读量」。
     *
     * <p>铃铛是 30 秒轮询的接口，逐类各打一次 count 会把四个 tab 变成八次往返。
     */
    @Query("SELECT m.category, COUNT(m), "
            + "SUM(CASE WHEN m.isRead = false THEN 1 ELSE 0 END) "
            + "FROM UserMessage m WHERE m.userId = :userId GROUP BY m.category")
    List<Object[]> categorySummary(@Param("userId") UUID userId);

    /** U04 单条已读：条件带上「未读」，重复点击影响行数 0，read_at 不会被覆盖成第二个时间 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserMessage m SET m.isRead = true, m.readAt = :now, m.updatedAt = :now "
            + "WHERE m.id = :id AND m.userId = :userId AND m.isRead = false")
    int markRead(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /** U04 全部已读：category 传空串表示不限类别，返回真实变更条数（角标据此归零） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserMessage m SET m.isRead = true, m.readAt = :now, m.updatedAt = :now "
            + "WHERE m.userId = :userId AND m.isRead = false AND (:category = '' OR m.category = :category)")
    int markAllRead(@Param("userId") UUID userId, @Param("category") String category,
                    @Param("now") LocalDateTime now);

    /** U23 每日营销上限：统计自然日内的营销条数，上限判定在 NotificationPolicy */    @Query("SELECT COUNT(m) FROM UserMessage m WHERE m.userId = :userId "
            + "AND m.category = 'marketing' AND m.createdAt >= :dayStart")
    long countMarketingSince(@Param("userId") UUID userId, @Param("dayStart") LocalDateTime dayStart);

    /** 事件回看：同一业务事件是否已经触达过（定时补采与事件监听两条路径共用这一个判断） */
    boolean existsByDedupKey(String dedupKey);

    /** U10 清理前先按用户 + 月份 + 类别聚合，作为归档计数落 message_archive_stat */
    @Query(value = """
            SELECT user_id                  AS userId,
                   to_char(created_at, 'YYYY-MM') AS bucketMonth,
                   category                 AS category,
                   COUNT(*)                 AS messageCount
              FROM user_message
             WHERE created_at < :cutoff
             GROUP BY user_id, to_char(created_at, 'YYYY-MM'), category
            """, nativeQuery = true)
    List<Object[]> aggregateBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 物理删除：按 cutoff 一次删净，任务按批次上限限制单轮处理量 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserMessage m WHERE m.createdAt < :cutoff")
    int deleteCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** 保留期内的总量与未读数：后台消息中心的概览卡 */
    @Query("SELECT COUNT(m) FROM UserMessage m WHERE m.createdAt >= :cutoff")
    long countRetained(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT m.bizType, COUNT(m) FROM UserMessage m WHERE m.createdAt >= :cutoff GROUP BY m.bizType")
    List<Object[]> eventDistribution(@Param("cutoff") LocalDateTime cutoff);
}
