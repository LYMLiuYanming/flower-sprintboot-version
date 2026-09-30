package org.liuym.flowerv1springboot.common;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模板插值（U07）：{@code {slot}} 占位替换，缺任一必填槽位时<b>整条不落库</b>。
 *
 * <p>为什么不做「缺了就留空」：订单号缺失时发出去的是「订单  已发货」，
 * 顾客看到只会以为系统出错，比收不到通知更难解释，宁可不发。
 *
 * <p>模板里用到、但 varKeys 没声明、事件方也没传值的槽位记为 {@code unknown}：
 * 它不阻断发送（替换成空串），但后台会提示「模板里还有个 {xxx} 没人填」，
 * 免得花括号带着花括号出去被顾客当成 bug。
 */
public final class MessageRenderPolicy {

    /** 占位语法：{name}，name 允许字母、数字、下划线、连字符 */
    private static final Pattern SLOT = Pattern.compile("\\{([A-Za-z0-9_-]{1,40})}");

    /** 单个变量值长度上限：防止有人把整段正文塞进一个槽位把标题撑爆 */
    public static final int VALUE_MAX = 500;

    /** 标题与正文的落库长度上限，与 user_message 的列宽一致 */
    public static final int TITLE_MAX = 200;
    public static final int CONTENT_MAX = 4000;

    private MessageRenderPolicy() {
    }

    /**
     * @param ok      能否落库
     * @param missing 必填但缺值的槽位（ok=false 时非空）
     * @param unknown 模板用到、但既没声明也没传值的槽位
     * @param reason  给人看的失败原因
     */
    public record Rendered(boolean ok, String title, String content,
                           List<String> missing, List<String> unknown, String reason) {

        static Rendered fail(String reason, List<String> missing) {
            return new Rendered(false, null, null, missing, List.of(), reason);
        }
    }

    /** 扫出模板里用到的全部槽位 */
    public static Set<String> slotsOf(String... templates) {
        Set<String> slots = new LinkedHashSet<>();
        if (templates == null) {
            return slots;
        }
        for (String tpl : templates) {
            if (tpl == null || tpl.isBlank()) {
                continue;
            }
            Matcher matcher = SLOT.matcher(tpl);
            while (matcher.find()) {
                slots.add(matcher.group(1));
            }
        }
        return slots;
    }

    /**
     * @param required 模板声明的必填槽位（message_template.var_keys 拆出来的列表）
     * @param vars     变量值；null 与全空白字符串都算缺值
     */
    public static Rendered render(String titleTpl, String contentTpl, List<String> required, Map<String, ?> vars) {
        if (titleTpl == null || titleTpl.isBlank()) {
            return Rendered.fail("模板标题为空，不予发送", List.of());
        }
        Set<String> requiredSet = new LinkedHashSet<>();
        if (required != null) {
            for (String key : required) {
                if (key != null && !key.isBlank()) {
                    requiredSet.add(key.trim());
                }
            }
        }
        Map<String, ?> safeVars = vars == null ? Map.of() : vars;

        List<String> missing = new ArrayList<>();
        for (String key : requiredSet) {
            if (isBlankValue(safeVars.get(key))) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return Rendered.fail("模板必填变量缺失：" + String.join("、", missing), missing);
        }

        Set<String> used = slotsOf(titleTpl, contentTpl);
        List<String> unknown = used.stream()
                .filter(key -> !requiredSet.contains(key))
                .filter(key -> isBlankValue(safeVars.get(key)))
                .toList();

        String title = truncate(fill(titleTpl, safeVars), TITLE_MAX);
        String content = contentTpl == null ? null : truncate(fill(contentTpl, safeVars), CONTENT_MAX);
        if (title == null || title.isBlank() || (content != null && content.isBlank())) {
            return Rendered.fail("模板渲染后文案为空，不予发送", List.of());
        }
        return new Rendered(true, title, content, List.of(), unknown, null);
    }

    private static boolean isBlankValue(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static String fill(String template, Map<String, ?> vars) {
        Matcher matcher = SLOT.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = vars.get(matcher.group(1));
            String text = value == null ? "" : truncate(String.valueOf(value), VALUE_MAX);
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(out);
        return out.toString().trim();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }
}
