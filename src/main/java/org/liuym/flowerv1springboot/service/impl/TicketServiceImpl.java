package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.Masking;
import org.liuym.flowerv1springboot.common.MessagePublisher;
import org.liuym.flowerv1springboot.common.NotificationEvent;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.TicketFlowPolicy;
import org.liuym.flowerv1springboot.common.TicketSlaPolicy;
import org.liuym.flowerv1springboot.dto.TicketDtos;
import org.liuym.flowerv1springboot.model.Ticket;
import org.liuym.flowerv1springboot.model.TicketAgent;
import org.liuym.flowerv1springboot.model.TicketMessage;
import org.liuym.flowerv1springboot.model.TicketSlaRule;
import org.liuym.flowerv1springboot.repository.TicketAgentRepository;
import org.liuym.flowerv1springboot.repository.TicketMessageRepository;
import org.liuym.flowerv1springboot.repository.TicketRepository;
import org.liuym.flowerv1springboot.repository.TicketSlaRuleRepository;
import org.liuym.flowerv1springboot.repository.TicketSourceRepository;
import org.liuym.flowerv1springboot.service.TicketService;
import org.liuym.flowerv1springboot.vo.TicketViews;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单实现（U12/U13/U15/U16/U24/U26）。
 *
 * <p>三处闸门都落在 SQL 的条件上，不在 Java 里判：
 * 状态跃迁带旧状态、认领带「assignee 为空」、评分带「satisfaction 为空」。
 * 影响行数 0 就是别人先改到了，接口抛 409 让页面刷新后重试，不会出现双开工单或双发通知。
 */
@Service
@Transactional
public class TicketServiceImpl implements TicketService {

    private static final DateTimeFormatter NO_STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");

    /** 顾客能自助发起的目标状态：状态机里只有「重新打开」这一条 */
    private static final Set<String> CUSTOMER_TARGETS = Set.of(Ticket.STATUS_OPEN);

    private final TicketRepository ticketRepository;
    private final TicketMessageRepository messageRepository;
    private final TicketSlaRuleRepository slaRuleRepository;
    private final TicketAgentRepository agentRepository;
    private final TicketSourceRepository sourceRepository;
    private final MessagePublisher publisher;

    public TicketServiceImpl(TicketRepository ticketRepository,
                             TicketMessageRepository messageRepository,
                             TicketSlaRuleRepository slaRuleRepository,
                             TicketAgentRepository agentRepository,
                             TicketSourceRepository sourceRepository,
                             MessagePublisher publisher) {
        this.ticketRepository = ticketRepository;
        this.messageRepository = messageRepository;
        this.slaRuleRepository = slaRuleRepository;
        this.agentRepository = agentRepository;
        this.sourceRepository = sourceRepository;
        this.publisher = publisher;
    }

    /* ---------- U12 提单 ---------- */

