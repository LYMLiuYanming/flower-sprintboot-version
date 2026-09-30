package org.liuym.flowerv1springboot.common;

/**
 * 敏感字段出网前的统一打码口径（L01）。
 *
 * <p>此前手机号散落在 AccountPolicy.maskPhone、AdminAuditQueryService.mask 与各 VO 里各写一遍，
 * 一旦口径变了（例如从 3+4 改成 3+2）就要改十几处。这里收成一个入口，其余各处按注释迁移。
 *
 * <p>固定规则：手机号 {@code 138****8000}（保留前 3 后 4），地址只留行政区、姓名保留首字，
 * 邮箱保留首字符与域名。长度不足或形状不符合预期的输入一律原样返回——
 * 把脏数据改得更短会让排查更难，而且打码不能成为丢数据的借口。
 */
public final class Masking {

    /** 掩码段：与全站既有页面、审计摘要完全一致的四个星号 */
    public static final String STAR = "****";

    /** 口令在任何出口都不允许出现的形态（序列化前用它做最后兜底判定） */
    private static final String[] SECRET_KEYS = {"password", "passwd", "pwd", "secret", "token", "captcha"};

    private Masking() {
    }

    /** 手机号：138****8000；非 11 位数字原样返回 */
    public static String phone(String raw) {
        String p = trimToNull(raw);
        if (p == null || p.length() != 11 || !allDigits(p)) {
            return raw;
        }
        return p.substring(0, 3) + STAR + p.substring(7);
    }

    /**
     * 收货地址：只留行政区，详址（小区门牌）永不保留。
     *
     * <p>详细地址配合订单号几乎能定位到人，列表页与审计摘要都不需要它。
     * 分隔存在时按段保留、最后一段直接丢弃；整串没有分隔符（最常见的存储形态）时退化成前 6 个字符，
     * 通常正好是「北京市朝阳」这一级，够用且不会把门牌带出去。
     */
    public static String address(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return raw;
        }
        String[] parts = v.split("[\\s/、,，#]+");
        StringBuilder head = new StringBuilder();
        for (int i = 0; i + 1 < parts.length && i < 3; i++) {
            head.append(parts[i]);
        }
        if (head.isEmpty()) {
            head.append(v, 0, Math.min(6, v.length()));
        }
        String prefix = head.length() > 12 ? head.substring(0, 12) : head.toString();
        return prefix + STAR;
    }

    /** 姓名：张三 -> 张*，欧阳修 -> 欧*修；空值给「匿名用户」而不是空串，页面不必各自兜底 */
    public static String name(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return "匿名用户";
        }
        if (v.length() == 1) {
            return v + "*";
        }
        if (v.length() == 2) {
            return v.charAt(0) + "*";
        }
        return v.charAt(0) + "*" + v.charAt(v.length() - 1);
    }

    /** 邮箱：z***@qq.com */
    public static String email(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return raw;
        }
        int at = v.indexOf('@');
        if (at <= 0) {
            return STAR;
        }
        return v.charAt(0) + STAR + v.substring(at);
    }

    /** 证件号：只留前 4 后 2，用于客服核身 */
    public static String idCard(String raw) {
        String v = trimToNull(raw);
        if (v == null || v.length() < 8) {
            return v == null ? null : STAR;
        }
        return v.substring(0, 4) + STAR + v.substring(v.length() - 2);
    }

    /** 任意标识类长串（session token、记住我签名）：留首尾各 4 位够排障用 */
    public static String fingerprint(String raw) {
        String v = trimToNull(raw);
        if (v == null) {
            return null;
        }
        if (v.length() <= 8) {
            return STAR;
        }
        return v.substring(0, 4) + STAR + v.substring(v.length() - 4);
    }

    /**
     * 字段名是否属于「任何形态都不得输出」的口令类：供序列化兜底与日志摘要共用。
     * 用包含判定而不是相等判定，因为 username/passwordHash/old_password 都要拦得住。
     */
    public static boolean isSecretKey(String field) {
        if (field == null) {
            return false;
        }
        String key = field.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
        for (String secret : SECRET_KEYS) {
            if (key.contains(secret)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 自由文本（异常消息、请求摘要）里的手机号统一打码，日志与审计共用。
     * 只处理 11 位连续数字，避免把订单号里的数字片段误伤。
     */
    public static String scrubText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        return PHONE_IN_TEXT.matcher(raw).replaceAll(m -> m.group(1) + STAR + m.group(2));
    }

    private static final java.util.regex.Pattern PHONE_IN_TEXT =
            java.util.regex.Pattern.compile("(?<![0-9])(1[3-9]\\d{2})(\\d{4})(?![0-9])");

    /**
     * 异常的一行摘要（根因类名 + 消息前 200 字），日志与台账都用它，绝不拼堆栈。
     *
     * <p>为什么只要根因：定时任务与外部调用的堆栈前几层几乎都是框架包装层，
     * 真正说明问题的是最里面那个 cause；而消息里最常出现的就是带手机号的业务参数。
     */
    public static String brief(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        // 限制下钻深度，防止循环引用的 cause 链把采集线程转死
        for (int depth = 0; depth < 8 && root.getCause() != null && root.getCause() != root; depth++) {
            root = root.getCause();
        }
        String message = root.getMessage();
        String text = root.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message.trim());
        return text.length() <= 200 ? text : text.substring(0, 199) + "…";
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        return v.isEmpty() ? null : v;
    }

    private static boolean allDigits(String v) {
        for (int i = 0; i < v.length(); i++) {
            if (!Character.isDigit(v.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
