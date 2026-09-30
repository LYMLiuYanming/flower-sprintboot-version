package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.ScheduledMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 延时消息（U20/U21/U22）。
 *
 * <p>投递前必须 {@link #claim} 抢占：条件 UPDATE 把 pending 改成 sending，
 * 影响行数 0 就说明这一条已被别的线程/别的轮次拿走，调用方直接跳过。
 * 这是「重启后不重发、多任务不重发」的唯一依据。
 */
public interface ScheduledMessageRepository extends JpaRepository<ScheduledMessage, UUID> {

    /**
     * 排期落库（幂等）：dedup_key 撞唯一索引返回 0，表示同一事件已经排过期。
     * 与 user_message 同一做法，避免「先查再插」在并发下排出两条同样的延时消息。
     */
    @Modifying
    @Query(value = """
            INSERT INTO scheduled_message (id, user_id, category, template_code, title, content, link_url,
                                          payload, due_at, status, attempts, max_attempts, last_error, sent_at,
                                          dedup_key, biz_type, biz_id, customised, created_at, updated_at)
            VALUES (CAST(:id AS uuid), CAST(:userId AS uuid), :category, :templateCode, :title, :content, :linkUrl,
                    :payload, :dueAt, 'pending', 0, 3, NULL, NULL, :dedupKey, :bizType, :bizId, :customised,
                    now(), now())
            ON CONFLICT (dedup_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("category") String category,
                       @Param("templateCode") String templateCode,
                       @Param("title") String title,
                       @Param("content") String content,
                       @Param("linkUrl") String linkUrl,
                       @Param("payload") String payload,
                       @Param("dueAt") LocalDateTime dueAt,
                       @Param("dedupKey") String dedupKey,
                       @Param("bizType") String bizType,
                       @Param("bizId") String bizId,
                       @Param("customised") boolean customised);

    /**
     * 到期待发：只挑 pending，或挑「sending 但抢占者已超过 5 分钟没动静」的行。
     *
     * <p>后一半是崩溃恢复：进程在 claim 之后、发送之前挂掉，行会永远停在 sending，
     * 5 分钟的租约窗口比人手的处理耗时宽得多，超时后允许被重新抢走。
     */
    @Query("""
            SELECT s FROM ScheduledMessage s
            WHERE s.dueAt <= :now
              AND (s.status = 'pending'
                   OR (s.status = 'sending' AND s.updatedAt < :leaseExpiredAt))
            ORDER BY s.dueAt ASC
            """)
    List<ScheduledMessage> findDue(@Param("now") LocalDateTime now,
                                   @Param("leaseExpiredAt") LocalDateTime leaseExpiredAt,
                                   Pageable pageable);

    /** U21 抢占：pending（或租约过期的 sending）→ sending，返回 0 表示别人已经拿走 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE ScheduledMessage s
               SET s.status = 'sending', s.attempts = s.attempts + 1, s.updatedAt = :now
             WHERE s.id = :id
               AND (s.status = 'pending'
                    OR (s.status = 'sending' AND s.updatedAt < :leaseExpiredAt))
            """)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now,
              @Param("leaseExpiredAt") LocalDateTime leaseExpiredAt);

    /** 投递成功：sending → sent，状态条件保证只有抢占者能写结论 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledMessage s SET s.status = 'sent', s.sentAt = :now, s.lastError = NULL, "
            + "s.updatedAt = :now WHERE s.id = :id AND s.status = 'sending'")
    int markSent(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 投递失败且还够重试次数：退回 pending 并把 dueAt 推到下一轮，状态条件保证只有抢占者能写结论 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledMessage s SET s.status = 'pending', s.lastError = :error, "
            + "s.dueAt = :retryAt, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status = 'sending' AND s.attempts < s.maxAttempts")
    int markRetry(@Param("id") UUID id, @Param("now") LocalDateTime now,
                  @Param("retryAt") LocalDateTime retryAt, @Param("error") String error);

    /** 重试次数耗尽：置终态 failed，后台按 lastError 排障 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledMessage s SET s.status = 'failed', s.lastError = :error, s.updatedAt = :now "
            + "WHERE s.id = :id AND s.status = 'sending'")
    int markFailed(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("error") String error);

    /** 取消未发：订单被退款/工单被关时撤掉后续提醒，已 sent 的行不动 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ScheduledMessage s SET s.status = 'cancelled', s.updatedAt = :now "
            + "WHERE s.bizType = :bizType AND s.bizId = :bizId AND s.status IN ('pending', 'sending')")
    int cancelByBiz(@Param("bizType") String bizType, @Param("bizId") String bizId,
                    @Param("now") LocalDateTime now);

    boolean existsByDedupKey(String dedupKey);

    /** 后台待发清单：状态 + 时间窗，逾期未发的行按 dueAt 升序排前面 */
    @Query("""
            SELECT s FROM ScheduledMessage s
            WHERE (:status = '' OR s.status = :status)
              AND (:bizType = '' OR s.bizType = :bizType)
              AND (s.dueAt BETWEEN :from AND :to)
            """)
    List<ScheduledMessage> search(@Param("status") String status, @Param("bizType") String bizType,
                                  @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  Pageable pageable);

    @Query("SELECT s.status, COUNT(s) FROM ScheduledMessage s GROUP BY s.status")
    List<Object[]> statusSummary();

    /**
     * 事件接入台账：每个业务类型还有多少条没投递出去（pending/sending/failed）。
     *
     * <p>后台要回答的是「这类事件卡住了没有」，所以这里按 biz_type 分组而不是按状态分组，
     * 一次查询覆盖八类事件，避免看板逐类打库。
     */
    @Query("SELECT s.bizType, COUNT(s) FROM ScheduledMessage s "
            + "WHERE s.status IN ('pending', 'sending', 'failed') GROUP BY s.bizType")
    List<Object[]> undeliveredByBizType();

    /** 养护提醒统计（U22）：命中知识库与走通用文案各多少条 */
    @Query("SELECT s.customised, COUNT(s) FROM ScheduledMessage s WHERE s.bizType = 'care_reminder' GROUP BY s.customised")
    List<Object[]> careSummary();
}