    @Override
    public TicketViews.Brief create(UUID userId, String userName, TicketDtos.CreateForm form) {
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        if (!TicketFlowPolicy.isKnownCategory(form.category())) {
            throw new BusinessException("问题类型不合法：" + form.category());
        }
        String priority = form.priority() == null || form.priority().isBlank()
                ? Ticket.PRIORITY_NORMAL : form.priority().trim();
        if (!TicketFlowPolicy.isKnownPriority(priority)) {
            throw new BusinessException("优先级不合法：" + priority);
        }
        if (form.orderId() != null && !sourceRepository.orderBelongsTo(form.orderId(), userId)) {
            // 归属只认库，页面传谁的订单 id 都不作数
            throw BusinessException.forbidden("该订单不属于当前账号");
        }
        if (form.orderId() != null && ticketRepository.countOpenByOrder(form.orderId()) > 0) {
            throw new BusinessException("这张订单已有处理中的工单，请在原工单里继续补充情况");
        }

        LocalDateTime now = LocalDateTime.now();
        TicketSlaRule rule = resolveRule(form.category(), priority);
        Ticket ticket = new Ticket();
        ticket.setTicketNo(nextTicketNo(now));
        ticket.setUserId(userId);
        ticket.setOrderId(form.orderId());
        ticket.setContactPhone(snapshotPhone(userId, form.orderId()));
        ticket.setCategory(form.category());
        ticket.setTitle(truncate(form.title().trim(), 200));
        ticket.setDescription(form.description());
        ticket.setImages(form.images());
        ticket.setPriority(priority);
        ticket.setStatus(Ticket.STATUS_OPEN);
        ticket.setSourceType(Ticket.SOURCE_MANUAL);
        if (rule != null) {
            ticket.setSlaRuleId(rule.getId());
            ticket.setFirstResponseDueAt(TicketSlaPolicy.firstResponseDueAt(now, rule));
            ticket.setResolveDueAt(TicketSlaPolicy.resolveDueAt(now, rule));
        }
        Ticket saved = ticketRepository.save(ticket);
        // 兜底：建单时规则被停用就没有时限，立刻按默认值补一次，否则队列里的单永远排不到前面
        if (saved.getResolveDueAt() == null) {
            saved.setResolveDueAt(TicketSlaPolicy.resolveDueAt(now, null));
            saved.setFirstResponseDueAt(TicketSlaPolicy.firstResponseDueAt(now, null));
            saved = ticketRepository.save(saved);
        }
        systemEntry(saved.getId(), "工单已提交，客服将在 "
                + TicketViews.humanMinutes(rule == null ? 120 : rule.getFirstResponseMinutes()) + "内首次响应", false);
        return TicketViews.Brief.of(saved, now, false);
    }

