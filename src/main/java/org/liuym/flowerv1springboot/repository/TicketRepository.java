package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Ticket;
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
 * 工单读写（U13/U14/U15/U17/U24/U25/U26）。
 *
 * <p>所有状态与闸门变更都走「带旧值条件的 UPDATE + 影响行数断言」：
 * 返回 0 即说明这次请求没抢到，服务层据此抛 409，不会重复通知、不会双发补偿。
 *
 * <p>筛选参数一律传空串而不是 null 表示「不限」：JPQL 里 {@code :p IS NULL OR ...} 对
 * null 参数的类型推断在 PostgreSQL 上不稳，空串是本项目既有约定（见 NoticeRepository）。
 */
public interface TicketRepository extends JpaRepository<Ticket, UUID> {

    Optional<Ticket> findByTicketNo(String ticketNo);

    /** 同一来源（如某条评价）是否已开过单：自动开单的便宜前置判断，唯一索引才是最终依据 */
    boolean existsBySourceTypeAndSourceId(String sourceType, String sourceId);

    /**
     * U15 队列：状态、类型、优先级、处理人、关联订单、关键词都可筛；
     * deadlineState = overdue / warning（即将超时，剩 60 分钟内）/ settled，空串表示不限。
     *
     * <p>排序写死在查询里：未结单优先 + SLA 剩余时间升序 + 新建时间倒序。
     * PostgreSQL 的 ASC 默认就是 NULLS LAST，所以「没算出时限」的单自然沉到队尾。
     */
    @Query("""
            SELECT t FROM Ticket t
            WHERE (:status = '' OR t.status = :status)
              AND (:category = '' OR t.category = :category)
              AND (:priority = '' OR t.priority = :priority)
              AND (:assigneeId IS NULL OR t.assigneeId = :assigneeId)
              AND (:orderId IS NULL OR t.orderId = :orderId)
              AND (:userId IS NULL OR t.userId = :userId)
              AND (:keyword = '' OR LOWER(t.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(t.ticketNo) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(COALESCE(t.description, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:deadlineState <> 'overdue'
                   OR (t.status IN ('open', 'assigned', 'processing') AND t.resolveDueAt < :now))
              AND (:deadlineState <> 'warning'
                   OR (t.status IN ('open', 'assigned', 'processing')
                       AND t.resolveDueAt >= :now AND t.resolveDueAt <= :warningUntil))
              AND (:deadlineState <> 'settled' OR t.status IN ('resolved', 'closed'))
            ORDER BY CASE WHEN t.status IN ('resolved', 'closed') THEN 1 ELSE 0 END ASC,
                     t.resolveDueAt ASC, t.createdAt DESC
            """)
    Page<Ticket> searchQueue(@Param("status") String status,
                             @Param("category") String category,
                             @Param("priority") String priority,
                             @Param("assigneeId") UUID assigneeId,
                             @Param("orderId") UUID orderId,
                             @Param("userId") UUID userId,
                             @Param("keyword") String keyword,
                             @Param("deadlineState") String deadlineState,
                             @Param("now") LocalDateTime now,
                             @Param("warningUntil") LocalDateTime warningUntil,
                             Pageable pageable);

