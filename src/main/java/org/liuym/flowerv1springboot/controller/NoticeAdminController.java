package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeReadStat;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台公告管理：/api/admin/** 由 AuthInterceptor 校验管理员，AdminAuditFilter 自动留痕。
 */
@RestController
@RequestMapping("/api/admin/notices")
@Tag(name = "后台 · 公告管理")
public class NoticeAdminController {

    @Autowired
    private NoticeService noticeService;

    /** status 传空串表示不限；keyword 命中标题 */
    @GetMapping
    public Result<List<NoticeView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "") String status) {
        Page<Notice> result = noticeService.searchAdmin(status, keyword, Pages.of(page, limit, Sort.Direction.DESC, "createdAt"));
        return Result.page(NoticeView.from(result.getContent()), result.getTotalElements())
                .with("displayableCount", noticeService.countDisplayable());
    }

    @GetMapping("/{id}")
    public Result<NoticeView> detail(@PathVariable UUID id) {
        return noticeService.findById(id)
                .map(notice -> Result.ok(NoticeView.from(notice)))
                .orElseGet(() -> Result.notFound("公告不存在"));
    }

    @PostMapping
    public Result<NoticeView> create(@Valid @RequestBody ContentDtos.NoticeForm form) {
        return Result.ok("创建成功", NoticeView.from(noticeService.createByForm(form)));
    }

    @PutMapping("/{id}")
    public Result<NoticeView> update(@PathVariable UUID id, @Valid @RequestBody ContentDtos.NoticeForm form) {
        return Result.ok("更新成功", NoticeView.from(noticeService.updateByForm(id, form)));
    }

    /** 手动上下线：与定时窗口是两套开关，这里是运营的一键停用 */
    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam String status) {
        if (!Notice.STATUS_ACTIVE.equals(status) && !Notice.STATUS_INACTIVE.equals(status)) {
            return Result.error("状态取值不合法");
        }
        return noticeService.updateStatus(id, status)
                ? Result.ok("状态更新成功", null)
                : Result.error("状态更新失败");
    }

    /** F12 已读统计：浏览 / 已读人数 / 已读次数 */
    @GetMapping("/{id}/stats")
    public Result<NoticeReadStat> stats(@PathVariable UUID id) {
        return Result.ok(noticeService.statOf(id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id) {
        return noticeService.deleteById(id) ? Result.ok("删除成功", null) : Result.error("删除失败");
    }

    @GetMapping("/count")
    public Result<Long> count() {
        return Result.ok(noticeService.count());
    }
}
