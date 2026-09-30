package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.NotificationDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.MessageCenterService;
import org.liuym.flowerv1springboot.vo.NotificationViews;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 顾客侧消息中心（U03/U04/U08）。
 *
 * <p>路径挂在 {@code /api/user/**} 而不是清单里写的 {@code /api/messages/**}：
 * 鉴权拦截器只登记了前者，新前缀不在名单里就会变成未登录也能读的接口，
 * 而 {@code WebMvcConfig} 归主智能体维护，本轮不动它。
 */
@RestController
@RequestMapping("/api/user/messages")
@Tag(name = "前台 · 消息中心")
public class MessageCenterController {

    private final MessageCenterService messageCenterService;

    public MessageCenterController(MessageCenterService messageCenterService) {
        this.messageCenterService = messageCenterService;
    }

    /** U03 角标：30 秒轮询的最小开销版本，只回一个数 */
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(HttpSession session) {
        User user = CurrentUser.of(session);
        return Result.ok(user == null ? 0L : messageCenterService.unreadCount(user.getId()));
    }

    /** U03 铃铛下拉：未读数 + 最近 5 条 + 分类计数 */
    @GetMapping("/badge")
    public Result<NotificationViews.UnreadBadge> badge(HttpSession session) {
        User user = CurrentUser.of(session);
        if (user == null) {
            return Result.ok(new NotificationViews.UnreadBadge(0, List.of(), List.of()));
        }
        return Result.ok(messageCenterService.unreadBadge(user.getId()));
    }

    /** U04 列表 */
    @GetMapping
    public Result<List<NotificationViews.MessageView>> list(@RequestParam(required = false) String category,
                                                            @RequestParam(defaultValue = "false") boolean unread,
                                                            @RequestParam(defaultValue = "1") int page,
                                                            @RequestParam(defaultValue = "10") int limit,
                                                            HttpSession session) {
        User user = CurrentUser.require(session);
        Page<NotificationViews.MessageView> found =
                messageCenterService.listForUser(user.getId(), category, unread, page, limit);
        return Result.page(found.getContent(), found.getTotalElements());
    }

    /** U04 单条已读：重复调用返回 0 行也报成功，页面要的是「现在已读」而不是「我这次改到了」 */
    @PostMapping("/{id}/read")
    public Result<Map<String, Object>> markRead(@PathVariable UUID id, HttpSession session) {
        User user = CurrentUser.require(session);
        int rows = messageCenterService.markRead(id, user.getId());
        return Result.ok(Map.of("changed", rows, "unread", messageCenterService.unreadCount(user.getId())));
    }

    /** U04 全部已读：category 传空表示不限类别 */
    @PostMapping("/read-all")
    public Result<Map<String, Object>> markAllRead(@RequestParam(required = false) String category,
                                                   HttpSession session) {
        User user = CurrentUser.require(session);
        int rows = messageCenterService.markAllRead(user.getId(), category);
        return Result.ok("已将 " + rows + " 条消息标记为已读",
                Map.of("changed", rows, "unread", messageCenterService.unreadCount(user.getId())));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id, HttpSession session) {
        User user = CurrentUser.require(session);
        messageCenterService.deleteOwn(id, user.getId());
        return Result.ok("已删除", null);
    }

    /** U08 偏好矩阵 */
    @GetMapping("/preferences")
    public Result<NotificationViews.PreferenceView> preferences(HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok(messageCenterService.preferences(user.getId()));
    }

    /** U08 保存整张矩阵：一次提交全部开关，避免十二个请求各成功一半 */
    @PostMapping("/preferences")
    public Result<NotificationViews.PreferenceView> savePreferences(@Valid @RequestBody NotificationDtos.PrefMatrixForm form,
                                                                    HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok("通知偏好已保存", messageCenterService.savePreferences(user.getId(), form));
    }

    /** U23 一键退订营销：退订与重新订阅共用这个入口，页面不用再找整张矩阵 */
    @PostMapping("/unsubscribe-marketing")
    public Result<NotificationViews.PreferenceView> unsubscribeMarketing(@RequestParam boolean unsubscribe,
                                                                        HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok(unsubscribe ? "已退订营销活动消息" : "已重新订阅营销活动消息",
                messageCenterService.unsubscribeMarketing(user.getId(), unsubscribe));
    }
}