    /**
     * U13 状态跃迁（CAS）：WHERE 带上读到的旧状态；
     * 重开时额外要求 reopen_count 未达上限，「只允许重开一次」由 SQL 自己守住而不是靠应用层判断。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.status = :to,
                   t.reopenCount = CASE WHEN :asReopen = true THEN t.reopenCount + 1 ELSE t.reopenCount END,
                   t.closedAt = CASE WHEN :to = 'closed' THEN :now ELSE t.closedAt END,
                   t.resolvedAt = CASE WHEN :to = 'resolved' THEN :now ELSE t.resolvedAt END,
                   t.solution = COALESCE(:solution, t.solution),
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.status = :from
               AND (:asReopen = false OR t.reopenCount < :maxReopen)
            """)
    int transit(@Param("id") UUID id,
                @Param("from") String from,
                @Param("to") String to,
                @Param("asReopen") boolean asReopen,
                @Param("maxReopen") int maxReopen,
                @Param("solution") String solution,
                @Param("now") LocalDateTime now);

    /** U26 认领/派单：默认只允许从没处理人的单上成功，两个客服同时点只有一个拿得到；override 供主管改派 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.assigneeId = :assigneeId, t.assigneeName = :assigneeName,
                   t.status = CASE WHEN t.status = 'open' THEN 'assigned' ELSE t.status END,
                   t.updatedAt = :now
             WHERE t.id = :id
               AND (t.assigneeId IS NULL OR :override = true)
               AND t.status IN ('open', 'assigned', 'processing')
            """)
    int assign(@Param("id") UUID id, @Param("assigneeId") UUID assigneeId,
               @Param("assigneeName") String assigneeName,
               @Param("override") boolean override, @Param("now") LocalDateTime now);

    /** 首响时间只写一次：客服后续再回复不会覆盖 first_response_at，看板的 P50/P90 才是真首响 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.firstResponseAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.firstResponseAt IS NULL")
    int stampFirstResponse(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * U14 逾期升级：条件带上「还没升级过 + 确实逾期 + 还没结单」，
     * 影响行数 0 即别的轮次已经升过，绝不重复升级优先级、重复提醒处理人。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.priority = :nextPriority, t.escalated = true, t.escalatedAt = :now,
                   t.escalateNote = :note,
                   t.resolveDueAt = COALESCE(:extendedDue, t.resolveDueAt),
                   t.updatedAt = :now
             WHERE t.id = :id
               AND t.escalated = false
               AND t.status IN ('open', 'assigned', 'processing')
               AND t.resolveDueAt < :now
            """)
    int escalate(@Param("id") UUID id, @Param("nextPriority") String nextPriority,
                 @Param("note") String note, @Param("extendedDue") LocalDateTime extendedDue,
                 @Param("now") LocalDateTime now);

    /** 首响逾期升级：只升优先与标记，不去改解决时限（解决时限由另一条扫描处理） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.priority = :nextPriority, t.escalated = true, t.escalatedAt = :now,
                   t.escalateNote = :note, t.updatedAt = :now
             WHERE t.id = :id
               AND t.escalated = false
               AND t.firstResponseAt IS NULL
               AND t.status IN ('open', 'assigned', 'processing')
               AND t.firstResponseDueAt < :now
            """)
    int escalateFirstResponse(@Param("id") UUID id, @Param("nextPriority") String nextPriority,
                              @Param("note") String note, @Param("now") LocalDateTime now);

    /** 解决时限已逾期、还没升级的单：按逾期先后处理，最久的排最前 */
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.escalated = false
              AND t.status IN ('open', 'assigned', 'processing')
              AND t.resolveDueAt IS NOT NULL AND t.resolveDueAt < :now
            ORDER BY t.resolveDueAt ASC
            """)
    List<Ticket> findOverdueUnescalated(@Param("now") LocalDateTime now, Pageable pageable);

    /** 首响已逾期但还没升级的单：与解决时限共用 escalated 闸门，一单只升一次 */
    @Query("""
            SELECT t FROM Ticket t
            WHERE t.escalated = false
              AND t.firstResponseAt IS NULL
              AND t.status IN ('open', 'assigned', 'processing')
              AND t.firstResponseDueAt IS NOT NULL AND t.firstResponseDueAt < :now
            ORDER BY t.firstResponseDueAt ASC
            """)
    List<Ticket> findFirstResponseOverdue(@Param("now") LocalDateTime now, Pageable pageable);

    /** U17 补偿闸门：compensation_type 非空即不可再补，CAS 只允许一次 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Ticket t
               SET t.compensationType = :type, t.compensationRef = :ref,
                   t.compensationNote = :note, t.compensatedAt = :now, t.updatedAt = :now
             WHERE t.id = :id
               AND t.compensationType IS NULL
               AND t.status IN ('open', 'assigned', 'processing')
            """)
    int bindCompensation(@Param("id") UUID id, @Param("type") String type, @Param("ref") String ref,
                         @Param("note") String note, @Param("now") LocalDateTime now);

    /**
     * 补偿没真正落地时拆掉闸门供重试：条件带上类型，
     * 防止「A 线程刚绑好 coupon、B 线程失败回滚」把别人的补偿记录抹掉。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.compensationType = NULL, t.compensationRef = NULL, t.compensatedAt = NULL, "
            + "t.compensationNote = :note, t.updatedAt = :now WHERE t.id = :id AND t.compensationType = :type")
    int releaseCompensation(@Param("id") UUID id, @Param("type") String type,
                            @Param("note") String note, @Param("now") LocalDateTime now);

    /** U24 满意度：satisfaction 非空即不可再评（CAS），且只有已解决的单能评 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.satisfaction = :score, t.satisfactionNote = :note, "
            + "t.satisfactionAt = :now, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.satisfaction IS NULL AND t.status = 'resolved'")
    int rate(@Param("id") UUID id, @Param("score") int score, @Param("note") String note,
             @Param("now") LocalDateTime now);

    @Query("SELECT t.status, COUNT(t) FROM Ticket t GROUP BY t.status")
    List<Object[]> statusSummary();

    /** U25 看板：类型分布 */
    @Query("SELECT t.category, COUNT(t) FROM Ticket t "
            + "WHERE t.createdAt BETWEEN :from AND :to GROUP BY t.category ORDER BY COUNT(t) DESC")
    List<Object[]> categoryDistribution(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT t.priority, COUNT(t) FROM Ticket t "
            + "WHERE t.createdAt BETWEEN :from AND :to GROUP BY t.priority")
    List<Object[]> priorityDistribution(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.createdAt BETWEEN :from AND :to")
    long countCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 达标率分母/分子：按建单时间归属时间窗，结单且未超时的算达标 */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.status IN ('resolved', 'closed') "
            + "AND t.createdAt BETWEEN :from AND :to AND t.resolveDueAt IS NOT NULL AND t.resolvedAt <= t.resolveDueAt")
    long countOnTimeBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.status IN ('resolved', 'closed') "
            + "AND t.createdAt BETWEEN :from AND :to")
    long countSettledBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.reopenCount > 0 AND t.createdAt BETWEEN :from AND :to")
    long countReopenedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT AVG(t.satisfaction) FROM Ticket t WHERE t.satisfaction IS NOT NULL "
            + "AND t.createdAt BETWEEN :from AND :to")
    Double averageSatisfaction(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.satisfaction IS NOT NULL AND t.createdAt BETWEEN :from AND :to")
    long countRatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.status IN ('open', 'assigned', 'processing') "
            + "AND t.resolveDueAt < :now")
    long countOverdueOpen(@Param("now") LocalDateTime now);

    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.status IN ('open', 'assigned', 'processing') "
            + "AND t.resolveDueAt >= :now AND t.resolveDueAt <= :warningUntil")
    long countApproaching(@Param("now") LocalDateTime now, @Param("warningUntil") LocalDateTime warningUntil);

    long countByStatus(String status);

    /**
     * 派单下拉上的「在途几张」：一次分组查询覆盖整份名录。
     *
     * <p>逐人各打三次 countByStatus 会让名录变成 N+1 的重灾区，客服团队一大就明显。
     */
    @Query("SELECT t.assigneeId, COUNT(t) FROM Ticket t "
            + "WHERE t.status IN ('open', 'assigned', 'processing') AND t.assigneeId IN :assigneeIds "
            + "GROUP BY t.assigneeId")
    List<Object[]> countOpenByAssignees(@Param("assigneeIds") List<UUID> assigneeIds);

    /** 名录上的「已办结」参考值，与在途数同一条分组查询口径 */
    @Query("SELECT t.assigneeId, COUNT(t) FROM Ticket t "
            + "WHERE t.status IN ('resolved', 'closed') AND t.assigneeId IN :assigneeIds "
            + "GROUP BY t.assigneeId")
    List<Object[]> countResolvedByAssignees(@Param("assigneeIds") List<UUID> assigneeIds);

    long countByUserId(UUID userId);

    List<Ticket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    /** 顾客同一订单上的在途工单：提单页提示「已有处理中的工单」，服务端仍是最终判定 */
    @Query("SELECT COUNT(t) FROM Ticket t WHERE t.orderId = :orderId AND t.status IN ('open', 'assigned', 'processing')")
    long countOpenByOrder(@Param("orderId") UUID orderId);

    /**
     * U25 导出：把时间窗内的工单关键列一次性取平，
     * 时长在 SQL 侧由服务层按分钟算，避免把全量实体拉进内存再逐条 Duration。
     */
    @Query("""
            SELECT t.ticketNo, t.category, t.priority, t.status, t.createdAt,
                   t.firstResponseAt, t.resolvedAt, t.resolveDueAt, t.escalated, t.satisfaction,
                   t.assigneeName, t.compensationType
              FROM Ticket t
             WHERE t.createdAt BETWEEN :from AND :to
             ORDER BY t.createdAt DESC
            """)
    List<Object[]> exportRows(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
