package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.AdminAuditQueryService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台操作审计（G25/G26）：多条件筛选、CSV 导出与单条详情。
 *
 * <p>读侧一律走 {@link AdminAuditQueryService} 而不是直接返回实体：审计表里存的是原始请求摘要，
 * 只有经过脱敏投影才允许出网。旧的 keyword + module 两个参数保留，页面不必同批替换。
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@Tag(name = "后台 · 操作日志")
public class AdminAuditController {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 导出条数上限与查询侧共用一个常量，避免页面能导出接口取不到的量 */
    private static final int EXPORT_LIMIT = AdminAuditQueryService.EXPORT_LIMIT;

    private final AdminAuditQueryService auditQueryService;

    public AdminAuditController(AdminAuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    /**
     * 组合筛选（G25）：操作人 / 模块 / 动作 / 关键词 / 结果 / 时间区间，任意组合，空即不过滤。
     * outcome 只认 ok / bad——结果码为空的历史记录按成功算，口径在查询里守住，页面不参与判断
     */
    @GetMapping
    public Result<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        AdminAuditQueryService.Filter filter = filterOf(operator, module, action, keyword, outcome, from, to);
        Page<Map<String, Object>> result = auditQueryService.search(filter, Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements())
                .with("pages", result.getTotalPages())
                // 模块条数随列表一起回：筛选框上的计数要和当前时间窗一致，不该再发一次请求
                .with("moduleCounts", auditQueryService.moduleCounts(filter.from(), filter.to()));
    }

    /** 筛选框的数据源：操作人候选 + 动作候选 + 模块条数，页面据此渲染下拉而不是让运营手打 */
    @GetMapping("/facets")
    public Result<Map<String, Object>> facets(@RequestParam(required = false) String module,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to) {
        AdminAuditQueryService.Filter filter =
                filterOf(null, module, null, null, null, from, to);
        return Result.ok(Map.of(
                "operators", auditQueryService.operators(),
                "actions", auditQueryService.actions(module),
                "modules", auditQueryService.moduleCounts(filter.from(), filter.to()),
                "limit", Pages.MAX_SIZE,
                "exportLimit", EXPORT_LIMIT));
    }

    /** 单条详情（G26 展开）：只回脱敏后的请求摘要，口令类字段与手机号在出网前已被替换 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable UUID id) {
        Map<String, Object> row = auditQueryService.detail(id);
        return row == null ? Result.notFound("日志不存在或已被清理") : Result.ok(row);
    }

    /**
     * 导出 CSV（G26）：筛选口径与列表完全一致，带 UTF-8 BOM，Excel 双击即开。
     * 导出的是全量快照，比列表更容易把敏感字段带出内网，所以行数据同样只取脱敏投影
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String operator,
                       @RequestParam(required = false) String module,
                       @RequestParam(required = false) String action,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String outcome,
                       @RequestParam(required = false) String from,
                       @RequestParam(required = false) String to,
                       HttpServletResponse response) throws IOException {
        AdminAuditQueryService.Filter filter = filterOf(operator, module, action, keyword, outcome, from, to);
        List<Map<String, Object>> rows = auditQueryService.exportRows(filter);

        String filename = URLEncoder.encode("audit-logs.csv", StandardCharsets.UTF_8);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        PrintWriter writer = response.getWriter();
        writer.write('\uFEFF');
        writer.println("时间,操作人,模块,动作,方式,目标接口,结果码,结果说明,来源IP,请求摘要（已脱敏）");
        for (Map<String, Object> row : rows) {
            writer.println(String.join(",", List.of(
                    csv(timeOf(row.get("createdAt"))), csv(row.get("operatorName")), csv(row.get("module")),
                    csv(row.get("action")), csv(row.get("method")), csv(row.get("uri")),
                    csv(row.get("resultCode")), csv(row.get("resultMsg")), csv(row.get("ip")),
                    csv(row.get("detail")))));
        }
        if (rows.size() >= EXPORT_LIMIT) {
            writer.println("# 仅导出前 " + EXPORT_LIMIT + " 条，请缩小时间范围或模块后再导");
        }
        writer.flush();
    }

    /**
     * 时间入参既接受 yyyy-MM-dd 也接受带时刻的串：日期一律按整天展开，
     * 否则「到今天」会把当天晚些时候的操作全漏掉
     */
    private static AdminAuditQueryService.Filter filterOf(String operator, String module, String action,
                                                           String keyword, String outcome,
                                                           String from, String to) {
        return new AdminAuditQueryService.Filter(operator, module, action, keyword, outcome,
                parseTime(from, false), parseTime(to, true));
    }

    private static LocalDateTime parseTime(String raw, boolean endOfDay) {
        String value = raw == null ? null : raw.trim();
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            if (value.length() <= 10) {
                LocalDate day = LocalDate.parse(value);
                return endOfDay ? day.atTime(23, 59, 59) : day.atStartOfDay();
            }
            return LocalDateTime.parse(value.replace(' ', 'T'));
        } catch (RuntimeException e) {
            throw new BusinessException("时间格式不正确，应为 yyyy-MM-dd");
        }
    }

    private static String timeOf(Object value) {
        if (value instanceof LocalDateTime time) {
            return time.format(DATE_TIME);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        // 请求摘要里可能带换行，CSV 单元格内的换行会把一条日志拆成多行
        String text = String.valueOf(value).replaceAll("[\\r\\n]+", " ");
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
