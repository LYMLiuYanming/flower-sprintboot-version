package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.UserMessage;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;
import java.util.Map;

/**
 * U 组的页面路由（U04/U08/U15/U16 + 后台消息页）。
 *
 * <p>自己声明而不并进 {@code AdminController} / {@code UserCenterController} 那张路由表：
 * 本轮多组并行施工，挤同一份文件会互相覆盖（第一轮已有先例，见 SystemCheckController 的说明）。
 * 路径都在拦截器已登记的 {@code /user/**} 与 {@code /admin/**} 下，鉴权与管理员校验照常生效。
 */
@Controller
public class NotificationPageController {

    /** 类别 tab 的顺序：与偏好矩阵一致，服务端给中文，页面不留第二份 code→文案映射 */
    private static final List<Map<String, String>> CATEGORY_TABS = List.of(
            UserMessage.CATEGORY_TRADE, UserMessage.CATEGORY_MARKETING,
            UserMessage.CATEGORY_SYSTEM, UserMessage.CATEGORY_TICKET)
            .stream()
            .map(code -> Map.of("code", code, "label", NotificationViews.categoryLabel(code)))
            .toList();

    /** U04 消息中心：列表 + U08 订阅偏好开关在同一页，退订不需要另开一张页 */
    @GetMapping("/user/messages")
    public String messages(Model model) {
        model.addAttribute("categories", CATEGORY_TABS);
        return "user/messages";
    }

    /** U16 我的工单列表 */
    @GetMapping("/user/tickets")
    public String tickets(Model model) {
        model.addAttribute("categories", CATEGORY_TABS);
        return "user/tickets";
    }

    /** U16 工单详情：站内信里的跳转就落在这里，列表与详情共用一张页面 */
    @GetMapping("/user/tickets/{id}")
    public String ticketDetail(@PathVariable String id, Model model) {
        model.addAttribute("ticketId", id);
        model.addAttribute("categories", CATEGORY_TABS);
        return "user/tickets";
    }

    /** U15 后台工单队列 */
    @GetMapping("/admin/ticket-list")
    public String adminTickets(HttpSession session, Model model) {
        CurrentUser.requireAdmin(session);
        model.addAttribute("operator", CurrentUser.require(session).getUsername());
        return "admin/ticket-list";
    }

    /** U07/U23 后台消息模板与发送记录 */
    @GetMapping("/admin/message-list")
    public String adminMessages(HttpSession session, Model model) {
        CurrentUser.requireAdmin(session);
        model.addAttribute("operator", CurrentUser.require(session).getUsername());
        return "admin/message-list";
    }
}
