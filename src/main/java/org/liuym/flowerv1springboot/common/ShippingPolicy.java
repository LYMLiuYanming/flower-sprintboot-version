package org.liuym.flowerv1springboot.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 多方式配送策略：闪送 / 次日达 / 预约达 / 空运冷链 / 海运保鲜 / 门店自提。
 * 运费与时效一律由服务端按此表算出，前端只负责展示，避免篡改运费。
 * 纯静态计算，便于单测覆盖，不依赖 Spring 上下文。
 */
public final class ShippingPolicy {

    private ShippingPolicy() {
    }

    /** 商品未填重量时的兜底单件估重：束装花礼含包装约 1.2kg */
    public static final BigDecimal DEFAULT_UNIT_KG = new BigDecimal("1.2");
    /** 预约送达允许的最远天数 */
    public static final int MAX_SLOT_DAYS = 15;
    /** 预约送达最近提前量：花艺师需要备花与包花时间 */
    public static final long MIN_SLOT_LEAD_MINUTES = 60;
    private static final BigDecimal SLOT_START_HOUR = BigDecimal.valueOf(8);
    private static final BigDecimal SLOT_END_HOUR = BigDecimal.valueOf(21);
    /** 可约整点时段闭区间，运力日历按小时建桶 */
    public static final int SLOT_FIRST_HOUR = 8;
    public static final int SLOT_LAST_HOUR = 21;
    /** 单个预约时段的配送窗口长度（小时） */
    public static final int SLOT_WINDOW_HOURS = 1;

    /** 可约时段列表：08:00–21:00 的每个整点 */
    public static List<Integer> slotHours() {
        return java.util.stream.IntStream.rangeClosed(SLOT_FIRST_HOUR, SLOT_LAST_HOUR).boxed().toList();
    }

    /**
     * @param baseFee   首重运费
     * @param feePerKg  续重单价，按整单估算重量累加
     * @param freeOver  包邮门槛，null 表示该方式不参与包邮
     * @param etaHours  常规时效（小时），预约达以用户所选时段为准
     * @param slotAware 是否需要用户指定送达时段
     */
    public record Method(String code, String name, String icon, String note,
                         BigDecimal baseFee, BigDecimal feePerKg, BigDecimal freeOver,
                         int etaHours, boolean slotAware) {
    }

    public static final Method SAME_CITY = new Method("same_city", "同城闪送", "fa-bolt",
            "花艺师专人直送，市区 2 小时达", new BigDecimal("18.00"), BigDecimal.ZERO, null, 2, false);
    public static final Method NEXT_DAY = new Method("next_day", "次日达", "fa-truck",
            "标准快递，全国主要城市次日送达", new BigDecimal("10.00"), new BigDecimal("2.00"), new BigDecimal("199.00"), 24, false);
    public static final Method SCHEDULED = new Method("scheduled", "预约定时达", "fa-calendar-check",
            "指定日期与时段送达，适合纪念日", new BigDecimal("15.00"), BigDecimal.ZERO, new BigDecimal("399.00"), 24, true);
    public static final Method AIR_COLD = new Method("air_cold", "空运冷链", "fa-plane",
            "跨省鲜花专机 + 恒温箱，48 小时到港", new BigDecimal("38.00"), new BigDecimal("6.00"), new BigDecimal("599.00"), 48, false);
    public static final Method SEA_FRESH = new Method("sea_fresh", "海运保鲜", "fa-ship",
            "恒温柜慢递，大批量订单更划算", new BigDecimal("12.00"), new BigDecimal("2.50"), new BigDecimal("899.00"), 96, false);
    public static final Method SELF_PICKUP = new Method("self_pickup", "门店自提", "fa-store",
            "徐家汇旗舰店自提，免运费", BigDecimal.ZERO, BigDecimal.ZERO, null, 3, false);

    public static final List<Method> METHODS = List.of(SAME_CITY, NEXT_DAY, SCHEDULED, AIR_COLD, SEA_FRESH, SELF_PICKUP);

    /** 未指定配送方式时的默认选择 */
    public static final Method DEFAULT = NEXT_DAY;

