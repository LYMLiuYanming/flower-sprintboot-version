package org.liuym.flowerv1springboot.common;

import java.util.Locale;
import java.util.Set;

/**
 * 后台账号处置策略（G17）：禁用/锁定必须带原因，启用则不强制。
 * 单独成类的原因有两个：
 * 1）原因文案要同时出现在接口校验、审计摘要和列表提示里，规则写两处必然漂移；
 * 2）原因最终落在 admin_audit_log.detail 的 JSON 里（不新增实体列），入库前必须先归一化，
 *    否则换行与控制字符会把请求摘要撑成一团，列表页再也读不出干净的原因。
 *
 * @param status 目标状态：active / inactive / locked
 * @param reason 处置原因（启用时可为空）
 */
public record UserDispositionPolicy(String status, String reason) {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";
    public static final String STATUS_LOCKED = "locked";

    private static final Set<String> STATUSES = Set.of(STATUS_ACTIVE, STATUS_INACTIVE, STATUS_LOCKED);

    /** 原因太短（如「1」）等于没写，太长会把审计摘要挤爆 */
    private static final int REASON_MIN = 2;
    private static final int REASON_MAX = 200;

    public static UserDispositionPolicy of(String status, String reason) {
        return new UserDispositionPolicy(
                status == null ? null : status.trim().toLowerCase(Locale.ROOT),
                normalizeReason(reason));
    }

    /** 校验并返回自身，便于控制器串式调用：policy.requireValid().reason() */
    public UserDispositionPolicy requireValid() {
        if (status == null || !STATUSES.contains(status)) {
            throw new BusinessException("账号状态取值不合法");
        }
        if (needsReason() && reason == null) {
            throw new BusinessException(actionLabel() + "必须填写原因，至少 " + REASON_MIN + " 个字");
        }
        if (reason != null && reason.length() < REASON_MIN) {
            throw new BusinessException("原因太短，请写到 " + REASON_MIN + " 个字以上");
        }
        return this;
    }

    /** 停用与锁定要可追溯，启用只是解除限制不必说明理由 */
    public boolean needsReason() {
        return !STATUS_ACTIVE.equals(status);
    }

    /** 状态中文名：列表徽标、提示语与审计摘要共用一份口径 */
    public String actionLabel() {
        if (status == null) {
            return "处置";
        }
        return switch (status) {
            case STATUS_ACTIVE -> "启用";
            case STATUS_LOCKED -> "锁定";
            default -> "禁用";
        };
    }

    public static String labelOf(String status) {
        return UserDispositionPolicy.of(status, null).actionLabel();
    }

    /**
     * 写进审计摘要的一句话：只带状态与原因，不带任何口令字段
     */
    public String describe(String targetName) {
        StringBuilder sb = new StringBuilder(actionLabel()).append('「')
                .append(targetName == null || targetName.isBlank() ? "未知账号" : targetName).append('」');
        if (reason != null) {
            sb.append("：").append(reason);
        }
        return sb.toString();
    }

    /** 换行与控制字符压成空格，超长截断；空串归一为 null，好让 needsReason 判定不被空格骗过 */
    private static String normalizeReason(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\r\\n\\t\\u0000-\\u001F]", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > REASON_MAX ? cleaned.substring(0, REASON_MAX) : cleaned;
    }
}
