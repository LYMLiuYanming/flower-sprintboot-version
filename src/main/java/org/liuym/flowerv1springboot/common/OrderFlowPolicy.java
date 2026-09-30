package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.OrderStatus;
import org.springframework.data.domain.Sort;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单履约前端的口径字典（C01/C03/C06/C07/C08/C18/C19/C26）。
 *
 * <p>取消原因、退款原因、状态分组、排序白名单、倒计时阈值与自动确认口径都收敛在这里，
 * 页面与后端共用一份，避免出现「页面写着 15 天、后端按 7 天跑」这类两套账。
 */
public final class OrderFlowPolicy {

    private OrderFlowPolicy() {
    }

    /** 原因文案落库列宽（order.cancel_reason / order.refund_reason 都是 varchar(200)） */
    public static final int REASON_MAX = 200;

    /** 自由补充说明的字数上限 */
    public static final int DETAIL_MAX = 120;

    /** 支付超时前的提醒窗口（C08）：只剩 5 分钟时详情页转为醒目提示 */
    public static final int PAY_WARN_SECONDS = 5 * 60;

    /** 发货后自动确认收货的天数（C18） */
    public static final int AUTO_RECEIVE_DAYS = 15;

    /** 签收/完成后引导评价的天数（C26） */
    public static final int REVIEW_GUIDE_DAYS = 3;

    /** 引导评价的入口：详情页的评价区锚点，复用既有评价流程 */
    public static final String REVIEW_TARGET = "#review";

    /** 关键词长度上限：LIKE 前置通配无法走索引，过长的串只会拖慢列表 */
    public static final int KEYWORD_MAX = 40;

    /** 一轮自动确认收货最多处理多少单，避免高峰把整表扫进内存 */
    public static final int AUTO_RECEIVE_BATCH = 50;

    /** 原因预设：code 只做埋点与筛选，label 与 text 会拼成落库文案 */
    public record Reason(String code, String label, String text) {
    }

    /** 取消原因预设（C06）：只放用户真会选的理由，选项太多等于没有选项 */
    public static final List<Reason> CANCEL_REASONS = List.of(
            new Reason("not_need", "暂时不需要了", "暂时不需要了"),
            new Reason("wrong_pick", "选错了/想重新搭配", "选错了，想重新搭配"),
            new Reason("address", "收货信息填错", "收货信息填写有误"),
            new Reason("price", "价格或优惠不满意", "价格或优惠不合适"),
            new Reason("too_late", "配送时间赶不上", "送达时间赶不上使用场景"),
            new Reason("paid_later", "已在线下门店购买", "已在门店现场购买"),
            new Reason("other", "其他原因", "其他原因"));

    /** 退款原因预设（C19）：售后场景要能直接对上花材与配送问题，方便门店判定 */
    public static final List<Reason> REFUND_REASONS = List.of(
            new Reason("withered", "花材不新鲜/有损伤", "花材不新鲜或有损伤"),
            new Reason("wrong_item", "与订购款式不符", "收到的花礼与订购款式不符"),
            new Reason("late", "送达严重超时", "送达时间严重晚于约定时段"),
            new Reason("missing", "缺件或少送", "有花礼缺少或未送达"),
            new Reason("card", "贺卡与留言有误", "贺卡内容或署名有误"),
            new Reason("not_arrived", "至今未收到花礼", "至今未收到花礼"),
            new Reason("other", "其他原因", "其他原因"));

    /**
     * 取消/退款原因归一（C06/C07）：预设 code 命中就用预设文案，否则退回用户自己填的文本，
     * 两者都有时拼成「预设 · 补充」，落库一列就能同时满足筛选与还原现场。
     */
    public static String reasonText(List<Reason> presets, String code, String detail, String fallback) {
        String preset = presets.stream()
                .filter(r -> r.code().equalsIgnoreCase(trim(code)))
                .map(Reason::text)
                .findFirst()
                .orElse(null);
        String extra = trim(detail);
        String merged;
        if (preset != null && !extra.isEmpty()) {
            merged = preset + " · " + extra;
        } else if (preset != null) {
            merged = preset;
        } else if (!extra.isEmpty()) {
            merged = extra;
        } else {
            merged = fallback;
        }
        // 三项都空时 merged 为 null（fallback 传 null 表示"没有原因"），交由调用方判空给出 400 文案
        if (merged == null) {
            return null;
        }
        return merged.length() > REASON_MAX ? merged.substring(0, REASON_MAX) : merged;
    }

