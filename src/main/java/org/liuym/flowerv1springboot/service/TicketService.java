package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.TicketDtos;
import org.liuym.flowerv1springboot.vo.TicketViews;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客服工单（U12/U13/U15/U16/U24/U26）：顾客侧提单与往返，后台侧受理、回复、关闭。
 *
 * <p>状态跃迁、派单认领、满意度评分都走条件 UPDATE + 影响行数判断，
 * 服务层不做「先查再改」，两个人同时点也只有一个能改成功。
 */
public interface TicketService {

    /* ---------- 顾客侧 ---------- */

    /** U12 提单：orderId 非空时服务端校验归属，不信任页面传来的 id */
    TicketViews.Brief create(UUID userId, String userName, TicketDtos.CreateForm form);

    /** 我的工单列表：status 空串不限，按建单时间倒序 */
    Page<TicketViews.Brief> listForUser(UUID userId, String status, int page, int limit);

    /** U16 详情 + 时间线：只看得到非内部备注的行，打开即把客服回复标记为已读 */
    TicketViews.Detail detailForUser(UUID ticketId, UUID userId);

    /** U16 顾客留言往返 */
    int customerReply(UUID ticketId, UUID userId, String userName, TicketDtos.ReplyForm form);

    /** U13 顾客侧跃迁：只有「重新打开」这一条路，其余目标状态一律拒绝 */
    int customerTransit(UUID ticketId, UUID userId, String userName, TicketDtos.TransitForm form);

    /** U24 满意度：satisfaction 非空即不可再评 */
    int rate(UUID ticketId, UUID userId, TicketDtos.RateForm form);

    /** 提单页的可选订单：只列本人近 90 天的可售后订单 */
    List<Map<String, Object>> selectableOrders(UUID userId);

    /* ---------- 后台侧 ---------- */

    /**
     * U15 队列。
     *
     * @param scopeAssigneeId U26 数据范围：普通客服传自己的 userId 只看分配给自己的单，主管传 null 看全量
     */
    Page<TicketViews.Brief> searchQueue(TicketDtos.QueueQuery query, UUID scopeAssigneeId);

    /** U16 后台详情：含内部备注 */
    TicketViews.Detail detailForAdmin(UUID ticketId, UUID operatorId, boolean supervisor);

    /** U26 认领：只从没处理人的单上抢，影响行数 0 表示被别人抢先了 */
    int claim(UUID ticketId, UUID operatorId, String operatorName);

    /** U26 主管改派 */
    int assign(UUID ticketId, UUID assigneeId, String assigneeName, boolean override, String note);

    /** U13 后台跃迁：受理（→processing）、解决（→resolved）、关闭（→closed） */
    int adminTransit(UUID ticketId, UUID operatorId, String operatorName, boolean supervisor,
                     TicketDtos.TransitForm form);

    /** U16 客服回复：首条回复写 first_response_at，内部备注不打扰顾客 */
    int adminReply(UUID ticketId, UUID operatorId, String operatorName, boolean supervisor,
                   TicketDtos.ReplyForm form);

    /** 字典：状态/优先级/类型中文与顾客可做的动作，页面不再维护第二份映射 */
    TicketViews.Dictionary dictionary();

    /**
     * U26 数据范围判定：主管看全量队列，普通客服只看分配给自己的工单。
     *
     * <p>名录里没有登记时按主管处理：V48 的迁移已把所有 admin 账号登记为主管，
     * 没登记进来的管理员说明是刚建的号，宁可让他看得见全量也不要看不见自己在排障的单。
     */
    boolean isSupervisor(UUID operatorId);

    /** 可派单的客服名录（后台派单下拉） */
    List<TicketViews.AgentView> assignableAgents();

    /** 队列概览卡：各状态计数 + 逾期 + 即将超时 */
    Map<String, Object> queueSummary();
}
