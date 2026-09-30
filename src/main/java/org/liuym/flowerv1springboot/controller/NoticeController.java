package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.NoticeRepository;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/notices")
@Tag(name = "前台 · 公告")
public class NoticeController {

    @Autowired
    private NoticeService noticeService;

    @Autowired
    private NoticeRepository noticeRepository;

    /**
     * 公告条数据源：只返回「启用 + 已到上线时间 + 未过下线时间」的记录（F11），
     * 置顶优先、其次按实际上线时间倒序。
     */
    @GetMapping("/active")
    public Result<List<NoticeView>> getActiveNotices(@RequestParam(defaultValue = "5") int limit) {
        List<Notice> notices = noticeService.findActiveNotices();
        int size = Math.min(Math.max(limit, 1), Pages.MAX_SIZE);
        return Result.ok(NoticeView.from(notices.size() > size ? notices.subList(0, size) : notices));
    }

    /**
     * 公告详情：浏览数自增，正文已在入库时按白名单清洗。
     * 未上线/已下线的公告对前台不可见，但回传 404 而不是 500，避免泄露后台排期。
     */
    @GetMapping("/{id}")
    public Result<NoticeView> detail(@PathVariable UUID id, HttpSession session) {
        return noticeService.findDisplayable(id)
                .map(notice -> {
                    noticeService.incrementViewCount(id);
                    NoticeView view = NoticeView.from(notice);
                    Result<NoticeView> result = Result.ok(view);
                    User user = CurrentUser.of(session);
                    if (user != null) {
                        // 已读状态给前端用来区分「第一次看」与「回看」，不计入浏览以外的统计口径
                        result.with("readByMe", noticeRepository.countReadBy(id, user.getId()) > 0)
                                .with("readUserCount", notice.getReadUserCount())
                                .with("readTimes", notice.getReadTimes());
                    }
                    return result;
                })
                .orElseGet(() -> Result.notFound("公告不存在或已下线"));
    }

    /**
     * F12 已读上报：同一用户重复打开只累加次数，人数不重复计。
     * 该路径不在拦截器清单里，登录校验在这里自己做，未登录返回 401 业务码。
     */
    @PostMapping("/{id}/read")
    public Result<Object> markRead(@PathVariable UUID id, HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok("已标记阅读", noticeService.markRead(id, user.getId()));
    }

    /** F12 统计：浏览 / 已读人数 / 已读次数（前台公告详情页也会用一次） */
    @GetMapping("/{id}/stat")
    public Result<Object> stat(@PathVariable UUID id) {
        return Result.ok(noticeService.statOf(id));
    }
}