    /** 状态分组（C01）：页面 tabs 给的是分组码，一次映射成状态集合，避免页面自己拼多请求 */
    public static List<OrderStatus> statusGroup(String raw) {
        String code = trim(raw);
        if (code.isEmpty() || "all".equalsIgnoreCase(code)) {
            return List.of(OrderStatus.values());
        }
        return switch (code.toLowerCase()) {
            case "pending" -> List.of(OrderStatus.PENDING);
            case "toship", "toshipped" -> List.of(OrderStatus.PAID, OrderStatus.PROCESSING);
            case "shipping", "ship" -> List.of(OrderStatus.SHIPPED);
            case "arrived" -> List.of(OrderStatus.DELIVERED);
            case "done", "completed" -> List.of(OrderStatus.COMPLETED);
            case "closed" -> List.of(OrderStatus.CANCELLED, OrderStatus.REFUNDED);
            case "refunded" -> List.of(OrderStatus.REFUNDED);
            case "cancelled" -> List.of(OrderStatus.CANCELLED);
            default -> List.of(OrderStatus.fromCode(code));
        };
    }

    /** 可点选的分组标签，列表页筛选条按它渲染 */
    public record StatusTab(String code, String label) {
    }

    public static final List<StatusTab> STATUS_TABS = List.of(
            new StatusTab("all", "全部"),
            new StatusTab("pending", "待付款"),
            new StatusTab("toship", "待发货"),
            new StatusTab("shipping", "配送中"),
            new StatusTab("done", "已完成"),
            new StatusTab("closed", "已取消/退款"));

    /** 排序白名单（C03）：只认这几个 key，其余一律回落默认，Sort 属性名不能由页面自由传入 */
    public static Sort sortOf(String key) {
        String code = trim(key).toLowerCase();
        return switch (code) {
            case "time_asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "amount_desc" -> Sort.by(Sort.Direction.DESC, "payAmount");
            case "amount_asc" -> Sort.by(Sort.Direction.ASC, "payAmount");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    public record SortOption(String code, String label) {
    }

    public static final List<SortOption> SORT_OPTIONS = List.of(
            new SortOption("time_desc", "最近下单"),
            new SortOption("time_asc", "最早下单"),
            new SortOption("amount_desc", "金额从高到低"),
            new SortOption("amount_asc", "金额从低到高"));

    /**
     * 搜索词清洗（C02）：% 与 _ 是 LIKE 通配符，用户搜「13%」不该把整表捞出来；
     * 手机号中间四位打码后仍搜不到，因此这里只做通配与长度控制，脱敏留给展示层。
     */
    public static String keyword(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.replace("%", "").replace("_", "").replace("'", "").trim();
        if (cleaned.length() > KEYWORD_MAX) {
            cleaned = cleaned.substring(0, KEYWORD_MAX);
        }
        // 手机号带空格/连字符是常见粘贴结果，去掉后按纯数字匹配 receiver_phone
        if (cleaned.matches("^[+\\d\\s-]{7,}$")) {
            cleaned = cleaned.replaceAll("[\\s-]", "").replace("+86", "");
        }
        return cleaned;
    }

    /** 时间范围补齐：页面只给日期时，结束日按当天 23:59:59 算，否则当天的单会被漏掉 */
    public static LocalDateTime endOfDay(LocalDateTime value) {
        return value == null ? null
                : value.toLocalDate().atTime(23, 59, 59);
    }

    /** 支付倒计时剩余秒数（C08）：只有待付款且未被标记为已支付时才有意义 */
    public static Long paySecondsLeft(OrderStatus status, LocalDateTime payDeadlineAt, LocalDateTime now) {
        if (status != OrderStatus.PENDING || payDeadlineAt == null || now == null) {
            return null;
        }
        long left = Duration.between(now, payDeadlineAt).getSeconds();
        return Math.max(left, 0);
    }

    public static String payCountdownText(Long secondsLeft) {
        if (secondsLeft == null) {
            return "";
        }
        long minutes = secondsLeft / 60;
        long seconds = secondsLeft % 60;
        return "%02d:%02d".formatted(minutes, seconds);
    }

    /**
     * 引导评价文案（C26）：签收/完成满 N 天且还有未评价的行才提示，
     * 刚送达就催评价只会招烦，所以锚点由调用方按 deliverTime/finishTime 选好传进来。
     */
    public static String reviewGuide(LocalDateTime anchor, LocalDateTime now, int days, int maxItems) {
        if (anchor == null || now == null || days < 0) {
            return null;
        }
        long elapsed = Duration.between(anchor, now).toDays();
        if (elapsed < days) {
            return null;
        }
        String when = elapsed == 0 ? "今天" : elapsed + " 天前";
        return "花礼已送达" + when + "，为本次花礼写条评价，帮更多人挑到合适的花（最多 "
                + maxItems + " 束待评价）";
    }

    /** 时间范围校验（C01）：结束早于开始直接给原因，而不是让列表显示成「暂无订单」 */
    public static String rangeError(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && to.isBefore(from)) {
            return "下单时间的结束日早于开始日，请重新选择";
        }
        return null;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
