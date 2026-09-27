package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/notices")
@Tag(name = "后台 · 公告管理")
public class NoticeAdminController {

    @Autowired
    private NoticeService noticeService;

    @GetMapping
    public Result<List<NoticeView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(required = false) String keyword) {
        Page<Notice> result = (keyword == null || keyword.isBlank())
                ? noticeService.findAll(Pages.of(page, limit, Sort.Direction.DESC, "createdAt"))
                : noticeService.searchByTitle(keyword.trim(), Pages.of(page, limit));
        return Result.page(NoticeView.from(result.getContent()), result.getTotalElements());
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

    @PostMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable UUID id, @RequestParam String status) {
        return noticeService.updateStatus(id, status)
                ? Result.ok("状态更新成功", null)
                : Result.error("状态更新失败");
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
