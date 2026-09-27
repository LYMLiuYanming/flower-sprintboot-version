package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "后台 · 操作日志")
public class AdminAuditController {

    @Autowired
    private AdminAuditService adminAuditService;

    @GetMapping
    public Result<List<AdminAuditLog>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String module) {
        Page<AdminAuditLog> result = adminAuditService.search(keyword, module,
                Pages.of(page, limit, Sort.Direction.DESC, "createdAt"));
        return Result.page(result.getContent(), result.getTotalElements());
    }
}