    public static Optional<Method> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return METHODS.stream().filter(m -> m.code().equalsIgnoreCase(code.trim())).findFirst();
    }

    /** 解析失败或越界一律回落默认方式，保证历史订单与外部入参不会中断下单 */
    public static Method resolve(String code) {
        return find(code).orElse(DEFAULT);
    }

    /**
     * 商品重量文案（"1.5kg" / "800g" / "约 1 公斤"）折算为千克；留空或无法识别时按标准束装估重
     */
    public static BigDecimal unitWeightKg(String weightText) {
        if (weightText == null || weightText.isBlank()) {
            return DEFAULT_UNIT_KG;
        }
        String text = weightText.toLowerCase().trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(text);
        if (!matcher.find()) {
            return DEFAULT_UNIT_KG;
        }
        BigDecimal value = new BigDecimal(matcher.group(1));
        boolean gram = (text.contains("g") && !text.contains("kg")) || text.contains("克");
        boolean ton = text.contains("t") && !text.contains("pt");
        BigDecimal kg = gram ? value.movePointLeft(3) : ton ? value.movePointRight(3) : value;
        kg = kg.setScale(2, RoundingMode.HALF_UP);
        if (kg.compareTo(new BigDecimal("0.05")) < 0 || kg.compareTo(new BigDecimal("50")) > 0) {
            return DEFAULT_UNIT_KG;
        }
        return kg;
    }

    /** 整单估算重量：单件重量 × 件数 */
    public static BigDecimal totalWeightKg(List<BigDecimal> lineWeights) {
        if (lineWeights == null) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal sum = lineWeights.stream()
                .map(w -> w == null ? BigDecimal.ZERO : w)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return money(sum);
    }

    /**
     * 运费 = 首重 + 续重 × 重量，达到包邮门槛后为 0；自提与闪送按固定首重计价，不参与包邮
     */
    public static BigDecimal freightOf(Method method, BigDecimal goodsAmount, BigDecimal weightKg) {
        if (method == null) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal goods = scale(goodsAmount);
        if (method.freeOver() != null && goods.compareTo(method.freeOver()) >= 0) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal extra = scale(method.feePerKg()).multiply(scale(weightKg));
        return money(scale(method.baseFee()).add(extra));
    }

    /** 原价运费：用于包邮时展示"已省 ¥xx" */
    public static BigDecimal originalFreight(Method method, BigDecimal weightKg) {
        if (method == null) {
            return money(BigDecimal.ZERO);
        }
        return money(scale(method.baseFee()).add(scale(method.feePerKg()).multiply(scale(weightKg))));
    }

    /**
     * 预计送达时间：预约达以所选时段的结束点为承诺时限（1 小时窗口），其余按下单时间 + 常规时效
     */
    public static LocalDateTime arriveAt(Method method, LocalDateTime from, LocalDateTime slot) {
        if (method == null) {
            return null;
        }
        if (method.slotAware()) {
            return slot == null ? null : slot.plusHours(SLOT_WINDOW_HOURS);
        }
        LocalDateTime base = from == null ? LocalDateTime.now() : from;
        return base.plusHours(method.etaHours());
    }

    /**
     * 校验并归一用户所选送达时段：需落在可约窗口内，且只接受 08:00–21:00 的送花时段
     */
    public static LocalDateTime requireSlot(Method method, LocalDateTime now, LocalDateTime slot) {
        if (!method.slotAware()) {
            return null;
        }
        if (slot == null) {
            throw new BusinessException("请选择「" + method.name() + "」的送达时间");
        }
        LocalDateTime floored = slot.withSecond(0).withNano(0);
        if (floored.isBefore(now.plusMinutes(MIN_SLOT_LEAD_MINUTES))) {
            throw new BusinessException("送达时间需晚于当前时间 1 小时，以便花艺师备花");
        }
        if (floored.isAfter(now.plusDays(MAX_SLOT_DAYS))) {
            throw new BusinessException("最多可预约 " + MAX_SLOT_DAYS + " 天内的送达时间");
        }
        BigDecimal hour = BigDecimal.valueOf(floored.getHour()).add(
                BigDecimal.valueOf(floored.getMinute()).movePointLeft(2));
        if (hour.compareTo(SLOT_START_HOUR) < 0 || hour.compareTo(SLOT_END_HOUR) > 0) {
            throw new BusinessException("送花时段为 08:00–21:00");
        }
        return floored;
    }

    /** 时段文本统一存 ISO 格式，前端直接展示无需二次格式化 */
    public static String slotText(LocalDateTime slot) {
        return slot == null ? null : slot.withSecond(0).withNano(0).toString();
    }

    private static final DateTimeFormatter SLOT_DATE = DateTimeFormatter.ofPattern("MM 月 dd 日");

    /** 面向用户的日期文案，约满提示用它避免 ISO 串 */
    public static String slotDateText(LocalDateTime slot) {
        return slot == null ? "" : slot.format(SLOT_DATE);
    }

    public static String slotHourText(int hour) {
        return "%02d:00–%02d:00".formatted(hour, hour + 1);
    }

    /**
     * 解析前端提交的送达时段：datetime-local 给的是 "yyyy-MM-ddTHH:mm"，
     * 秒与纳秒一律抹平（预约粒度为分钟）；格式不合法直接拒绝，避免脏数据进库
     */
    public static LocalDateTime parseSlot(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.trim()).withSecond(0).withNano(0);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException("预约时间格式不正确");
        }
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 金额与重量统一保留两位，避免同一口径出现 0 与 0.00 两种写法 */
    private static BigDecimal money(BigDecimal value) {
        return scale(value).setScale(2, RoundingMode.HALF_UP);
    }
}
