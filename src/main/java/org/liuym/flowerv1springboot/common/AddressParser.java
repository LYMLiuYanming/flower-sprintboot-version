package org.liuym.flowerv1springboot.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 收货地址「智能识别」：把用户粘贴的一段自由文本（快递单式写法）拆成 姓名 / 电话 / 省 / 市 / 区 / 详址。
 *
 * <p>识别顺序即优先级：先摘手机号（唯一强特征），再剥显式字段标签，然后对 省 / 市 / 区 逐级做
 * 「全文定位 + 命中前的短中文段认领为姓名」的贪心扫描，剩下的连续片段作为详细地址。
 * 识别不到的字段留空交回用户补填，绝不猜测编造。
 */
public final class AddressParser {

    /** 手机号：11 位、1[3-9] 开头，允许中间有至多 2 处空格或连字符（快递单常见写法） */
    private static final Pattern PHONE = Pattern.compile("(?<![\\d-])1[3-9]\\d(?:[ -]?\\d){8}(?![\\d-])");

    private static final Pattern LABELED_NAME = Pattern.compile(
            "(?:收货人|收货者|收件人|联系人|姓名|名字)\\s*[:：是为]?\\s*([\\u4e00-\\u9fa5·]{2,8})");
    private static final Pattern LABELED_PHONE = Pattern.compile(
            "(?:手机|电话|联系电话|手机号|联系方式|tel|phone)\\s*[:：是为]?\\s*(1[3-9][\\d -]{9,13})",
            Pattern.CASE_INSENSITIVE);

    /** 行政区划：省/直辖市/自治区、地级市/盟/州、区县旗。均用 find() 全文定位，故不锚定行首 */
    private static final Pattern PROVINCE = Pattern.compile(
            "(北京|上海|天津|重庆|河北|山西|辽宁|吉林|黑龙江|江苏|浙江|安徽|福建|江西|山东|河南|湖北|湖南"
                    + "|广东|海南|四川|贵州|云南|陕西|甘肃|青海|台湾|内蒙古|广西|西藏|宁夏|新疆|香港|澳门)"
                    + "(省|市|自治区|特别行政区)");
    private static final Pattern CITY = Pattern.compile("([\\u4e00-\\u9fa5]{2,8}?(?:市|盟|地区|自治州|林区))");
    private static final Pattern DISTRICT = Pattern.compile("([\\u4e00-\\u9fa5]{2,8}?(?:区|县|旗|自治县))");

    /** 可作姓名的短中文段（2-4 字），用于把「省/市命中之前的前缀」认领为收货人 */
    private static final Pattern NAME_TOKEN = Pattern.compile("^[\\u4e00-\\u9fa5·]{2,4}$");

    private static final java.util.Set<String> MUNICIPALITIES =
            java.util.Set.of("北京市", "上海市", "天津市", "重庆市");

    private AddressParser() {
    }

    /**
     * 解析结果。字段为空表示未识别出，由页面引导用户补填。
     */
    public record Parsed(String receiverName, String receiverPhone, String province,
                         String city, String district, String detail) {

        /** 已识别出的字段数，用于前端提示「识别到 N 项，请核对」 */
        public int filledCount() {
            int n = 0;
            if (notBlank(receiverName)) {
                n++;
            }
            if (notBlank(receiverPhone)) {
                n++;
            }
            if (notBlank(province)) {
                n++;
            }
            if (notBlank(city)) {
                n++;
            }
            if (notBlank(district)) {
                n++;
            }
            if (notBlank(detail)) {
                n++;
            }
            return n;
        }
    }

    public static Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Parsed(null, null, null, null, null, null);
        }
        // 统一各种分隔符（全/半角逗号、顿号、制表符、换行、竖线）为单空格，便于按段处理
        String text = raw.replace('　', ' ')
                .replaceAll("[,，、;；\\r\\n\\t|]+", " ")
                .replaceAll("\\s+", " ")
                .trim();

        /* ---------- 手机号：优先带标签的，其次任意 11 位强特征串 ---------- */
        String phone = null;
        Matcher labeledPhone = LABELED_PHONE.matcher(text);
        if (labeledPhone.find()) {
            phone = digitsOnly(labeledPhone.group(1));
        }
        String leftover = text;
        Matcher plainPhone = PHONE.matcher(leftover);
        if (plainPhone.find()) {
            if (phone == null) {
                phone = digitsOnly(plainPhone.group());
            }
            leftover = leftover.substring(0, plainPhone.start()) + " " + leftover.substring(plainPhone.end());
        }
        leftover = LABELED_PHONE.matcher(leftover).replaceAll(" ").trim();

        /* ---------- 显式姓名标签 ---------- */
        String name = null;
        Matcher labeledName = LABELED_NAME.matcher(leftover);
        if (labeledName.find()) {
            name = labeledName.group(1);
            leftover = labeledName.replaceAll(" ");
        }
        leftover = leftover.replaceAll("\\s+", " ").trim();

        /* ---------- 省 / 市 / 区贪心扫描：命中前若是一段短中文，认领为姓名 ---------- */
        String province = null;
        String city = null;
        String district = null;

        Matcher pm = PROVINCE.matcher(leftover);
        String rest = leftover;
        if (pm.find()) {
            name = claimName(name, rest.substring(0, pm.start()));
            province = pm.group();
            rest = rest.substring(pm.end()).trim();
        }
        Matcher cm = CITY.matcher(rest);
        if (cm.find()) {
            if (province == null) {
                name = claimName(name, rest.substring(0, cm.start()));
            }
            city = cm.group();
            rest = rest.substring(cm.end()).trim();
        } else if (MUNICIPALITIES.contains(province)) {
            // 直辖市：省即市，市段与省同值，详址直接从区开始
            city = province;
        }
        Matcher dm = DISTRICT.matcher(rest);
        if (dm.find()) {
            district = dm.group();
            rest = rest.substring(dm.end()).trim();
        }

        // 兜底猜姓名：既无标签、省/市前缀也没认领到时，取原文「电话之前的最后一段 2-4 字中文」
        if (name == null) {
            name = guessName(text, phone);
        }

        String detail = normalizeDetail(rest);
        return new Parsed(name, phone, province, city, district, detail);
    }

    /** 已有姓名则保留；否则当 given 是干净的 2-4 字中文段时认领为姓名 */
    private static String claimName(String current, String given) {
        if (current != null) {
            return current;
        }
        String token = given == null ? "" : given.trim();
        return NAME_TOKEN.matcher(token).matches() ? token : null;
    }

    /**
     * 从「姓名 + 电话 + 地址」紧凑写法里取姓名：电话之前的最后一段 2-4 个汉字。
     * 识别不到时返回 null，不硬凑。
     */
    private static String guessName(String text, String phone) {
        if (phone == null) {
            return null;
        }
        Matcher m = Pattern.compile("([\\u4e00-\\u9fa5]{2,4})[^\\u4e00-\\u9fa5]*1[3-9]\\d").matcher(text);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        return found;
    }

    private static String normalizeDetail(String leftover) {
        String detail = leftover == null ? "" : leftover.replaceAll("\\s+", " ").trim();
        detail = detail.replaceAll("^(详细地址|地址|详址|街道|收件地址)\\s*[:：是为]?\\s*", "").trim();
        return detail.isEmpty() ? null : detail;
    }

    private static String digitsOnly(String value) {
        return value == null ? null : value.replaceAll("[^0-9]", "");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
