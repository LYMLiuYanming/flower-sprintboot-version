package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 账户域校验策略集中在此：用户名 / 手机号 / 密码强度 / 地址标签 / 券到期与门槛 / 头像生成 / CSV 转义。
 *
 * <p>注册与改密的服务端强校验都走这里，页面脚本只是即时反馈，二者口径必须一致；
 * 登录不做强度校验，避免历史弱口令账号被锁在门外。
 */
public final class AccountPolicy {

    /* ---------- 用户名 ---------- */
    public static final int USERNAME_MIN = 3;
    public static final int USERNAME_MAX = 20;
    /** 允许：中英文、数字、下划线；下划线不在首尾 */
    private static final String USERNAME_PATTERN = "^[\\u4e00-\\u9fa5A-Za-z0-9][\\u4e00-\\u9fa5A-Za-z0-9_]{1,18}[\\u4e00-\\u9fa5A-Za-z0-9]$";

    /* ---------- 手机号 ---------- */
    private static final String PHONE_PATTERN = "^1[3-9]\\d{9}$";

    /* ---------- 密码强度 ---------- */
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 32;
    /** 至少命中的字符类别数：字母 + 数字 */
    public static final int PASSWORD_MIN_CATEGORIES = 2;
    /** 强度达标线（0-4 类）：低于此值判为弱密码并拒绝 */
    public static final int PASSWORD_REJECT_CATEGORIES = 2;

    /** 常见弱口令黑名单：命中直接拒绝，杜绝「123456789」「password」这类 */
    private static final java.util.Set<String> COMMON_PASSWORDS = java.util.Set.of(
            "12345678", "123456789", "1234567890", "88888888", "00000000",
            "password", "passw0rd", "qwerty123", "abc12345", "a1b2c3d4",
            "iloveyou", "admin123", "11111111", "1q2w3e4r", "1qaz2wsx");

    /* ---------- 地址标签 ---------- */
    public static final java.util.List<String> ADDRESS_TAG_PRESETS = java.util.List.of("家", "公司", "学校");
    public static final int ADDRESS_TAG_MAX = 10;

    /* ---------- 券到期提醒 ---------- */
    /** 距到期 ≤ 该天数即视为「即将过期」，券包置顶并打标 */
    public static final int COUPON_EXPIRING_DAYS = 3;

    /* ---------- 账户注销冷静期 ---------- */
    /** 注销申请后保留数据的天数（软删除冷静期），期满由定时任务匿名化 */
    public static final int DELETION_COOLDOWN_DAYS = 7;

    /* ---------- 未付款倒计时 ---------- */
    /** 与 application.properties 的 order.pay-timeout-minutes 同口径，页面倒计时用 */
    public static final int PAY_TIMEOUT_MINUTES = 30;

    private AccountPolicy() {
    }

    /* ================= 用户名 ================= */

    /**
     * 用户名格式校验，返回 null 表示通过，否则为可直接展示给用户的原因。
     * 只做格式判断，重名由 {@code UserService.existsByUsername} 负责。
     */
    public static String usernameError(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (username.isEmpty()) {
            return "请填写用户名";
        }
        if (username.length() < USERNAME_MIN || username.length() > USERNAME_MAX) {
            return "用户名长度需为 " + USERNAME_MIN + "-" + USERNAME_MAX + " 个字符";
        }
        if (!username.matches(USERNAME_PATTERN)) {
            return "用户名仅支持中英文、数字与下划线，且首尾不能为下划线";
        }
        if (username.chars().distinct().count() == 1) {
            return "用户名不能由同一字符重复组成";
        }
        return null;
    }

    /* ================= 手机号 ================= */

    public static boolean isValidPhone(String raw) {
        return raw != null && raw.matches(PHONE_PATTERN);
    }

