package org.liuym.flowerv1springboot.common;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 养护提醒文案（U22）：按花材命中第一轮 F15 的知识库文章，命中不了时用通用文案并标注「未定制」。
 *
 * <p>不留空是硬要求：顾客收到的是一条「第 4 天养护提醒」，正文空着比文案普通更糟。
 *
 * <p>命中口径只用花材关键词（article.materials 是逗号串），不做语义匹配：
 * 项目里没有模型也不联网，词表命中是唯一能验收、能解释、能单测的做法。
 */
public final class CareContentPolicy {

    /** 养护提醒的天数口径（U20）：签收后第 2 / 4 / 7 天 */
    public static final int[] REMINDER_DAYS = {2, 4, 7};

    /** 每一天的提醒重点：第 2 天看水、第 4 天剪根换水、第 7 天收尾与抢救 */
    private static final String[] DAY_FOCUS = {
            "检查水位与花枝吸水情况",
            "换水并再剪根 1 厘米，去掉泡水的叶子",
            "看外瓣是否翻卷，及时清理败花保住剩下的花头"
    };

    /** 未命中知识库时的通用文案（U22 要求「不留空」） */
    public static final String GENERIC_TIPS =
            "常规养护即可：隔天换水、每次斜剪根 1-2 厘米、去掉浸泡在水位线以下的叶子，"
                    + "并把花束远离空调出风口、阳光直射与果盘（水果释放的乙烯会加速衰败）。";

    private CareContentPolicy() {
    }

    /**
     * @param customised false 时页面与站内信都要标注「未定制」，运营据此补知识库条目
     * @param articleRef 命中的文章标题，未命中为 null
     */
    public record CareContent(String title, String content, boolean customised, String articleRef) {
    }

    /**
     * @param dayIndex     提醒序号，从 0 开始对应 {@link #REMINDER_DAYS}
     * @param materials    订单行上的花材文本（可空）
     * @param productNames 商品名，用于标题里说清是哪一束
     * @param matchedTitle 命中的知识库文章标题；未命中传 null
     * @param matchedSummary 命中文章的摘要
     */
    public static CareContent build(int dayIndex, String materials, String productNames,
                                    String matchedTitle, String matchedSummary) {
        int day = dayIndex >= 0 && dayIndex < REMINDER_DAYS.length ? REMINDER_DAYS[dayIndex] : dayIndex + 1;
        String focus = dayIndex >= 0 && dayIndex < DAY_FOCUS.length ? DAY_FOCUS[dayIndex] : DAY_FOCUS[DAY_FOCUS.length - 1];
        String flowerLabel = materials == null || materials.isBlank() ? "您的花礼" : materials.trim() + "花礼";
        String title = "养护提醒 · 第 " + day + " 天：" + flowerLabel + focus;
        StringBuilder body = new StringBuilder();
        boolean customised = matchedTitle != null && !matchedTitle.isBlank();
        if (customised) {
            body.append("门店知识库《").append(matchedTitle.trim()).append("》里针对该花材的建议：");
            body.append(matchedSummary == null || matchedSummary.isBlank() ? GENERIC_TIPS : matchedSummary.trim());
            body.append(' ');
        } else {
            // 标注「未定制」而不是悄悄降级：运营在后台能看见哪一批没有专属文案，顾客也看得懂这是通用建议
            body.append("（通用建议 · 该花材暂无定制文案）");
            body.append(GENERIC_TIPS).append(' ');
        }
        body.append(focus).append('。');
        if (productNames != null && !productNames.isBlank()) {
            body.append("涉及花礼：").append(productNames.trim()).append('。');
        }
        return new CareContent(title, body.toString(), customised, customised ? matchedTitle.trim() : null);
    }

    /**
     * 花材是否命中文章 materials 关键词：双向包含判定。
     *
     * <p>只做小写与去空白，不做分词：花材名本身就是短词（玫瑰、康乃馨），
     * 分词反而会把「香槟玫瑰」切成「香槟」+「玫瑰」导致误命中其它花材的文案。
     */
    public static boolean matches(String materials, String articleMaterials) {
        if (materials == null || articleMaterials == null) {
            return false;
        }
        String hay = normalize(materials);
        if (hay.isEmpty()) {
            return false;
        }
        for (String keyword : articleMaterials.split("[,，、/;；\\s]+")) {
            String needle = normalize(keyword);
            if (needle.length() >= 2 && (hay.contains(needle) || needle.contains(hay) && hay.length() >= 2)) {
                return true;
            }
        }
        return false;
    }

    /** 第 N 天提醒的投递时刻：以签收时间为锚点，落在上午 10 点，避开免打扰窗口 */
    public static LocalDateTime dueAtFor(LocalDateTime anchor, int dayIndex) {
        int day = dayIndex >= 0 && dayIndex < REMINDER_DAYS.length ? REMINDER_DAYS[dayIndex] : dayIndex + 1;
        return anchor.toLocalDate().plusDays(day).atTime(10, 0);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    }
}
