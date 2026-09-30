package org.liuym.flowerv1springboot.common;

import java.util.Locale;

/**
 * 站内信跳转地址白名单（U05）：只允许站内路径，外链与危险协议一律拒绝入库并计数告警。
 *
 * <p>比 {@link SafeUrl} 更严：SafeUrl 允许 http(s) 绝对地址（商品图、banner 外链确实要用），
 * 而站内信的跳转是「点一下就离开站点」，被拿去发钓鱼链接的代价远大于收益。
 * 这里先借 SafeUrl 挡掉引号/尖括号/反斜杠与 {@code https://legit.com@evil.com} 这类解析把戏，
 * 再要求剩下的形态必须是单斜杠开头的站内路径。
 */
public final class MessageTargetPolicy {

    /** 与 user_message.link_url 列宽一致 */
    public static final int MAX_LENGTH = 500;

    /* 越权原因码：落 message_guard_event.reason，后台按码分桶计数 */
    public static final String REASON_PROTOCOL = "blocked-protocol";
    public static final String REASON_EXTERNAL = "external-host";
    public static final String REASON_SHAPE = "malformed";

    /** 危险协议前缀：与小写化后的串比对 */
    private static final String[] BLOCKED_PREFIXES = {"javascript:", "vbscript:", "data:", "file:", "blob:", "about:"};

    private MessageTargetPolicy() {
    }

    /**
     * 校验结果：{@code url} 为规范化后的站内路径（合法时非空），{@code reason} 为越权原因（非法时非空）。
     *
     * <p>两者互斥且都不为空的情形不存在，调用方只需要看 {@code rejected()}。
     */
    public record Target(String url, String reason) {

        public boolean rejected() {
            return reason != null;
        }

        /** 空值不是越权：没填跳转地址是正常情形，不产生告警计数 */
        public boolean empty() {
            return url == null && reason == null;
        }

        public static final Target EMPTY = new Target(null, null);
    }

    /** 检查跳转地址（U05） */
    public static Target check(String raw) {
        if (raw == null || raw.isBlank()) {
            return Target.EMPTY;
        }
        String value = raw.trim();
        if (value.length() > MAX_LENGTH) {
            return new Target(null, REASON_SHAPE);
        }
        String lowered = value.toLowerCase(Locale.ROOT).replace(" ", "");
        for (String prefix : BLOCKED_PREFIXES) {
            if (lowered.startsWith(prefix)) {
                return new Target(null, REASON_PROTOCOL);
            }
        }
        if (lowered.startsWith("//")) {
            // 协议相对 URL 会按当前域之外的任意域解析，等同外链
            return new Target(null, REASON_EXTERNAL);
        }
        if (lowered.contains("://") || lowered.contains("www.")) {
            return new Target(null, REASON_EXTERNAL);
        }
        if (!value.startsWith("/")) {
            return new Target(null, REASON_SHAPE);
        }
        try {
            // 复用全站既有的形状校验：引号、尖括号、反斜杠、"/\\" 开头都在这里被挡
            SafeUrl.requireSafe(value, "消息跳转地址");
        } catch (IllegalArgumentException e) {
            return new Target(null, REASON_SHAPE);
        }
        return new Target(value, null);
    }

    /** 只判不抛：页面渲染前用，越权的地址渲染成纯文本而不是链接 */
    public static boolean isInternal(String raw) {
        Target target = check(raw);
        return !target.rejected() && !target.empty();
    }
}