    public static String phoneError(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "请填写手机号";
        }
        return isValidPhone(raw.trim()) ? null : "手机号格式不正确";
    }

    /**
     * 手机号脱敏：保留前 3 后 4，中间以 **** 代替，用于日志与跨账号展示。
     * 非 11 位原样返回，避免把脏数据改得更难排查。
     *
     * <p>L01 起口径唯一的真源是 {@link Masking#phone(String)}，这里只做转发：
     * 同一个手机号在页面、审计摘要与日志里必须是同一个形状，否则对不上账。
     */
    public static String maskPhone(String phone) {
        return Masking.phone(phone);
    }

    /** 姓名脱敏：张三 -> 张*，欧阳修 -> 欧*修，与评价列表口径一致 */
    public static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "匿名用户";
        }
        String n = name.trim();
        if (n.length() == 1) {
            return n + "**";
        }
        if (n.length() == 2) {
            return n.charAt(0) + "*";
        }
        return n.charAt(0) + "*" + n.charAt(n.length() - 1);
    }

    /* ================= 密码强度 ================= */

    /**
     * 密码强度评估结果。score 为命中的字符类别数（0-4），percent 供强度条展示。
     */
    public record PasswordStrength(int score, int percent, String level, String issue) {
        public boolean acceptable() {
            return issue == null;
        }
    }

    /**
     * 注册 / 改密强度校验：长度 8-32 + 至少两类字符 + 不含用户名 + 不在弱口令黑名单。
     * 不通过时 issue 给出原因，页面据此拒绝提交。
     */
    public static PasswordStrength evaluatePassword(String raw, String username) {
        String password = raw == null ? "" : raw;
        if (password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            return new PasswordStrength(0, Math.min(password.length() * 8, 40), "弱",
                    "密码长度需为 " + PASSWORD_MIN + "-" + PASSWORD_MAX + " 位");
        }
        int categories = countCategories(password);
        int percent = percentFor(password, categories);
        String level = percent >= 80 ? "强" : percent >= 55 ? "中" : "弱";
        String normalized = password.toLowerCase(Locale.ROOT);
        if (categories < PASSWORD_REJECT_CATEGORIES) {
            return new PasswordStrength(categories, Math.min(percent, 40), "弱",
                    "密码需至少包含字母与数字两类字符");
        }
        if (COMMON_PASSWORDS.contains(normalized)) {
            return new PasswordStrength(categories, 40, "弱", "该密码过于常见，请更换");
        }
        if (username != null && !username.isBlank()
                && normalized.contains(username.toLowerCase(Locale.ROOT))) {
            return new PasswordStrength(categories, Math.min(percent, 45), "弱", "密码不能包含用户名");
        }
        return new PasswordStrength(categories, percent, level, null);
    }

    public static String passwordError(String raw, String username) {
        return evaluatePassword(raw, username).issue();
    }

    private static int countCategories(String password) {
        int categories = 0;
        if (password.chars().anyMatch(c -> c >= 'a' && c <= 'z')) {
            categories++;
        }
        if (password.chars().anyMatch(c -> c >= 'A' && c <= 'Z')) {
            categories++;
        }
        if (password.chars().anyMatch(c -> c >= '0' && c <= '9')) {
            categories++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            categories++;
        }
        return categories;
    }

    /** 强度条百分比：类别数打底，长度加分，命中重复/顺序模式扣分，最终夹到 [0,100] */
    private static int percentFor(String password, int categories) {
        int percent = categories * 20;
        if (password.length() >= 12) {
            percent += 20;
        } else if (password.length() >= 10) {
            percent += 10;
        }
        if (hasSequentialRun(password, 4)) {
            percent -= 20;
        }
        if (hasRepeatedRun(password, 4)) {
            percent -= 20;
        }
        return Math.max(0, Math.min(100, percent));
    }

    /** 连续升序/降序字符（如 1234、abcd、dcba）达到 n 个视为规律模式 */
    private static boolean hasSequentialRun(String value, int n) {
        int asc = 1;
        int desc = 1;
        for (int i = 1; i < value.length(); i++) {
            int diff = value.charAt(i) - value.charAt(i - 1);
            asc = diff == 1 ? asc + 1 : 1;
            desc = diff == -1 ? desc + 1 : 1;
            if (asc >= n || desc >= n) {
                return true;
            }
        }
        return false;
    }

    /** 同一字符连续 n 个（如 aaaa、1111）视为弱模式 */
    private static boolean hasRepeatedRun(String value, int n) {
        int run = 1;
        for (int i = 1; i < value.length(); i++) {
            run = value.charAt(i) == value.charAt(i - 1) ? run + 1 : 1;
            if (run >= n) {
                return true;
            }
        }
        return false;
    }

    /* ================= 地址标签 ================= */

    /**
     * 归一化地址标签：去空白、去掉控制字符、限长；空串归一为 null。
     * 家 / 公司 / 学校是预设标签，也允许用户自定义任意短标签。
     */
    public static String normalizeAddressTag(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > ADDRESS_TAG_MAX ? cleaned.substring(0, ADDRESS_TAG_MAX) : cleaned;
    }

    /** 标签是否可直接作为预设 chip 展示 */
    public static boolean isPresetTag(String tag) {
        return tag != null && ADDRESS_TAG_PRESETS.contains(tag.trim());
    }

    /* ================= 优惠券到期与门槛 ================= */

    /** 距到期是否已进入提醒窗口（未过期且剩余 ≤ {@link #COUPON_EXPIRING_DAYS} 天） */
    public static boolean isExpiringSoon(LocalDateTime expireAt, LocalDateTime now) {
        if (expireAt == null || now == null || !expireAt.isAfter(now)) {
            return false;
        }
        return Duration.between(now, expireAt).toDays() <= COUPON_EXPIRING_DAYS;
    }

    /** 距到期剩余天数（向上取整到整天），已过期返回 0 */
    public static long daysToExpire(LocalDateTime expireAt, LocalDateTime now) {
        if (expireAt == null || now == null || !expireAt.isAfter(now)) {
            return 0;
        }
        long seconds = Duration.between(now, expireAt).getSeconds();
        return (seconds + 86399) / 86400;
    }

    /**
     * 满门槛差额：base 还差多少才能用券，达到或无门槛返回 null。
     * 用于券包「再买 X 元可用」提示（D14）。
     */
    public static BigDecimal thresholdGap(BigDecimal threshold, BigDecimal base) {
        if (threshold == null || threshold.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal current = base == null ? BigDecimal.ZERO : base;
        if (current.compareTo(threshold) >= 0) {
            return null;
        }
        return threshold.subtract(current).setScale(2, RoundingMode.HALF_UP);
    }

    /* ================= 头像（D05 统一规则） ================= */

    /**
     * 头像首字：真实姓名优先，退回用户名，再退回「花」。取首码点，兼容增补平面字符。
     */
    public static String avatarInitial(String name) {
        String source = name == null ? "" : name.trim();
        if (source.isEmpty()) {
            return "花";
        }
        int cp = source.codePointAt(0);
        return new String(Character.toChars(cp));
    }

    /**
     * 由名字稳定派生的头像底色（HSL），保证同一用户在页面各处颜色一致。
     * 固定饱和度与亮度，落在商务感的中深色调，避免花哨。
     */
    public static String avatarColor(String name) {
        int hash = 0;
        String source = name == null ? "" : name.trim();
        for (int i = 0; i < source.length(); i++) {
            hash = (hash * 31) + source.charAt(i);
        }
        int hue = Math.floorMod(hash, 360);
        return "hsl(" + hue + ", 38%, 42%)";
    }

    /* ================= 导出（D17） ================= */

    /**
     * CSV 单元格转义：含逗号/引号/换行时用双引号包裹并转义内部引号，
     * 前置制表符防御性避免 Excel 把纯数字串（订单号 / 手机号）自动转成科学计数或日期。
     */
    public static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        boolean risky = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@");
        String escaped = value.replace("\"", "\"\"");
        return risky ? "\"" + escaped + "\"" : escaped;
    }

    public static String csvRow(String... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvCell(cells[i]));
        }
        return sb.toString();
    }

    /* ================= 其它格式化 ================= */

    public static String plainDecimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    public static LocalDate asLocalDate(LocalDateTime value) {
        return value == null ? null : value.toLocalDate();
    }
}
