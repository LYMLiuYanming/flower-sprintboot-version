package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.TicketMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 工单往返（U16） */
public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    /** 时间线：正序，客服内部备注（internalNote）由展示层过滤，不在这里丢信息 */
    @Query("SELECT m FROM TicketMessage m WHERE m.ticketId = :ticketId ORDER BY m.createdAt ASC, m.id ASC")
    List<TicketMessage> timelineOf(@Param("ticketId") UUID ticketId);

    /** 顾客侧时间线：内部备注与只给客服看的系统行不下发 */
    @Query("""
            SELECT m FROM TicketMessage m
            WHERE m.ticketId = :ticketId AND m.internalNote = false
            ORDER BY m.createdAt ASC, m.id ASC
            """)
    List<TicketMessage> customerTimelineOf(@Param("ticketId") UUID ticketId);

    long countByTicketId(UUID ticketId);

    @Query("SELECT m.authorType, COUNT(m) FROM TicketMessage m WHERE m.ticketId = :ticketId GROUP BY m.authorType")
    List<Object[]> authorSummary(@Param("ticketId") UUID ticketId);

    /** 最近一条客服回复：工单列表页的「最新进展」列用它，省掉逐行 N+1 */
    @Query("SELECT m FROM TicketMessage m WHERE m.ticketId = :ticketId AND m.authorType <> 'customer' "
            + "ORDER BY m.createdAt DESC")
    List<TicketMessage> lastAgentReply(@Param("ticketId") UUID ticketId, Pageable pageable);

    /** 未读给顾客的回复数：顾客端工单列表的「客服已回复」红点 */
    @Query("SELECT COUNT(m) FROM TicketMessage m WHERE m.ticketId IN :ticketIds "
            + "AND m.authorType IN ('agent', 'system') AND m.unreadForCustomer = true")
    long countUnreadReplies(@Param("ticketIds") List<UUID> ticketIds);

    /**
     * 列表页的「有未读回复」红点：一整页只要一条 IN 查询。
     *
     * <p>逐行调 lastAgentReply 会把 20 行的列表变成 20 次往返，这是工单队列最容易踩的 N+1。
     */
    @Query("SELECT DISTINCT m.ticketId FROM TicketMessage m WHERE m.ticketId IN :ticketIds "
            + "AND m.authorType IN ('agent', 'system') AND m.internalNote = false AND m.unreadForCustomer = true")
    List<UUID> ticketIdsWithUnreadReply(@Param("ticketIds") List<UUID> ticketIds);

    /**
     * 顾客打开工单详情即清红点：条件 UPDATE 只在真有未读时改行，
     * 影响行数即「本次清掉几条」，重复刷新不会把已读时间戳来回改。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketMessage m SET m.unreadForCustomer = false "
            + "WHERE m.ticketId = :ticketId AND m.unreadForCustomer = true")
    int clearUnreadForCustomer(@Param("ticketId") UUID ticketId);
}
