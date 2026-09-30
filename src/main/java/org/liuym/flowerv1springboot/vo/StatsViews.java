package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.CityGeo;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 看板与报表的输出口径。
 *
 * <p>这里放的不只是数据载体，还有「环比/同比怎么算、什么时候算异常、城市怎么归并」这套纯逻辑：
 * 抽成静态方法后单测可以直接跑（不需要 Spring 上下文），服务层只负责取数与装配。
 */
public final class StatsViews {

    private StatsViews() {
    }

    /** 异常高亮阈值（H14）：集中一处，运营调口径不用翻遍服务代码 */
    public static final class Thresholds {
        /** 成交额环比跌破 -15% 记异常下滑 */
        public static final double GMV_DROP = -0.15;
        /** 成交额环比涨幅超过 +60% 记暴涨（活动拉动或数据异常都值得复核） */
        public static final double GMV_RISE = 0.60;
        public static final double AOV_DROP = -0.20;
        public static final double AOV_RISE = 0.35;
        public static final double ORDERS_DROP = -0.20;
        public static final double ORDERS_RISE = 0.60;
        public static final double CONVERT_DROP = -0.25;
        public static final double REPURCHASE_DROP = -0.25;
        /** 退款率越低越好，只有环比上升超阈值才是坏消息 */
        public static final double REFUND_RISE = 0.50;
        /** 履约时长涨幅：中位时长环比变慢 30% 以上提示 */
        public static final double TIMING_RISE = 0.30;
        /** 库存可售天数低于该值进告急清单 */
        public static final double LOW_STOCK_COVER_DAYS = 3.0;
        /** 不论动销快慢，库存低于该绝对值一律告急 */
        public static final int LOW_STOCK_FLOOR = 5;
        /** 只想单向判定（如转化率只关心下滑）时，把另一侧阈值设成永远达不到的值 */
        public static final double NEVER = Double.MAX_VALUE;

        private Thresholds() {
        }
    }

    /** 异常等级：down 红、up 金、flat 不高亮 */
    public static final String LEVEL_DOWN = "down";
    public static final String LEVEL_UP = "up";
    public static final String LEVEL_FLAT = "flat";

    /** 时间粒度 */
    public static final String UNIT_DAY = "day";
    public static final String UNIT_WEEK = "week";
    public static final String UNIT_MONTH = "month";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("M月d日");

    /* ══════════════════ 纯逻辑（单测覆盖） ══════════════════ */

    /**
     * 变化率：基期为 0 时返回 null，而不是把「上期没有」伪装成 0% 或除出无穷大。
     * 前端对 null 显示「—」，读者一眼能区分「持平」与「无可比数据」。
     */
    public static Double changeRate(double current, double base) {
        if (base == 0d) {
            return null;
        }
        return round4((current - base) / Math.abs(base));
    }

    /** 按阈值判等级；lowerIsBetter=true（退款率、时效这类越小越好的指标）只有上升才算异常 */
    public static String levelOf(Double rate, double drop, double rise, boolean lowerIsBetter) {
        if (rate == null) {
            return LEVEL_FLAT;
        }
        if (lowerIsBetter) {
            return rate >= rise ? LEVEL_DOWN : LEVEL_FLAT;
        }
        if (rate <= drop) {
            return LEVEL_DOWN;
        }
        return rate >= rise ? LEVEL_UP : LEVEL_FLAT;
    }

    /** 环比条目：变化率 + 等级 + 一句人话，前端直接渲染角标 */
    public static Anomaly anomaly(String metric, double current, double base, double drop, double rise,
                                  boolean lowerIsBetter) {
        Double rate = changeRate(current, base);
        String level = levelOf(rate, drop, rise, lowerIsBetter);
        return new Anomaly(metric, rate, level, describe(rate, level, base));
    }

    public static Delta delta(double current, double base, double drop, double rise, boolean lowerIsBetter) {
        Double rate = changeRate(current, base);
        return new Delta(rate, levelOf(rate, drop, rise, lowerIsBetter));
    }

    private static String describe(Double rate, String level, double base) {
        if (rate == null) {
            return "上期无对比数据";
        }
        String pct = percent(Math.abs(rate), 1);
        if (LEVEL_FLAT.equals(level)) {
            return "环比 " + (rate >= 0 ? "+" : "-") + pct + "，波动在正常区间";
        }
        return "环比异常" + (rate >= 0 ? "上涨 " : "下滑 ") + pct + "（上期 " + plain(base) + "）";
    }

