package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Notice;
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

    @GetMapping("/active")
    public Result<List<NoticeView>> getActiveNotices(@RequestParam(defaultValue = "5") int limit) {
        List<Notice> notices = noticeService.findActiveNotices();
        int size = Math.min(Math.max(limit, 1), Pages.MAX_SIZE);
        return Result.ok(NoticeView.from(notices.size() > size ? notices.subList(0, size) : notices));
    }

    /**
     * 公告详情：查看数自增，正文已在入库时过滤脚本
     */
    @GetMapping("/{id}")
    public Result<NoticeView> detail(@PathVariable UUID id) {
        return noticeService.findById(id)
                .filter(n -> Notice.STATUS_ACTIVE.equals(n.getStatus()))
                .map(n -> {
                    noticeService.incrementViewCount(id);
                    return Result.ok(NoticeView.from(n));
                })
                .orElseGet(() -> Result.notFound("公告不存在或已下线"));
    }
}
