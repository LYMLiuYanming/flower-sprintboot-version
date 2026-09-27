package org.liuym.flowerv1springboot.common;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 图片/链接类字段的入库校验：拒绝 javascript:、data:、vbscript: 等协议注入
 */
public final class SafeUrl {

    private static final Pattern ALLOWED = Pattern.compile(
            "^(?:/(?!/)|https?://[\\w.-]+(?::\\d+)?/?|\\?|#)[^\\s\"'<>]*$",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> BLOCKED_SCHEMES = Set.of("javascript", "data", "vbscript", "file", "blob");

    private SafeUrl() {
    }

    /**
     * 逗号分隔的多图字段：逐段校验协议后重新拼接，非法段直接拒绝
     *
     * @return 规范化后的字符串；value 为空时返回 null
     */
    public static String requireSafeList(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split("[,，]");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(requireSafe(part, fieldLabel));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 只判定不抛错：富文本清洗中非法链接只丢弃 href，不应让整段内容保存失败
     */
    public static boolean isSafe(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            requireSafe(value, "链接");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @return 规范化后的安全 URL；value 为空时返回 null
     * @throws IllegalArgumentException 协议不在白名单内
     */
    public static String requireSafe(String value, String fieldLabel) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase().replace(" ", "");
        for (String scheme : BLOCKED_SCHEMES) {
            if (lower.startsWith(scheme + ":")) {
                throw new IllegalArgumentException(fieldLabel + "不支持该协议：" + scheme);
            }
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldLabel + "需为站内路径或 http(s) 链接");
        }
        // https://legit.com@evil.com 与 https://evil.com\.legit.com 都会被浏览器解析成外部站点
        if (trimmed.contains("://") && (trimmed.contains("@") || trimmed.contains("\\"))) {
            throw new IllegalArgumentException(fieldLabel + "需为站内路径或 http(s) 链接");
        }
        return trimmed;
    }
}
