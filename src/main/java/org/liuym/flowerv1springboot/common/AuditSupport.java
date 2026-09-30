package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.AdminAuditLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台留痕的补洞工具（L02）。
 *
 * <p>背景：{@code AdminAuditFilter} 已经覆盖了 /api/admin/** 的全部写方法，真正的漏洞不在「有没有记」，
 * 而在「记了什么」——非 JSON 请求（表单、multipart）当年只落一句 {@code [非 JSON 请求体 ...]}，
 * 等于运营改了哪个字段没人知道。这里把参数摘要补上，并且与出网侧用同一套脱敏口径。
 *
 * <p>三条规矩：
 * <ol>
 *   <li>口令/密钥/验证码类字段一律写成 {@code ******}，不判长度也不判内容；</li>
 *   <li>手机号、身份证形状的取值按 {@link Masking} 打码；</li>
 *   <li>不记文件字节，只记文件名与大小——图片内容进审计表等于把数据库当对象存储用。</li>
 * </ol>
 */
public final class AuditSupport {

    /** 单条摘要的长度上限：与过滤器既有口径一致，超出截断 */
    public static final int DETAIL_LIMIT = 1800;

    private AuditSupport() {
    }

    /**
     * 表单/查询参数摘要。
     *
     * @param params 请求参数（一个键可能多值）
     * @param extra  额外说明，可为 null
     */
    public static String formSummary(Map<String, String[]> params, String extra) {
        Map<String, String> flat = new LinkedHashMap<>();
        if (params != null) {
            params.forEach((key, values) -> flat.put(key, values == null || values.length == 0
                    ? "" : String.join(",", values)));
        }
        return summary(flat, extra);
    }

    /** 键值型摘要：Controller 手工补记时用它，例如「批量下架 6 个商品」这种带业务语义的细节 */
    public static String summary(Map<String, ?> values, String extra) {
        StringBuilder out = new StringBuilder();
        if (extra != null && !extra.isBlank()) {
            out.append(extra.trim()).append(' ');
        }
        if (values != null) {
            values.forEach((key, value) -> {
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
                out.append(key).append('=').append(render(key, value));
            });
        }
        return truncate(out.toString().trim());
    }

    /** 单个值按字段名决定怎么落盘 */
    public static String render(String key, Object value) {
        if (Masking.isSecretKey(key)) {
            return "******";
        }
        if (value == null) {
            return "";
        }
        return Masking.scrubText(String.valueOf(value));
    }

    /** 自由文本摘要（Controller 手写一句话时用），已脱敏 + 截断 */
    public static String note(String text) {
        return truncate(Masking.scrubText(text));
    }

    /**
     * 组装一条留痕记录：给「过滤器看不全」的手工场景用（批量操作、异步导出）。
     *
     * @param operator 可为 null（系统内部动作），此时操作人记为「系统」
     */
    public static AdminAuditLog entry(org.liuym.flowerv1springboot.model.User operator, String module, String action,
                                      String method, String uri, String detail, Integer resultCode, String resultMsg) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setOperatorId(operator == null ? null : operator.getId());
        entry.setOperatorName(operator == null ? "系统" : operator.getUsername());
        entry.setModule(module);
        entry.setAction(action);
        entry.setMethod(method == null ? "POST" : method.toUpperCase(java.util.Locale.ROOT));
        entry.setUri(truncate(uri));
        entry.setDetail(truncate(detail));
        entry.setResultCode(resultCode);
        entry.setResultMsg(truncate(resultMsg));
        return entry;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= DETAIL_LIMIT ? value : value.substring(0, DETAIL_LIMIT - 1) + '…';
    }
}