    /* ---------- 顾客侧读与往返 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Page<TicketViews.Brief> listForUser(UUID userId, String status, int page, int limit) {
        if (userId == null) {
            return Page.empty();
        }
        LocalDateTime now = LocalDateTime.now();
        String normalized = TicketFlowPolicy.isKnownStatus(status) ? status.trim() : "";
        Page<Ticket> found = ticketRepository.searchQueue(normalized, "", "", null, null, userId, "",
                "", now, now.plusMinutes(TicketSlaPolicy.WARNING_MINUTES), Pages.of(page, limit));
        Set<UUID> unread = unreadReplyIds(found.getContent());
        return found.map(t -> TicketViews.Brief.of(t, now, unread.contains(t.getId())));
    }

    @Override
    public TicketViews.Detail detailForUser(UUID ticketId, UUID userId) {
        Ticket ticket = requireOwned(ticketId, userId);
        LocalDateTime now = LocalDateTime.now();
        // 内部备注只在后台出现，顾客侧连条数都不该看到
        List<TicketMessage> entries = messageRepository.customerTimelineOf(ticketId);
        TicketViews.Detail detail = assemble(ticket, entries, now, false);
        // 打开详情即视为看过客服回复：条件 UPDATE 把红点清掉，重复打开只会命中 0 行
        messageRepository.clearUnreadForCustomer(ticketId);
        return detail;
    }

    @Override
    public int customerReply(UUID ticketId, UUID userId, String userName, TicketDtos.ReplyForm form) {
        Ticket ticket = requireOwned(ticketId, userId);
        if (TicketFlowPolicy.isSettled(ticket.getStatus())
                && !Ticket.STATUS_RESOLVED.equals(ticket.getStatus())) {
            throw new BusinessException("工单已关闭，请重新打开或新建工单后再补充情况");
        }
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticketId);
        entry.setAuthorType(TicketMessage.AUTHOR_CUSTOMER);
        entry.setAuthorId(userId);
        entry.setAuthorName(truncate(userName, 60));
        entry.setContent(form.content().trim());
        entry.setAttachments(form.attachments());
        entry.setInternalNote(false);
        entry.setUnreadForCustomer(false);
        messageRepository.save(entry);
        // 顾客补话让已解决的单回到处理中：走同一张条件 UPDATE，抢不到就说明状态已被后台改动
        if (Ticket.STATUS_RESOLVED.equals(ticket.getStatus())) {
            ticketRepository.transit(ticketId, Ticket.STATUS_RESOLVED, Ticket.STATUS_PROCESSING,
                    false, Ticket.MAX_REOPEN, null, LocalDateTime.now());
        }
        return 1;
    }

    @Override
    public int customerTransit(UUID ticketId, UUID userId, String userName, TicketDtos.TransitForm form) {
        Ticket ticket = requireOwned(ticketId, userId);
        String to = form.to() == null ? "" : form.to().trim();
        if (!CUSTOMER_TARGETS.contains(to)) {
            throw new BusinessException("顾客侧只能重新打开工单，其余状态由客服处理");
        }
        return transitInternal(ticket, to, userName, form.reason(), null, true);
    }

    @Override
    public int rate(UUID ticketId, UUID userId, TicketDtos.RateForm form) {
        requireOwned(ticketId, userId);
        int rows = ticketRepository.rate(ticketId, form.score(), form.note(), LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException("这张工单已经评过分了，每次处理只支持一次评分");
        }
        return rows;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> selectableOrders(UUID userId) {
        return sourceRepository.findSelectableOrders(userId, 20).stream()
                .<Map<String, Object>>map(o -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("orderId", o.orderId());
                    row.put("orderNo", o.orderNo());
                    row.put("status", o.status());
                    row.put("statusLabel", o.statusLabel());
                    row.put("payAmount", o.payAmount());
                    row.put("createdAt", o.createdAt());
                    row.put("sampleItemName", o.sampleItemName());
                    row.put("itemCount", o.itemCount());
                    row.put("hasOpenTicket", ticketRepository.countOpenByOrder(o.orderId()) > 0);
                    return row;
                })
                .toList();
    }

    /* ---------- U15/U26 后台侧 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Page<TicketViews.Brief> searchQueue(TicketDtos.QueueQuery query, UUID scopeAssigneeId) {
        LocalDateTime now = LocalDateTime.now();
        Page<Ticket> found = ticketRepository.searchQueue(
                known(query == null ? null : query.status(), TicketFlowPolicy::isKnownStatus),
                known(query == null ? null : query.category(), TicketFlowPolicy::isKnownCategory),
                known(query == null ? null : query.priority(), TicketFlowPolicy::isKnownPriority),
                query != null && query.assigneeId() != null ? query.assigneeId() : scopeAssigneeId,
                query == null ? null : query.orderId(),
                null,
                query == null ? "" : trim(query.keyword()),
                deadlineState(query == null ? null : query.deadlineState()),
                now, now.plusMinutes(TicketSlaPolicy.WARNING_MINUTES),
                Pages.of(query == null ? 1 : query.page(), query == null ? 20 : query.limit()));
        Set<UUID> unread = unreadReplyIds(found.getContent());
        return found.map(t -> TicketViews.Brief.of(t, now, unread.contains(t.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public TicketViews.Detail detailForAdmin(UUID ticketId, UUID operatorId, boolean supervisor) {
        // U26：普通客服看不见没分配给自己的单，越权一律按「不存在」回答，不给探测别人工单号的机会
        Ticket ticket = loadForAdmin(ticketId, operatorId, supervisor);
        return assemble(ticket, messageRepository.timelineOf(ticketId), LocalDateTime.now(), true);
    }

    @Override
    public int claim(UUID ticketId, UUID operatorId, String operatorName) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> BusinessException.notFound("工单不存在"));
        String name = displayName(operatorId, operatorName);
        int rows = ticketRepository.assign(ticketId, operatorId, name, false, LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException("这张工单已经有人在处理了，请刷新队列看下一张");
        }
        systemEntry(ticketId, name + " 已受理（认领）", true);
        return rows;
    }

    @Override
    public int assign(UUID ticketId, UUID assigneeId, String assigneeName, boolean override, String note) {
        if (assigneeId == null) {
            throw new BusinessException("请选择要派给的客服");
        }
        TicketAgent agent = agentRepository.findById(assigneeId)
                .orElseThrow(() -> new BusinessException("该账号未登记为客服，先在客服名录里添加"));
        if (!Boolean.TRUE.equals(agent.getEnabled())) {
            throw new BusinessException("该客服已停用，不能接单");
        }
        int rows = ticketRepository.assign(ticketId, assigneeId, displayName(assigneeId, assigneeName),
                override, LocalDateTime.now());
        if (rows == 0) {
            throw new BusinessException("派单未生效：这张单已有处理人，只有主管能改派");
        }
        systemEntry(ticketId, "已派单给 " + displayName(assigneeId, assigneeName)
                + (note == null || note.isBlank() ? "" : "｜" + truncate(note.trim(), 120)), true);
        return rows;
    }

    @Override
    public int adminTransit(UUID ticketId, UUID operatorId, String operatorName, boolean supervisor,
                            TicketDtos.TransitForm form) {
        Ticket ticket = loadForAdmin(ticketId, operatorId, supervisor);
        String to = form.to() == null ? "" : form.to().trim();
        if (CUSTOMER_TARGETS.contains(to) && !supervisor) {
            throw new BusinessException("重新打开由顾客或主管操作");
        }
        if (Ticket.STATUS_RESOLVED.equals(to) && (form.solution() == null || form.solution().isBlank())) {
            // 结论为空就会把「已解决」推给顾客，通知里只能写一句空话
            throw new BusinessException("标记解决前请先填写处理结论，顾客会收到这条内容");
        }
        return transitInternal(ticket, to, displayName(operatorId, operatorName),
                form.reason(), form.solution(), false);
    }

    @Override
    public int adminReply(UUID ticketId, UUID operatorId, String operatorName, boolean supervisor,
                          TicketDtos.ReplyForm form) {
        Ticket ticket = loadForAdmin(ticketId, operatorId, supervisor);
        boolean internal = Boolean.TRUE.equals(form.internalNote());
        if (internal && !supervisor && !operatorId.equals(ticket.getAssigneeId())) {
            throw BusinessException.notFound("工单不存在");
        }
        String name = displayName(operatorId, operatorName);
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticketId);
        entry.setAuthorType(TicketMessage.AUTHOR_AGENT);
        entry.setAuthorId(operatorId);
        entry.setAuthorName(truncate(name, 60));
        entry.setContent(form.content().trim());
        entry.setAttachments(form.attachments());
        entry.setInternalNote(internal);
        // 内部备注不给顾客打红点，否则顾客会为一个看不到的回复收到一条「有新回复」
        entry.setUnreadForCustomer(!internal);
        messageRepository.save(entry);
        if (!internal) {
            // first_response_at 只写一次：之后再回复不会覆盖，看板的 P50/P90 才是真首响
            ticketRepository.stampFirstResponse(ticketId, LocalDateTime.now());
            // 幂等键带上本次回复的 id：只按工单 id 去重的话，同一张工单的第二次回复会被当成重复事件丢掉
            publisher.publish(NotificationEvent.builder(ticket.getUserId(),
                            MessagePublisher.EVENT_TICKET_REPLIED, ticketId)
                    .template(MessagePublisher.EVENT_TICKET_REPLIED, Map.of(
                            "ticketNo", ticket.getTicketNo() == null ? "" : ticket.getTicketNo(),
                            "title", ticket.getTitle() == null ? "" : ticket.getTitle(),
                            "excerpt", excerpt(form.content())))
                    .link("/user/tickets/" + ticketId)
                    .qualifier(entry.getId())
                    .build());
        }
        return 1;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketViews.Dictionary dictionary() {
        // CATEGORY_LABELS/PRIORITY_LABELS 是 Map.of，遍历顺序不保证；这里按业务顺序固定输出，
        // 否则下拉框的选项每次重启都可能换顺序，后台截图与验收对不上
        List<Map<String, String>> categories = List.of(
                Ticket.CATEGORY_QUALITY, Ticket.CATEGORY_DELIVERY, Ticket.CATEGORY_REFUND,
                Ticket.CATEGORY_CARD, Ticket.CATEGORY_SUBSCRIPTION, Ticket.CATEGORY_OTHER)
                .stream()
                .map(code -> Map.of("code", code, "label", TicketFlowPolicy.categoryLabel(code)))
                .toList();
        List<Map<String, String>> priorities = List.of(
                Ticket.PRIORITY_LOW, Ticket.PRIORITY_NORMAL, Ticket.PRIORITY_HIGH, Ticket.PRIORITY_URGENT)
                .stream()
                .map(code -> Map.of("code", code, "label", TicketFlowPolicy.priorityLabel(code)))
                .toList();
        List<Map<String, String>> statuses = TicketFlowPolicy.statusCodes().stream()
                .map(code -> Map.of("code", code, "label", TicketFlowPolicy.statusLabel(code)))
                .toList();
        return new TicketViews.Dictionary(categories, priorities, statuses, List.of(Ticket.STATUS_OPEN));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> queueSummary() {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        TicketFlowPolicy.statusCodes().forEach(code -> byStatus.put(code, 0L));
        for (Object[] row : ticketRepository.statusSummary()) {
            byStatus.put((String) row[0], ((Number) row[1]).longValue());
        }
        out.put("byStatus", byStatus);
        out.put("open", byStatus.getOrDefault(Ticket.STATUS_OPEN, 0L)
                + byStatus.getOrDefault(Ticket.STATUS_ASSIGNED, 0L)
                + byStatus.getOrDefault(Ticket.STATUS_PROCESSING, 0L));
        out.put("overdue", ticketRepository.countOverdueOpen(now));
        out.put("approaching", ticketRepository.countApproaching(now,
                now.plusMinutes(TicketSlaPolicy.WARNING_MINUTES)));
        out.put("slaRules", slaRuleRepository.countEnabled());
        out.put("agents", agentRepository.findEnabled().size());
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSupervisor(UUID operatorId) {
        if (operatorId == null) {
            return false;
        }
        // 名录里查不到时按主管处理：V48 已把全部 admin 登记为主管，查不到只会是刚建的号，
        // 让他看不见全量队列反而会卡在排障现场
        return agentRepository.findById(operatorId)
                .map(TicketAgent::isSupervisor)
                .orElse(true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TicketViews.AgentView> assignableAgents() {
        List<TicketAgent> agents = agentRepository.findAssignable();
        if (agents.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> usernames = sourceRepository.usernamesOf(agents.stream().map(TicketAgent::getUserId).toList());
        List<UUID> ids = agents.stream().map(TicketAgent::getUserId).toList();
        Map<UUID, Long> openCount = countByAssignee(ticketRepository.countOpenByAssignees(ids));
        Map<UUID, Long> resolvedCount = countByAssignee(ticketRepository.countResolvedByAssignees(ids));
        return agents.stream()
                .map(a -> TicketViews.AgentView.of(a, usernames.get(a.getUserId()),
                        openCount.getOrDefault(a.getUserId(), 0L), resolvedCount.getOrDefault(a.getUserId(), 0L)))
                .toList();
    }

    /** Object[] 分组结果收敛成 map：native/JPQL 分组查询的返回值只有这一种形状 */
    private static Map<UUID, Long> countByAssignee(List<Object[]> rows) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null) {
                out.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return out;
    }

    /* ---------- 内部 ---------- */

    /** 跃迁的共用实现：策略判合法性，条件 UPDATE 判并发，两个都过了才写时间线与通知 */
    private int transitInternal(Ticket ticket, String to, String operator, String reason,
                                String solution, boolean byCustomer) {
        String from = ticket.getStatus();
        if (!TicketFlowPolicy.isKnownStatus(to)) {
            throw new BusinessException("工单状态不存在：" + to);
        }
        String denial = TicketFlowPolicy.denial(from, to, ticket.getReopenCount() == null ? 0 : ticket.getReopenCount());
        if (denial != null) {
            throw new BusinessException(denial);
        }
        if (byCustomer && !Ticket.STATUS_OPEN.equals(to)) {
            throw new BusinessException("顾客侧只能重新打开工单");
        }
        boolean reopen = TicketFlowPolicy.isReopen(from, to);
        LocalDateTime now = LocalDateTime.now();
        int rows = ticketRepository.transit(ticket.getId(), from, to, reopen, Ticket.MAX_REOPEN,
                solution == null || solution.isBlank() ? null : solution.trim(), now);
        if (rows == 0) {
            throw new BusinessException("工单状态刚刚已被他人变更，请刷新后重试");
        }
        TicketMessage mark = systemEntry(ticket.getId(), operator + " 将状态从「" + TicketFlowPolicy.statusLabel(from)
                + "」变更为「" + TicketFlowPolicy.statusLabel(to) + "」"
                // 顾客自己重开不该给自己打未读红点：红点只代表「客服有新动作」
                + (reason == null || reason.isBlank() ? "" : "｜" + truncate(reason.trim(), 120)), !byCustomer);
        if (Ticket.STATUS_RESOLVED.equals(to)) {
            // 幂等键带上这次结论的时间线行 id：工单被重开后再次解决时才不会再静默吞掉通知
            publisher.publish(NotificationEvent.builder(ticket.getUserId(),
                            MessagePublisher.EVENT_TICKET_RESOLVED, ticket.getId())
                    .template(MessagePublisher.EVENT_TICKET_RESOLVED, Map.of(
                            "ticketNo", ticket.getTicketNo() == null ? "" : ticket.getTicketNo(),
                            "solution", solution == null || solution.isBlank()
                                    ? "客服已处理完成" : truncate(solution.trim(), 200)))
                    .link("/user/tickets/" + ticket.getId())
                    .qualifier(mark.getId())
                    .build());
        }
        return rows;
    }

    /** 详情页组装：时间线在改状态之前已全部取成实体列表，之后再无懒加载访问 */
    private TicketViews.Detail assemble(Ticket ticket, List<TicketMessage> entries,
                                        LocalDateTime now, boolean withInternal) {
        LocalDateTime createdAt = ticket.getCreatedAt() == null ? now : ticket.getCreatedAt();
        Long firstResponseMinutes = ticket.getFirstResponseAt() == null ? null
                : Duration.between(createdAt, ticket.getFirstResponseAt()).toMinutes();
        Long resolveMinutes = ticket.getResolvedAt() == null ? null
                : Duration.between(createdAt, ticket.getResolvedAt()).toMinutes();
        List<TicketViews.Entry> timeline = entries.stream()
                .filter(m -> withInternal || !Boolean.TRUE.equals(m.getInternalNote()))
                .map(TicketViews.Entry::of)
                .toList();
        TicketSourceRepository.OrderSnapshot order = ticket.getOrderId() == null ? null
                : sourceRepository.findOrderSnapshot(ticket.getOrderId());
        TicketViews.OrderView orderView = order == null ? null : new TicketViews.OrderView(
                order.orderId(), order.orderNo(), order.status(), order.statusLabel(),
                order.payAmount(), order.refundAmount(), order.refundStatus(),
                order.createdAt(), order.payTime(), order.expectedArriveAt(),
                order.receiverName(), order.receiverPhone(), order.expressCompany(), order.expressNo());
        boolean rateable = ticket.isRateable();
        boolean reopenable = (Ticket.STATUS_RESOLVED.equals(ticket.getStatus())
                || Ticket.STATUS_CLOSED.equals(ticket.getStatus()))
                && (ticket.getReopenCount() == null ? 0 : ticket.getReopenCount()) < Ticket.MAX_REOPEN;
        return new TicketViews.Detail(
                TicketViews.Brief.of(ticket, now,
                        !withInternal && entries.stream().anyMatch(m -> Boolean.TRUE.equals(m.getUnreadForCustomer())
                                && !TicketMessage.AUTHOR_CUSTOMER.equals(m.getAuthorType()))),
                ticket.getContactPhone(), ticket.getDescription(), ticket.getImages(), ticket.getSolution(),
                ticket.getFirstResponseDueAt(), ticket.getFirstResponseAt(),
                ticket.getResolvedAt(), ticket.getClosedAt(), firstResponseMinutes, resolveMinutes,
                ticket.getEscalateNote(), ticket.getEscalatedAt(),
                ticket.getCompensationType(), ticket.getCompensationRef(), ticket.getCompensationNote(),
                ticket.getCompensatedAt(),
                ticket.getSatisfaction(), ticket.getSatisfactionNote(), ticket.getSatisfactionAt(),
                ticket.getSourceType(), slaText(ticket, now),
                timeline, orderView,
                allowedActions(ticket, withInternal), nextStepHint(ticket, withInternal, rateable, reopenable),
                rateable, reopenable);
    }

    private static List<String> allowedActions(Ticket ticket, boolean adminView) {
        List<String> actions = new ArrayList<>();
        if (!adminView) {
            if (TicketFlowPolicy.isSettled(ticket.getStatus())
                    && (ticket.getReopenCount() == null || ticket.getReopenCount() < Ticket.MAX_REOPEN)) {
                actions.add(Ticket.STATUS_OPEN);
            }
            return actions;
        }
        switch (ticket.getStatus()) {
            case Ticket.STATUS_OPEN -> {
                actions.add(Ticket.STATUS_ASSIGNED);
                actions.add(Ticket.STATUS_PROCESSING);
            }
            case Ticket.STATUS_ASSIGNED -> actions.add(Ticket.STATUS_PROCESSING);
            default -> {
            }
        }
        if (!TicketFlowPolicy.isSettled(ticket.getStatus())) {
            actions.add(Ticket.STATUS_RESOLVED);
            actions.add(Ticket.STATUS_CLOSED);
        }
        if (Ticket.STATUS_RESOLVED.equals(ticket.getStatus()) || Ticket.STATUS_CLOSED.equals(ticket.getStatus())) {
            actions.add(Ticket.STATUS_OPEN);
        }
        return actions;
    }

    private static String nextStepHint(Ticket ticket, boolean adminView, boolean rateable, boolean reopenable) {
        if (rateable) {
            return "工单已解决，给这次处理打个分吧（只能评一次）";
        }
        if (adminView && ticket.getAssigneeId() == null) {
            return "先认领这张单，处理中的单不会在别人手里逾期";
        }
        if (adminView && TicketSlaPolicy.isOverdue(ticket, LocalDateTime.now())) {
            return "已超过解决时限，请尽快给出处理结论或改派";
        }
        if (!adminView && reopenable) {
            return "问题还没解决？点「重新打开」继续跟进（每张工单限一次）";
        }
        if (Ticket.STATUS_CLOSED.equals(ticket.getStatus())) {
            return "本次服务已结束，如有新问题请针对订单重新提交工单";
        }
        return adminView ? "回复顾客后记得标记解决，SLA 计时才会停" : "客服每次回复都会同步一条站内信给你";
    }

    /** SLA 一句话口径：详情与列表共用，页面不再自己拼「还剩几分钟」 */
    private static String slaText(Ticket ticket, LocalDateTime now) {
        String state = TicketSlaPolicy.deadlineState(ticket, now);
        Long left = TicketSlaPolicy.minutesLeft(ticket, now);
        return switch (state) {
            case "settled" -> "已结单，时限冻结在结论时刻";
            case "none" -> "未设解决时限";
            case "overdue" -> "已超时 " + human(Math.abs(left == null ? 0 : left));
            case "warning" -> "剩 " + human(left == null ? 0 : left) + "，即将超时";
            default -> "剩 " + human(left == null ? 0 : left);
        };
    }

    private static String human(Long minutes) {
        long m = minutes == null ? 0 : Math.max(0, minutes);
        if (m < 60) {
            return m + " 分钟";
        }
        long hours = m / 60;
        long days = hours / 24;
        return days > 0 ? days + " 天 " + (hours % 24) + " 小时" : hours + " 小时 " + (m % 60) + " 分";
    }

    private Ticket requireOwned(UUID ticketId, UUID userId) {
        if (ticketId == null || userId == null) {
            throw BusinessException.notFound("工单不存在");
        }
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> BusinessException.notFound("工单不存在"));
        if (!userId.equals(ticket.getUserId())) {
            // 别人的工单号拼进 URL：按不存在回答，不承认它存在
            throw BusinessException.notFound("工单不存在");
        }
        return ticket;
    }

    private Ticket loadForAdmin(UUID ticketId, UUID operatorId, boolean supervisor) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> BusinessException.notFound("工单不存在"));
        if (!supervisor && !operatorId.equals(ticket.getAssigneeId())) {
            throw BusinessException.notFound("工单不存在");
        }
        return ticket;
    }

    /** 一屏列表只打一次 IN 查询拿红点，避免逐行 lastAgentReply 造成 N+1 */
    private Set<UUID> unreadReplyIds(List<Ticket> rows) {
        if (rows.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(messageRepository.ticketIdsWithUnreadReply(
                rows.stream().map(Ticket::getId).toList()));
    }

    private TicketSlaRule resolveRule(String category, String priority) {
        return slaRuleRepository.findByCategoryAndPriorityAndEnabled(category, priority, true)
                .orElseGet(() -> slaRuleRepository.findFirstByCategoryAndEnabled(category, true).orElse(null));
    }

    /** 联系电话快照：优先取订单收货号，没有订单就用账号手机号（出网仍按 Masked 口径） */
    private String snapshotPhone(UUID userId, UUID orderId) {
        if (orderId != null) {
            TicketSourceRepository.OrderSnapshot snapshot = sourceRepository.findOrderSnapshot(orderId);
            if (snapshot != null && snapshot.receiverPhone() != null && !snapshot.receiverPhone().isBlank()) {
                return snapshot.receiverPhone();
            }
        }
        return sourceRepository.phoneOf(userId);
    }

    /** 客服显示名：名录里有就用名录里的，没有就退回账号名，派单后时间线不会出现一堆 UUID */
    private String displayName(UUID operatorId, String fallback) {
        return agentRepository.findById(operatorId)
                .map(TicketAgent::getDisplayName)
                .filter(name -> name != null && !name.isBlank())
                .orElseGet(() -> fallback == null || fallback.isBlank() ? "客服" : fallback.trim());
    }

    /**
     * 系统行：状态跃迁与受理动作都留一条可追溯的时间线。
     *
     * @param unread 是否给顾客打未读红点。建单回执不算未读——那行是顾客自己动作产生的，
     *               红点留给「客服真的回我了」这一种情况。
     */
    private TicketMessage systemEntry(UUID ticketId, String content, boolean unread) {
        TicketMessage entry = new TicketMessage();
        entry.setTicketId(ticketId);
        entry.setAuthorType(TicketMessage.AUTHOR_SYSTEM);
        entry.setAuthorName("系统");
        entry.setContent(truncate(Masking.scrubText(content), 2000));
        entry.setInternalNote(false);
        entry.setUnreadForCustomer(unread);
        return messageRepository.save(entry);
    }

    /** 工单号：时间戳 + 4 位随机，唯一索引兜底；冲突概率极低，真撞上了页面重试即可 */
    private static String nextTicketNo(LocalDateTime now) {
        return "T" + NO_STAMP.format(now) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private static String excerpt(String content) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        return text.length() <= 60 ? text : text.substring(0, 59) + "…";
    }

    private static String known(String value, java.util.function.Predicate<String> checker) {
        String trimmed = value == null ? "" : value.trim();
        return checker.test(trimmed) ? trimmed : "";
    }

    private static String deadlineState(String value) {
        String trimmed = value == null ? "" : value.trim();
        return List.of("overdue", "warning", "settled").contains(trimmed) ? trimmed : "";
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
