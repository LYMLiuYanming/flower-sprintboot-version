package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.TicketDtos;
import org.liuym.flowerv1springboot.model.User;
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
 * 顾客侧工单（U12/U13/U16/U24）。
 *
 * <p>与 {@code MessageCenterController} 一样挂在 {@code /api/user/**} 下：鉴权拦截器只登记了这个前缀，
 * 清单里的 {@code /api/tickets/**} 不在名单上，直接挂过去会变成未登录可读写。
 *
 * <p>归属判定全部在服务层做条件过滤，控制层只负责把登录态里的 user id 传下去，
 * 不接受页面传来的 userId——否则改一个 URL 参数就能读别人的工单。
 */
@RestController
@RequestMapping("/api/user/tickets")
@Tag(name = "前台 · 客服工单")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 列表 + 详情共用一套字典，页面不再自己写第二份 code→中文 */
    @GetMapping("/dictionary")
    public Result<TicketViews.Dictionary> dictionary() {
        return Result.ok(ticketService.dictionary());
    }

    /** U12 提单页的可选订单 */
    @GetMapping("/selectable-orders")
    public Result<List<Map<String, Object>>> selectableOrders(HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok(ticketService.selectableOrders(user.getId()));
    }

    @GetMapping
    public Result<List<TicketViews.Brief>> list(@RequestParam(required = false) String status,
                                                @RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int limit,
                                                HttpSession session) {
        User user = CurrentUser.require(session);
        Page<TicketViews.Brief> found = ticketService.listForUser(user.getId(), status, page, limit);
        return Result.page(found.getContent(), found.getTotalElements());
    }

    /** U16 详情：打开即清未读红点，返回的时间线已过滤内部备注 */
    @GetMapping("/{id}")
    public Result<TicketViews.Detail> detail(@PathVariable UUID id, HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok(ticketService.detailForUser(id, user.getId()));
    }

    @PostMapping
    public Result<TicketViews.Brief> create(@Valid @RequestBody TicketDtos.CreateForm form, HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok("工单已提交，客服会按承诺时限首次响应",
                ticketService.create(user.getId(), displayName(user), form));
    }

    /** U16 顾客补充情况 */
    @PostMapping("/{id}/reply")
    public Result<Map<String, Object>> reply(@PathVariable UUID id,
                                             @Valid @RequestBody TicketDtos.ReplyForm form,
                                             HttpSession session) {
        User user = CurrentUser.require(session);
        int rows = ticketService.customerReply(id, user.getId(), displayName(user), form);
        return Result.ok("已提交，客服回复会同时给你一条站内信", Map.of("changed", rows));
    }

    /** U13 顾客侧只有「重新打开」，第二次重开由服务层给出「请新建工单」的明确原因 */
    @PostMapping("/{id}/transit")
    public Result<Map<String, Object>> transit(@PathVariable UUID id,
                                               @Valid @RequestBody TicketDtos.TransitForm form,
                                               HttpSession session) {
        User user = CurrentUser.require(session);
        int rows = ticketService.customerTransit(id, user.getId(), displayName(user), form);
        return Result.ok("工单已重新打开", Map.of("changed", rows));
    }

    /** U24 满意度：一次性评分，重复提交返回明确的「已评过」而不是静默成功 */
    @PostMapping("/{id}/rate")
    public Result<Map<String, Object>> rate(@PathVariable UUID id,
                                            @Valid @RequestBody TicketDtos.RateForm form,
                                            HttpSession session) {
        User user = CurrentUser.require(session);
        int rows = ticketService.rate(id, user.getId(), form);
        return Result.ok("感谢评分，处理记录会保留在工单里", Map.of("changed", rows));
    }

    /** 时间线上的署名：没有真名就用账号名，避免出现一串 UUID */
    private static String displayName(User user) {
        String name = user.getFullName();
        return name == null || name.isBlank() ? user.getUsername() : name;
    }
}
