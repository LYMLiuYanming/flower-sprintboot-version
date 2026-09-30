package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.repository.AdminAuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 后台操作审计的查询与脱敏（G25/G26）。
 *
 * <p>AdminAuditService 由上一批交付、签名不动，这里只做「读侧」：多条件筛选、逐行脱敏与导出取数。
 * 脱敏放在读侧兜底是因为写入过滤器只掩码口令类字段，手机号这类仍可能是明文——
 * 日志一旦把完整手机号发出去，就再没有收回来的机会。
 */
@Service
@Transactional(readOnly = true)
public class AdminAuditQueryService {

    /** 时间边界：空条件时用极端值占位，避免 null 参数在 PostgreSQL 里推断不出类型 */
    public static final LocalDateTime MIN_TIME = LocalDateTime.of(1970, 1, 1, 0, 0);
    public static final LocalDateTime MAX_TIME = LocalDateTime.of(2999, 12, 31, 23, 59, 59);

    /** 11 位大陆手机号：保留前三后四，中间四位打码 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    /** 兜住过滤器没覆盖到的口令类字段（大小写、驼峰、下划线各种写法） */
    private static final Pattern SECRET = Pattern.compile(
            "(\"[\\w-]*(?:password|passwd|secret|token|captcha|auth)[\\w-]*\"\\s*:\\s*)\"[^\"]*\"",
            Pattern.CASE_INSENSITIVE);

    /** 导出/列表一次最多取这么多条，超出部分要求收窄条件 */
    public static final int EXPORT_LIMIT = 5000;

    private final AdminAuditLogRepository auditLogRepository;

    public AdminAuditQueryService(AdminAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * 筛选意图：任何一项为空即「不过滤」。方向/时间都已在控制器归一好，这里不再判空。
     */
    public record Filter(String operator, String module, String action, String keyword,
                         String outcome, LocalDateTime from, LocalDateTime to) {

        public Filter {
            operator = blankToEmpty(operator);
            module = blankToEmpty(module);
            action = blankToEmpty(action);
            keyword = blankToEmpty(keyword);
            outcome = blankToEmpty(outcome);
            from = from == null ? MIN_TIME : from;
            to = to == null ? MAX_TIME : to;
        }

        private static String blankToEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public Page<Map<String, Object>> search(Filter filter, Pageable pageable) {
        return auditLogRepository.searchAdvanced(filter.operator(), filter.module(), filter.action(),
                filter.keyword(), filter.outcome(), filter.from(), filter.to(), pageable)
                .map(AdminAuditQueryService::rowOf);
    }

    /** 导出取数：不分页但封顶，避免一次把整张审计表拉进内存 */
    public List<Map<String, Object>> exportRows(Filter filter) {
        return auditLogRepository.searchAdvanced(filter.operator(), filter.module(), filter.action(),
                        filter.keyword(), filter.outcome(), filter.from(), filter.to(),
                        org.springframework.data.domain.PageRequest.of(0, EXPORT_LIMIT))
                .getContent().stream().map(AdminAuditQueryService::rowOf).toList();
    }

    /** 单条详情（G26 展开）：同样只回脱敏后的请求摘要 */
    public Map<String, Object> detail(UUID id) {
        return auditLogRepository.findById(id).map(AdminAuditQueryService::rowOf).orElse(null);
    }

    /** 操作人候选：筛选框的下拉数据源 */
    public List<String> operators() {
        return auditLogRepository.distinctOperators();
    }

    /** 模块与其条数：让运营一眼看出该从哪个模块查起 */
    public Map<String, Long> moduleCounts(LocalDateTime from, LocalDateTime to) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : auditLogRepository.countByModule(from == null ? MIN_TIME : from,
                to == null ? MAX_TIME : to)) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts;
    }

    public List<String> actions(String module) {
        return module == null || module.isBlank()
                ? auditLogRepository.distinctActions("") : auditLogRepository.distinctActions(module);
    }

    /**
     * 输出一律走这里：detail 先遮口令再遮手机号，其余字段原样。
     * 直接返回实体的话，未脱敏的请求摘要会跟着 JSON 一起出去
     */
    public static Map<String, Object> rowOf(AdminAuditLog entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", entry.getId());
        row.put("createdAt", entry.getCreatedAt());
        row.put("operatorId", entry.getOperatorId());
        row.put("operatorName", entry.getOperatorName());
        row.put("module", entry.getModule());
        row.put("action", entry.getAction());
        row.put("method", entry.getMethod());
        row.put("uri", entry.getUri());
        row.put("detail", mask(entry.getDetail()));
        row.put("resultCode", entry.getResultCode());
        row.put("resultMsg", entry.getResultMsg());
        row.put("ip", mask(entry.getIp()));
        return row;
    }

    /** 供别的后台页复用：任何要展示给前端的请求摘要都得先过这一关 */
    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String masked = SECRET.matcher(raw).replaceAll("$1\"******\"");
        return PHONE.matcher(masked).replaceAll("$1****$2");
    }
}