    /** 缺失日期补 0：库里只返回有成交的天，折线必须连续，否则周末空档会被读成断崖 */
    public static List<TrendPoint> fillDailyGaps(LocalDate from, LocalDate to, Map<String, BigDecimal> amounts,
                                                 Map<String, Long> orders) {
        List<TrendPoint> points = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            String key = day.format(DAY);
            points.add(new TrendPoint(key, amount(amounts.get(key)), orders.getOrDefault(key, 0L), null));
        }
        return withAov(points);
    }

    /**
     * 日桶上卷到周/月（H02）。入参应是补齐过的连续日序列，这样周/月里的空档不会被当成 0 天漏算。
     * week 取 ISO 周一作为桶标签，month 取 yyyy-MM。
     */
    public static List<TrendPoint> rollupTrend(List<TrendPoint> daily, String granularity) {
        if (daily == null || daily.isEmpty()) {
            return List.of();
        }
        if (!isWeek(granularity) && !isMonth(granularity)) {
            return withAov(daily);
        }
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (TrendPoint point : daily) {
            String key = bucketLabel(point.label(), granularity);
            if (key == null) {
                continue;
            }
            amounts.merge(key, amount(point.amount()), BigDecimal::add);
            counts.merge(key, point.orders(), Long::sum);
        }
        List<TrendPoint> rolled = new ArrayList<>(amounts.size());
        for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
            rolled.add(new TrendPoint(entry.getKey(), entry.getValue(), counts.getOrDefault(entry.getKey(), 0L), null));
        }
        return withAov(rolled);
    }

    /** 桶标签：非法日期返回 null——上游数据异常时宁可少一个点，也不让整张图崩掉 */
    public static String bucketLabel(String day, String granularity) {
        LocalDate date = parseDate(day);
        if (date == null) {
            return null;
        }
        if (isMonth(granularity)) {
            return date.format(MONTH);
        }
        if (isWeek(granularity)) {
            return date.with(DayOfWeek.MONDAY).format(DAY);
        }
        return date.format(DAY);
    }

    /** 给每个桶补客单价（GMV/单量）；单量为 0 时留 null，前端显示「—」而不是 0 元 */
    public static List<TrendPoint> withAov(List<TrendPoint> points) {
        List<TrendPoint> out = new ArrayList<>(points.size());
        for (TrendPoint p : points) {
            out.add(new TrendPoint(p.label(), amount(p.amount()), p.orders(), ratio(p.amount(), p.orders())));
        }
        return out;
    }

    /**
     * 地区销售榜（H06）：把收货地址文本归并到城市。
     *
     * <p>入参已是「一单一行」的 SQL 聚合结果（金额按订单折叠过，不会因明细行重复累加），
     * 城市判定只能在 Java 侧做：地址是自由文本，SQL 正则取「市」既会漏也会错，口径统一交给 CityGeo。
     * 定位不到城市的单子并进「其他」，保证榜单合计等于窗口成交额。
     */
    public static List<RegionSale> regionRank(List<GeoRow> rows, BigDecimal totalAmount, int limit) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, CityGeo.Center> centers = new LinkedHashMap<>();
        for (GeoRow row : rows) {
            CityGeo.Center center = CityGeo.fromAddress(row.address()).orElse(null);
            String city = center == null ? OTHER_REGION : center.name();
            counters.computeIfAbsent(city, k -> new long[2]);
            long[] acc = counters.get(city);
            acc[0]++;
            acc[1] += row.units();
            amounts.merge(city, amount(row.amount()), BigDecimal::add);
            if (center != null) {
                centers.putIfAbsent(city, center);
            }
        }
        List<RegionSale> list = new ArrayList<>(amounts.size());
        for (Map.Entry<String, BigDecimal> entry : amounts.entrySet()) {
            long[] acc = counters.get(entry.getKey());
            CityGeo.Center center = centers.get(entry.getKey());
            list.add(new RegionSale(entry.getKey(), center == null ? null : center.adcode(),
                    center == null ? null : center.lng(), center == null ? null : center.lat(),
                    acc[0], acc[1], money(entry.getValue()), shareOf(entry.getValue(), totalAmount)));
        }
        list.sort((a, b) -> b.amount().compareTo(a.amount()));
        return list.size() > limit ? new ArrayList<>(list.subList(0, limit)) : list;
    }

    private static final String OTHER_REGION = "其他";

    /** 金额占比（%）：分母为 0 返回 0，避免空窗口里出现 NaN */
    public static double shareOf(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0 || part == null) {
            return 0d;
        }
        return round1(part.doubleValue() / total.doubleValue() * 100d);
    }

    public static double shareOf(double part, double total) {
        return total <= 0d ? 0d : round1(part / total * 100d);
    }

    public static double ratePercent(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0d;
        }
        return round1(numerator * 100d / denominator);
    }

    /** 日均销量 */
    public static double dailyAverage(long total, int days) {
        return round2(total * 1d / Math.max(1, days));
    }

    /** 库存周转率 = 窗口销量 / 期末库存；库存为 0 返回 null（缺货比周转率更有意义） */
    public static Double turnoverRate(long unitsSold, long stock) {
        if (stock <= 0) {
            return null;
        }
        return round2(unitsSold * 1d / stock);
    }

    /** 可售天数 = 库存 / 日均销量；没有动销返回 null（表示「不缺」，而不是 0 天告急） */
    public static Double coverDays(long stock, long unitsSold, int days) {
        if (unitsSold <= 0) {
            return null;
        }
        double perDay = unitsSold * 1d / Math.max(1, days);
        return perDay <= 0d ? null : round1(stock / perDay);
    }

    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return LocalDate.parse(text.length() >= 10 ? text.substring(0, 10) : text, DAY);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static boolean isWeek(String granularity) {
        return UNIT_WEEK.equalsIgnoreCase(granularity);
    }

    public static boolean isMonth(String granularity) {
        return UNIT_MONTH.equalsIgnoreCase(granularity);
    }

    public static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 金额 ÷ 单量：单量为 0 返回 null，前端据此显示「—」 */
    public static BigDecimal ratio(BigDecimal amount, long divisor) {
        if (divisor <= 0) {
            return null;
        }
        return money(amount).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
    }

    public static BigDecimal amount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static long amountValue(BigDecimal value) {
        return value == null ? 0L : value.longValue();
    }

    public static String plain(BigDecimal value) {
        return money(value).stripTrailingZeros().toPlainString();
    }

    public static String plain(double value) {
        return new BigDecimal(Double.toString(value)).stripTrailingZeros().toPlainString();
    }

    public static String percent(double rate, int digits) {
        double scaled = Math.round(rate * Math.pow(10, digits)) / Math.pow(10, digits);
        return new BigDecimal(Double.toString(scaled)).setScale(digits, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    public static String md(LocalDate date) {
        return date == null ? "" : date.format(MD);
    }

    private static double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static double round2(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private static double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    /* ══════════════════ CSV（H12） ══════════════════ */

    /** 逗号/引号/换行都要包起来；以 = 开头的单元格会被 Excel 当公式执行，一并加引号规避 */
    public static String csvCell(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r", " ").replace("\n", " ");
        if (text.contains(",") || text.contains("\"") || text.startsWith(" ") || text.startsWith("=")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    public static String csvRow(Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            Object cell = cells[i];
            line.append(csvCell(cell == null ? "" : String.valueOf(cell)));
        }
        return line.toString();
    }

    /* ══════════════════ 视图 ══════════════════ */

    /** 时间范围（H01） */
    public record Range(String from, String to, int days, String granularity, String label, String preset) {
    }

    /** 环比变化：changeRate 为 null 表示上期无数据 */
    public record Delta(Double changeRate, String level) {
    }

    public record Anomaly(String metric, Double changeRate, String level, String text) {
    }

    /** KPI 指标卡（H03 + H14 角标） */
    public record Kpi(
            BigDecimal gmv,
            long dealOrders,
            BigDecimal avgOrderAmount,
            long createdOrders,
            double payConvertPercent,
            long buyers,
            long repeatBuyers,
            double repurchasePercent,
            long refundOrders,
            BigDecimal refundAmount,
            double refundPercent,
            long cancelOrders,
            double cancelPercent,
            BigDecimal totalGmv,
            Delta gmvDelta,
            Delta orderDelta,
            Delta avgDelta,
            Delta convertDelta,
            Delta repurchaseDelta,
            Delta refundDelta) {
    }

    /** 趋势点（H02）：label 为日/周/月桶标签 */
    public record TrendPoint(String label, BigDecimal amount, long orders, BigDecimal avgOrderAmount) {
    }

    /** 品类占比与同比（H04） */
    public record CategoryShare(String category, BigDecimal amount, long units, long orders,
                                double percent, Double yoyRate, BigDecimal yoyAmount) {
    }

    /** 商品销量榜（H05） */
    public record ProductSale(UUID productId, String productName, long units, BigDecimal amount,
                              double amountPercent, Integer stock, Double coverDays) {
    }

    /** 地址聚合入参（一单一行） */
    public record GeoRow(String address, BigDecimal amount, long units) {
    }

    /** 地区销售榜（H06）：带经纬度，前端可直接画气泡图并联动鲜花地图 */
    public record RegionSale(String city, String adcode, Double lng, Double lat,
                             long orders, long units, BigDecimal amount, double percent) {
    }

    /** 产地发货榜（H06 联动地图） */
    public record OriginSale(String name, String city, long units, long orders, double percent) {
    }

    /** 券表现（H07） */
    public record CouponPerf(UUID couponId, String name, String type, String status, long issued, long used,
                             long expired, long usedInWindow, BigDecimal gmvInWindow, BigDecimal discountInWindow,
                             double redeemPercent) {
    }

    public record CouponSummary(long templates, long issued, long used, long expired, double redeemPercent,
                                long ordersInWindow, BigDecimal gmvInWindow, BigDecimal discountInWindow,
                                double gmvSharePercent) {
    }

    public record CouponBoard(CouponSummary summary, List<CouponPerf> items) {
    }

    /** 退款/取消原因（H08） */
    public record Reason(String name, long orders, BigDecimal amount, double percent) {
    }

    public record RefundStat(long dealOrders, BigDecimal dealAmount, long refundOrders, BigDecimal refundAmount,
                             double refundPercent, long cancelOrders, BigDecimal cancelAmount, double cancelPercent,
                             List<Reason> refundReasons, List<Reason> cancelReasons, Delta refundDelta) {
    }

    /** 履约时效（H09） */
    public record TimingStage(String key, String label, Double p25, Double p50, Double p90, long samples) {
    }

    public record Fulfillment(List<TimingStage> stages, long promised, long late, double onTimePercent,
                              Delta slowDelta) {
    }

    /** 库存周转与告急（H10） */
    public record Turnover(UUID productId, String productName, int stock, long units, BigDecimal amount,
                           Double turnoverRate, Double coverDays) {
    }

    public record StockAlert(UUID productId, String productName, int stock, long units, Double coverDays,
                             String reason) {
    }

    /** 阈值随数据一起下发：界面文案里的「可售天数 < N 天」必须与服务端判定同源，避免两处调参 */
    public record Inventory(double turnoverRate, long soldUnits, long onHandStock, long activeProducts,
                            long alertCount, double coverDaysThreshold, int stockFloorThreshold,
                            List<Turnover> top, List<StockAlert> alerts) {
    }

    /** 新客/老客与来源（H11） */
    public record Source(String name, long users, BigDecimal amount, double percent) {
    }

    public record CustomerMix(long newUsers, long oldUsers, long dealUsers, double newPercent,
                              BigDecimal newAmount, BigDecimal oldAmount, BigDecimal avgNewAmount,
                              BigDecimal avgOldAmount, List<Source> sources) {
    }

    /** 口碑卡 */
    public record Reputation(long reviews, double avgRating, long bad, double badPercent, long great,
                             double greatPercent, Delta reviewsDelta) {
    }

    /**
     * 后台首页待办卡（G27）：待发货 / 待审核退款 / 库存告急（待回复评价由评价批次的接口出，两边口径不打架）。
     *
     * <p>pendingRefund 用包装类型：退款流程的表还没建库时它是 null，界面据此写「未启用」；
     * 若退化成 0，运营会把它读成「今天没有退款要处理」，那是个假消息。
     */
    public record TodoBoard(long awaitingShip, Long pendingRefund, long stockAlerts, LocalDateTime generatedAt) {
    }

    /** 看板首屏：KPI + 趋势 + 异常标记 + 缓存生成时间（H13） */
    public record Dashboard(Range range, Kpi kpi, List<TrendPoint> trend, List<Anomaly> anomalies,
                            List<CategoryShare> categories, List<ProductSale> topProducts,
                            List<RegionSale> regions, LocalDateTime generatedAt) {
    }

    /** 报表中心整页数据（H12 导出也以此为「当前视图」） */
    public record Report(Range range, Kpi kpi, List<TrendPoint> trend, List<CategoryShare> categories,
                         List<ProductSale> topProducts, List<RegionSale> regions, List<OriginSale> origins,
                         CouponBoard coupons, RefundStat refunds, Fulfillment fulfillment, Inventory inventory,
                         CustomerMix customers, Reputation reputation, LocalDateTime generatedAt) {
    }
}
