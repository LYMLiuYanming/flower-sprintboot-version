package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.AuditSupport;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.TicketDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.liuym.flowerv1springboot.service.TicketService;
import org.liuym.flowerv1springboot.vo.TicketViews;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台工单处理（U15/U16/U26）。
 *
 * <p>通用留痕由 {@code AdminAuditFilter} 覆盖，这里补的是「为什么做」：
 * 认领、改派、状态跃迁、回复都要能在日志里还原成一句人话，出了纠纷才知道是谁定的结论。
 *
 * <p>数据范围（U26）在服务层判定：普通客服的队列查询被强制加上 assignee = 自己，
 * 控制层不接受页面传来的「看谁的单」，越权按 404 回答。
 */
@RestController
@RequestMapping("/api/admin/tickets")
@Tag(name = "后台 · 客服工单")
public class TicketAdminController {

    private final TicketService ticketService;
    private final AdminAuditService auditService;

    public TicketAdminController(TicketService ticketService, AdminAuditService auditService) {
        this.ticketService = ticketService;
        this.auditService = auditService;
    }

    @GetMapping("/dictionary")
    public Result<TicketViews.Dictionary> dictionary(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(ticketService.dictionary());
    }

    @GetMapping("/summary")
    public Result<Map<String, Object>> summary(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(ticketService.queueSummary());
    }

    /** 派单下拉：只列启用的客服，并带上各自在途量，主管才不会把单塞给已经压了 20 张的人 */
    @GetMapping("/agents")
    public Result<List<TicketViews.AgentView>> agents(HttpSession session) {
        CurrentUser.requireAdmin(session);
        return Result.ok(ticketService.assignableAgents());
    }

    /** U15 队列：默认未结单在前 + SLA 剩余时间升序，普通客服只看自己的 */
    @GetMapping
    public Result<List<TicketViews.Brief>> list(@RequestParam(required = false) String status,
                                                @RequestParam(required = false) String category,
                                                @RequestParam(required = false) String priority,
                                                @RequestParam(required = false) UUID assigneeId,
                                                @RequestParam(required = false) UUID orderId,
                                                @RequestParam(required = false) String keyword,
                                                @RequestParam(required = false) String deadlineState,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "20") int limit,
                                                HttpSession session) {
        User operator = CurrentUser.requireAdmin(session);
        boolean supervisor = ticketService.isSupervisor(operator.getId());
        TicketDtos.QueueQuery query = new TicketDtos.QueueQuery(status, category, priority,
                supervisor ? assigneeId : null, orderId, keyword, deadlineState, page, limit);
        Page<TicketViews.Brief> found = ticketService.searchQueue(query, supervisor ? null : operator.getId());
        return Result.page(found.getContent(), found.getTotalElements());
    }

    @GetMapping("/{id}")
    public Result<TicketViews.Detail> detail(@PathVariable UUID id, HttpSession session) {
        User operator = CurrentUser.requireAdmin(session);
        boolean supervisor = ticketService.isSupervisor(operator.getId());
        return Result.ok(ticketService.detailForAdmin(id, operator.getId(), supervisor));
    }

    /** U26 认领：两个人同时点只有一个人拿得到，输的那位收到一句「已经有人在处理了」 */
    @PostMapping("/{id}/claim")
    public Result<Map<String, Object>> claim(@PathVariable UUID id, HttpSession session,
                                             HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        int rows = ticketService.claim(id, operator.getId(), operator.getUsername());
        audit(request, operator, "认领工单", id, "认领成功，状态进入已派单/处理中");
        return Result.ok("已受理这张工单", Map.of("changed", rows));
    }

    /** U26 主管改派 */
    @PostMapping("/{id}/assign")
    public Result<Map<String, Object>> assign(@PathVariable UUID id,
                                              @Valid @RequestBody TicketDtos.AssignForm form,
                                              HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        boolean supervisor = ticketService.isSupervisor(operator.getId());
        if (!supervisor) {
            // 普通客服只能认领，不能把活塞给别人；改派是主管的权限
            return Result.forbidden("只有主管可以改派工单，请先认领自己那张");
        }
        int rows = ticketService.assign(id, form.assigneeId(), null, true, form.note());
        audit(request, operator, "改派工单", id, "改派给 " + form.assigneeId()
                + (form.note() == null ? "" : "｜" + form.note()));
        return Result.ok("已派单", Map.of("changed", rows));
    }

    /** U13 后台跃迁：受理 / 解决 / 关闭 */
    @PostMapping("/{id}/transit")
    public Result<Map<String, Object>> transit(@PathVariable UUID id,
                                               @Valid @RequestBody TicketDtos.TransitForm form,
                                               HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        boolean supervisor = ticketService.isSupervisor(operator.getId());
        int rows = ticketService.adminTransit(id, operator.getId(), operator.getUsername(), supervisor, form);
        audit(request, operator, "工单流转", id, "→ " + form.to()
                + (form.solution() == null ? "" : "｜结论：" + form.solution())
                + (form.reason() == null ? "" : "｜理由：" + form.reason()));
        return Result.ok("状态已更新", Map.of("changed", rows));
    }

    /** U16 客服回复：内部备注不会推站内信，也不会给顾客打红点 */
    @PostMapping("/{id}/reply")
    public Result<Map<String, Object>> reply(@PathVariable UUID id,
                                             @Valid @RequestBody TicketDtos.ReplyForm form,
                                             HttpSession session, HttpServletRequest request) {
        User operator = CurrentUser.requireAdmin(session);
        boolean supervisor = ticketService.isSupervisor(operator.getId());
        int rows = ticketService.adminReply(id, operator.getId(), operator.getUsername(), supervisor, form);
        audit(request, operator, Boolean.TRUE.equals(form.internalNote()) ? "工单内部备注" : "回复工单",
                id, "回复 " + form.content().length() + " 字"
                        + (Boolean.TRUE.equals(form.internalNote()) ? "（顾客不可见）" : ""));
        return Result.ok(Boolean.TRUE.equals(form.internalNote()) ? "内部备注已保存" : "已回复顾客",
                Map.of("changed", rows));
    }

    private void audit(HttpServletRequest request, User operator, String action, Object targetId, String detail) {
        auditService.record(AuditSupport.entry(operator, "客服工单", action, request.getMethod(),
                request.getRequestURI() + " → 工单 " + targetId, detail, 200, action + "成功"));
    }
}
