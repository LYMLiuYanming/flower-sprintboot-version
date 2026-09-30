package org.liuym.flowerv1springboot.common;

import java.util.List;
import java.util.Optional;

/**
 * 鲜花贺卡定制：样式字典与"是否随单附贺卡"的判定。
 * 文案在下单时快照进订单，后续改模板不影响已下单的贺卡。
 */
public final class GreetingCardPolicy {

    private GreetingCardPolicy() {
    }

    /** 留言字数上限：烫金卡片手写排版按 3 行 * 20 字控制 */
    public static final int MAX_MESSAGE = 60;

    public record Style(String code, String name, String note) {
    }

    public static final Style CLASSIC = new Style("classic", "经典烫金", "米白纸纹 + 香槟金边框");
    public static final Style BOTANICAL = new Style("botanical", "清新叶脉", "浅绿晕染 + 细线叶脉");
    public static final Style STARRY = new Style("starry", "浪漫星空", "墨蓝底 + 星点烫银");
    public static final Style INK = new Style("ink", "水墨丹青", "宣纸质感 + 墨色花枝");

    public static final List<Style> STYLES = List.of(CLASSIC, BOTANICAL, STARRY, INK);

    public static Optional<Style> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return STYLES.stream().filter(s -> s.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    /** 任何一项有内容即视为需要贺卡：只填署名也算 */
    public static boolean requested(String recipient, String message, String signature) {
        return notBlank(recipient) || notBlank(message) || notBlank(signature);
    }

    /** 未选样式时回落经典烫金，保证打印端总能拿到合法样式码 */
    public static String normalizeStyle(String code) {
        return find(code).map(Style::code).orElse(CLASSIC.code());
    }

    public static String styleName(String code) {
        return find(code).map(Style::name).orElse(null);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
