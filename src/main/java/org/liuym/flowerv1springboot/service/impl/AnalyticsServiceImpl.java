package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.config.CacheConfig;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.repository.AnalyticsRepository;
import org.liuym.flowerv1springboot.service.AnalyticsService;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.liuym.flowerv1springboot.vo.StatsViews.Anomaly;
import org.liuym.flowerv1springboot.vo.StatsViews.CategoryShare;
import org.liuym.flowerv1springboot.vo.StatsViews.CouponBoard;
import org.liuym.flowerv1springboot.vo.StatsViews.CouponPerf;
import org.liuym.flowerv1springboot.vo.StatsViews.CouponSummary;
import org.liuym.flowerv1springboot.vo.StatsViews.CustomerMix;
import org.liuym.flowerv1springboot.vo.StatsViews.Dashboard;
import org.liuym.flowerv1springboot.vo.StatsViews.Delta;
import org.liuym.flowerv1springboot.vo.StatsViews.Fulfillment;
import org.liuym.flowerv1springboot.vo.StatsViews.GeoRow;
import org.liuym.flowerv1springboot.vo.StatsViews.Inventory;
import org.liuym.flowerv1springboot.vo.StatsViews.Kpi;
import org.liuym.flowerv1springboot.vo.StatsViews.OriginSale;
import org.liuym.flowerv1springboot.vo.StatsViews.ProductSale;
import org.liuym.flowerv1springboot.vo.StatsViews.Range;
import org.liuym.flowerv1springboot.vo.StatsViews.Reason;
import org.liuym.flowerv1springboot.vo.StatsViews.RefundStat;
import org.liuym.flowerv1springboot.vo.StatsViews.RegionSale;
import org.liuym.flowerv1springboot.vo.StatsViews.Reputation;
import org.liuym.flowerv1springboot.vo.StatsViews.Source;
import org.liuym.flowerv1springboot.vo.StatsViews.StockAlert;
import org.liuym.flowerv1springboot.vo.StatsViews.Thresholds;
import org.liuym.flowerv1springboot.vo.StatsViews.TimingStage;
import org.liuym.flowerv1springboot.vo.StatsViews.TrendPoint;
import org.liuym.flowerv1springboot.vo.StatsViews.Turnover;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 看板/报表取数：所有数字都来自 AnalyticsRepository 的数据库聚合，应用侧只做桶上卷与比率换算。
 *
 * <p>环比口径统一为「紧邻的等长上一期」，同比统一为「去年同期」；上期缺数据时比率返回 null，
 * 界面显示「—」，绝不把「没有数据」显示成「下跌 100%」。
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsServiceImpl implements AnalyticsService {

    /** 成交口径唯一来源：OrderStatus.DEAL_STATUSES，退款/取消由各自的状态单独统计 */
    private static final List<String> DEAL_CODES =
            OrderStatus.DEAL_STATUSES.stream().map(OrderStatus::getCode).toList();

    private static final List<String> REFUNDED_CODES = List.of(OrderStatus.REFUNDED.getCode());
    private static final List<String> CANCELLED_CODES = List.of(OrderStatus.CANCELLED.getCode());

    /** 待发货＝已付款/处理中但还没写发货时间：待付款不该进备货队列，已发货的也不叫待办 */
    private static final List<String> SHIPPING_PENDING_CODES =
            List.of(OrderStatus.PAID.getCode(), OrderStatus.PROCESSING.getCode());

    /** 待办卡的库存告急固定按近 7 天动销估计可售天数 */
    private static final int TODO_STOCK_SPAN = 7;

    /** 时效分段的中文名，key 与 SQL 里的 seg 字面量一一对应 */
    private static final Map<String, String> TIMING_LABELS = Map.of(
            "created_to_paid", "下单 → 支付",
            "created_to_ship", "下单 → 发货",
            "ship_to_sign", "发货 → 签收",
            "created_to_sign", "下单 → 签收");

    /** 图上分段的固定顺序（不依赖 UNION ALL 的返回顺序） */
    private static final List<String> TIMING_ORDER =
            List.of("created_to_paid", "created_to_ship", "ship_to_sign", "created_to_sign");

    /** 「下单 → 签收」是 H14 判定履约变慢的基准段 */
    private static final String SIGN_STAGE = "created_to_sign";

    private final AnalyticsRepository analytics;

    public AnalyticsServiceImpl(AnalyticsRepository analytics) {
        this.analytics = analytics;
    }

    /* ══════════════ H01 时间范围 ══════════════ */

    @Override
    public Range resolveRange(String range, LocalDate from, LocalDate to, String granularity) {
        LocalDate today = LocalDate.now();
        String preset = normalizePreset(range, from, to);
        LocalDate start;
        LocalDate end;
        if ("custom".equals(preset)) {
            start = from == null ? today.minusDays(29) : from;
            end = to == null ? today : to;
        } else {
            int days = switch (preset) {
                case "7" -> 7;
                case "90" -> 90;
                default -> 30;
            };
            end = today;
            start = today.minusDays(days - 1L);
        }
        if (start.isAfter(end)) {
            LocalDate swap = start;
            start = end;
            end = swap;
        }
        // 未来日期与超长跨度都在这里收敛，服务层其余方法可以假定窗口合法
        if (end.isAfter(today)) {
            end = today;
        }
        if (start.isBefore(end.minusDays(MAX_SPAN_DAYS - 1L))) {
            start = end.minusDays(MAX_SPAN_DAYS - 1L);
        }
        if (start.isAfter(end)) {
            start = end;
        }
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        String unit = StatsViews.isMonth(granularity) ? StatsViews.UNIT_MONTH
                : StatsViews.isWeek(granularity) ? StatsViews.UNIT_WEEK : StatsViews.UNIT_DAY;
        String label = StatsViews.md(start) + " – " + StatsViews.md(end) + " · " + days + " 天";
        return new Range(start.toString(), end.toString(), days, unit, label, preset);
    }

    private static String normalizePreset(String range, LocalDate from, LocalDate to) {
        if ("custom".equalsIgnoreCase(range) || (from != null && to != null)) {
            return "custom";
        }
        if ("7".equals(range) || "30".equals(range) || "90".equals(range)) {
            return range;
        }
        return "30";
    }

    /* ══════════════ 看板首屏（H01/H02/H03/H13/H14） ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS,
            key = "'dash:' + #p0.from() + ':' + #p0.to() + ':' + #p0.granularity()")
    public Dashboard dashboard(Range view) {
        Window current = window(view);
        Window before = previous(current);
        List<Anomaly> anomalies = new ArrayList<>();
        Kpi kpi = buildKpi(current, before, anomalies);
        return new Dashboard(view, kpi, buildTrend(view, current), anomalies,
                buildCategories(current), buildProducts(current, 8),
                buildRegions(current, kpi.gmv(), 10), LocalDateTime.now());
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'kpi:' + #p0.from() + ':' + #p0.to()")
    public Kpi kpi(Range view) {
        Window current = window(view);
        return buildKpi(current, previous(current), new ArrayList<>());
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS,
            key = "'trend:' + #p0.from() + ':' + #p0.to() + ':' + #p0.granularity()")
    public List<TrendPoint> trend(Range view) {
        return buildTrend(view, window(view));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'cat:' + #p0.from() + ':' + #p0.to()")
    public List<CategoryShare> categoryShare(Range view) {
        return buildCategories(window(view));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'top:' + #p0.from() + ':' + #p0.to() + ':' + #p1")
    public List<ProductSale> topProducts(Range view, int limit) {
        return buildProducts(window(view), clamp(limit, 5, 30));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'region:' + #p0.from() + ':' + #p0.to() + ':' + #p1")
    public List<RegionSale> regions(Range view, int limit) {
        Window current = window(view);
        BigDecimal dealAmount = analytics.dealTotals(DEAL_CODES, current.from(), current.to()).amount();
        return buildRegions(current, dealAmount, clamp(limit, 5, 30));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'origin:' + #p0.from() + ':' + #p0.to() + ':' + #p1")
    public List<OriginSale> origins(Range view, int limit) {
        Window current = window(view);
        List<AnalyticsRepository.OriginSale> rows =
                analytics.originSales(DEAL_CODES, current.from(), current.to(), clamp(limit, 5, 30));
        long total = rows.stream().mapToLong(AnalyticsRepository.OriginSale::units).sum();
        List<OriginSale> list = new ArrayList<>(rows.size());
        for (AnalyticsRepository.OriginSale row : rows) {
            list.add(new OriginSale(row.name(), row.city(), row.units(), row.orders(),
                    StatsViews.shareOf(row.units(), total)));
        }
        return list;
    }

    /* ══════════════ H07 优惠券 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'coupon:' + #p0.from() + ':' + #p0.to() + ':' + #p1")
    public CouponBoard coupons(Range view, int limit) {
        Window current = window(view);
        AnalyticsRepository.CouponTotal total = analytics.couponTotal(DEAL_CODES, current.from(), current.to());
        BigDecimal dealAmount = analytics.dealTotals(DEAL_CODES, current.from(), current.to()).amount();
        List<AnalyticsRepository.CouponPerf> rows =
                analytics.couponPerformance(DEAL_CODES, current.from(), current.to(), clamp(limit, 5, 30));
        List<CouponPerf> items = new ArrayList<>(rows.size());
        for (AnalyticsRepository.CouponPerf row : rows) {
            items.add(new CouponPerf(row.couponId(), row.name(), row.type(), row.status(), row.issued(),
                    row.used(), row.expired(), row.usedInWindow(), StatsViews.money(row.gmvInWindow()),
                    StatsViews.money(row.discountInWindow()),
                    StatsViews.ratePercent(row.used(), row.issued())));
        }
        CouponSummary summary = new CouponSummary(analytics.couponTemplateCount(), total.issued(), total.used(),
                total.expired(), StatsViews.ratePercent(total.used(), total.issued()), total.ordersInWindow(),
                StatsViews.money(total.gmvInWindow()), StatsViews.money(total.discountInWindow()),
                StatsViews.shareOf(total.gmvInWindow(), dealAmount));
        return new CouponBoard(summary, items);
    }

    /* ══════════════ H08 退款 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'refund:' + #p0.from() + ':' + #p0.to()")
    public RefundStat refunds(Range view) {
        Window current = window(view);
        return buildRefunds(current, previous(current));
    }

    /* ══════════════ H09 履约时效 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'fulfill:' + #p0.from() + ':' + #p0.to()")
    public Fulfillment fulfillment(Range view) {
        Window current = window(view);
        List<TimingStage> stages = buildStages(current);
        double currentP50 = medianOf(stages, SIGN_STAGE);
        double previousP50 = medianOf(buildStages(previous(current)), SIGN_STAGE);
        Delta slowDelta = StatsViews.delta(currentP50, previousP50, Thresholds.NEVER,
                Thresholds.TIMING_RISE, true);
        AnalyticsRepository.FulfillCompliance compliance =
                analytics.fulfillCompliance(DEAL_CODES, current.from(), current.to());
        return new Fulfillment(stages, compliance.promised(), compliance.late(),
                StatsViews.ratePercent(compliance.promised() - compliance.late(), compliance.promised()), slowDelta);
    }

    private List<TimingStage> buildStages(Window window) {
        List<AnalyticsRepository.TimingRow> rows =
                analytics.fulfillmentTiming(DEAL_CODES, window.from(), window.to());
        Map<String, AnalyticsRepository.TimingRow> bySegment = new LinkedHashMap<>();
        for (AnalyticsRepository.TimingRow row : rows) {
            bySegment.put(row.segment(), row);
        }
        List<TimingStage> stages = new ArrayList<>(TIMING_ORDER.size());
        for (String key : TIMING_ORDER) {
            AnalyticsRepository.TimingRow row = bySegment.get(key);
            if (row == null) {
                continue;
            }
            stages.add(new TimingStage(key, TIMING_LABELS.getOrDefault(key, key),
                    row.p25(), row.p50(), row.p90(), row.samples()));
        }
        return stages;
    }

    private static double medianOf(List<TimingStage> stages, String key) {
        for (TimingStage stage : stages) {
            if (key.equals(stage.key()) && stage.p50() != null) {
                return stage.p50();
            }
        }
        return 0d;
    }

    /* ══════════════ H10 库存 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'stock:' + #p0.from() + ':' + #p0.to() + ':' + #p1")
    public Inventory inventory(Range view, int limit) {
        Window current = window(view);
        int days = current.days();
        AnalyticsRepository.StockSummary summary = analytics.stockSummary(DEAL_CODES, current.from(), current.to());
        List<AnalyticsRepository.StockRow> topRows =
                analytics.stockTurnover(DEAL_CODES, current.from(), current.to(), clamp(limit, 5, 30));
        List<AnalyticsRepository.StockRow> alertRows = analytics.stockAlerts(DEAL_CODES, current.from(), current.to(),
                days, Thresholds.LOW_STOCK_COVER_DAYS, Thresholds.LOW_STOCK_FLOOR, 20);

        List<StatsViews.Turnover> top = new ArrayList<>(topRows.size());
        for (AnalyticsRepository.StockRow row : topRows) {
            top.add(new StatsViews.Turnover(row.productId(), row.productName(), row.stock(), row.units(),
                    StatsViews.money(row.amount()),
                    StatsViews.turnoverRate(row.units(), row.stock()),
                    StatsViews.coverDays(row.stock(), row.units(), days)));
        }
        List<StatsViews.StockAlert> alerts = new ArrayList<>(alertRows.size());
        for (AnalyticsRepository.StockRow row : alertRows) {
            Double cover = StatsViews.coverDays(row.stock(), row.units(), days);
            alerts.add(new StatsViews.StockAlert(row.productId(), row.productName(), row.stock(), row.units(),
                    cover, alertReason(row.stock(), cover)));
        }
        Double rate = StatsViews.turnoverRate(summary.units(), summary.stock());
        return new Inventory(rate == null ? 0d : rate, summary.units(), summary.stock(),
                summary.activeProducts(), alerts.size(), Thresholds.LOW_STOCK_COVER_DAYS, Thresholds.LOW_STOCK_FLOOR,
                top, alerts);
    }

    /** 告急原因要能解释「为什么被点名」：无动销但库存见底与动销过快是两种处置 */
    private static String alertReason(int stock, Double coverDays) {
        if (coverDays != null && coverDays < Thresholds.LOW_STOCK_COVER_DAYS) {
            return "预计 " + StatsViews.plain(coverDays) + " 天售罄";
        }
        if (stock <= 0) {
            return "已无库存";
        }
        if (stock <= Thresholds.LOW_STOCK_FLOOR) {
            return "库存低于 " + Thresholds.LOW_STOCK_FLOOR + " 件";
        }
        return "动销偏快";
    }

    /* ══════════════ H11 客群 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'mix:' + #p0.from() + ':' + #p0.to()")
    public CustomerMix customers(Range view) {
        Window current = window(view);
        Map<String, AnalyticsRepository.MixRow> mix = new LinkedHashMap<>();
        for (AnalyticsRepository.MixRow row : analytics.customerMix(DEAL_CODES, current.from(), current.to())) {
            mix.put(row.bucket(), row);
        }
        AnalyticsRepository.MixRow fresh = mix.getOrDefault("new", AnalyticsRepository.MixRow.ZERO);
        AnalyticsRepository.MixRow loyal = mix.getOrDefault("old", AnalyticsRepository.MixRow.ZERO);
        long newUsers = fresh.users();
        long oldUsers = loyal.users();
        BigDecimal newAmount = StatsViews.money(fresh.amount());
        BigDecimal oldAmount = StatsViews.money(loyal.amount());

        List<AnalyticsRepository.SourceRow> sourceRows =
                analytics.newCustomerSources(DEAL_CODES, current.from(), current.to());
        List<Source> sources = new ArrayList<>(sourceRows.size());
        long attributed = sourceRows.stream().mapToLong(AnalyticsRepository.SourceRow::users).sum();
        for (AnalyticsRepository.SourceRow row : sourceRows) {
            sources.add(new Source(row.source(), row.users(), StatsViews.money(row.amount()),
                    StatsViews.shareOf(row.users(), attributed)));
        }
        return new CustomerMix(newUsers, oldUsers, newUsers + oldUsers,
                StatsViews.shareOf(newUsers, newUsers + oldUsers), newAmount, oldAmount,
                StatsViews.ratio(newAmount, fresh.orders()), StatsViews.ratio(oldAmount, loyal.orders()), sources);
    }

    /* ══════════════ 口碑 ══════════════ */

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'review:' + #p0.from() + ':' + #p0.to()")
    public Reputation reputation(Range view) {
        Window current = window(view);
        AnalyticsRepository.RatingRow now = analytics.ratingWindow(DEAL_CODES, current.from(), current.to());
        Window before = previous(current);
        AnalyticsRepository.RatingRow then = analytics.ratingWindow(DEAL_CODES, before.from(), before.to());
        return new Reputation(now.reviews(), now.avgRating(), now.bad(),
                StatsViews.ratePercent(now.bad(), now.reviews()), now.great(),
                StatsViews.ratePercent(now.great(), now.reviews()),
                StatsViews.delta(now.reviews(), then.reviews(), Thresholds.ORDERS_DROP, Thresholds.ORDERS_RISE, false));
    }

    /* ══════════════ G27 待办卡 ══════════════ */

    /**
     * 待办看的是「现在有什么要动手」，所以不跟随看板的时间范围：
     * 告急用的动销窗口固定取近 7 天——补货判断只对最近的卖速敏感，用它选 90 天范围反而会把滞销品误报成告急。
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'todo'")
    public StatsViews.TodoBoard todos() {
        LocalDate today = LocalDate.now();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        LocalDateTime from = today.minusDays(TODO_STOCK_SPAN - 1L).atStartOfDay();
        return new StatsViews.TodoBoard(
                analytics.awaitingShipCount(SHIPPING_PENDING_CODES),
                analytics.pendingRefundCount(),
                analytics.stockAlertCount(DEAL_CODES, from, to, TODO_STOCK_SPAN,
                        Thresholds.LOW_STOCK_COVER_DAYS, Thresholds.LOW_STOCK_FLOOR),
                LocalDateTime.now());
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.STATS, allEntries = true)
    @Transactional(readOnly = false)
    public void clearCache() {
        // 清理由注解完成，方法体留空
    }

    /* ══════════════ 内部装配 ══════════════ */

    /** 窗口是「[from, to) 的 LocalDateTime 对 + 天数」，服务层用它取代散落的日期加减 */
    private record Window(LocalDate start, LocalDate end, LocalDateTime from, LocalDateTime to, int days) {
    }

    private Window window(Range view) {
        LocalDate start = StatsViews.parseDate(view.from());
        LocalDate end = StatsViews.parseDate(view.to());
        if (start == null || end == null) {
            return window(resolveRange(null, null, null, null));
        }
        int days = Math.max(1, view.days());
        return new Window(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), days);
    }

    private Window previous(Window current) {
        LocalDate end = current.start.minusDays(1);
        LocalDate start = end.minusDays(current.days - 1L);
        return new Window(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), current.days);
    }

    private Window yearOverYear(Window current) {
        LocalDate start = current.start.minusYears(1);
        LocalDate end = current.end.minusYears(1);
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        return new Window(start, end, start.atStartOfDay(), end.plusDays(1).atStartOfDay(), days);
    }

    private List<TrendPoint> buildTrend(Range view, Window current) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Long> orders = new LinkedHashMap<>();
        for (AnalyticsRepository.DayBucket row : analytics.salesByDay(DEAL_CODES, current.from(), current.to())) {
            amounts.put(row.day(), row.amount());
            orders.put(row.day(), row.orders());
        }
        List<TrendPoint> daily = StatsViews.fillDailyGaps(current.start, current.end, amounts, orders);
        return StatsViews.rollupTrend(daily, view.granularity());
    }

    private Kpi buildKpi(Window current, Window before, List<Anomaly> anomalies) {
        AnalyticsRepository.Repurchase now = analytics.repurchase(DEAL_CODES, current.from(), current.to());
        AnalyticsRepository.Repurchase prev = analytics.repurchase(DEAL_CODES, before.from(), before.to());

        Funnel currentFunnel = funnel(current);
        Funnel prevFunnel = funnel(before);

        CloseStats refundsNow = closeStats(REFUNDED_CODES, current);
        CloseStats refundsPrev = closeStats(REFUNDED_CODES, before);
        CloseStats cancelsNow = closeStats(CANCELLED_CODES, current);

        BigDecimal gmv = StatsViews.money(now.amount());
        BigDecimal prevGmv = StatsViews.money(prev.amount());
        BigDecimal avg = StatsViews.ratio(gmv, now.orders());
        BigDecimal prevAvg = StatsViews.ratio(prevGmv, prev.orders());

        double convert = StatsViews.ratePercent(currentFunnel.dealCreated(), currentFunnel.created());
        double prevConvert = StatsViews.ratePercent(prevFunnel.dealCreated(), prevFunnel.created());
        double repurchase = StatsViews.ratePercent(now.repeatBuyers(), now.buyers());
        double prevRepurchase = StatsViews.ratePercent(prev.repeatBuyers(), prev.buyers());
        double refundRate = StatsViews.ratePercent(refundsNow.orders(), now.orders());
        double prevRefundRate = StatsViews.ratePercent(refundsPrev.orders(), prev.orders());

        Anomaly gmvAnomaly = StatsViews.anomaly("成交额", gmv.doubleValue(), prevGmv.doubleValue(),
                Thresholds.GMV_DROP, Thresholds.GMV_RISE, false);
        Anomaly orderAnomaly = StatsViews.anomaly("成交单量", now.orders(), prev.orders(),
                Thresholds.ORDERS_DROP, Thresholds.ORDERS_RISE, false);
        Anomaly avgAnomaly = StatsViews.anomaly("客单价", doubleOf(avg), doubleOf(prevAvg),
                Thresholds.AOV_DROP, Thresholds.AOV_RISE, false);
        Anomaly convertAnomaly = StatsViews.anomaly("支付转化率", convert, prevConvert,
                Thresholds.CONVERT_DROP, Thresholds.NEVER, false);
        Anomaly repurchaseAnomaly = StatsViews.anomaly("复购率", repurchase, prevRepurchase,
                Thresholds.REPURCHASE_DROP, Thresholds.NEVER, false);
        Anomaly refundAnomaly = StatsViews.anomaly("退款率", refundRate, prevRefundRate,
                Thresholds.NEVER, Thresholds.REFUND_RISE, true);
        collect(anomalies, gmvAnomaly, orderAnomaly, avgAnomaly, convertAnomaly, repurchaseAnomaly, refundAnomaly);

        return new Kpi(gmv, now.orders(), avg, currentFunnel.created(), convert,
                now.buyers(), now.repeatBuyers(), repurchase,
                refundsNow.orders(), refundsNow.amount(), refundRate,
                cancelsNow.orders(), StatsViews.ratePercent(cancelsNow.orders(), now.orders()),
                StatsViews.money(analytics.totalDealAmount(DEAL_CODES)),
                toDelta(gmvAnomaly), toDelta(orderAnomaly), toDelta(avgAnomaly),
                toDelta(convertAnomaly), toDelta(repurchaseAnomaly), toDelta(refundAnomaly));
    }

    private RefundStat buildRefunds(Window current, Window before) {
        AnalyticsRepository.DealTotals deal = analytics.dealTotals(DEAL_CODES, current.from(), current.to());
        CloseStats refunds = closeStats(REFUNDED_CODES, current);
        CloseStats refundsPrev = closeStats(REFUNDED_CODES, before);
        CloseStats cancels = closeStats(CANCELLED_CODES, current);
        double refundRate = StatsViews.ratePercent(refunds.orders(), deal.orders());
        double prevRefundRate = StatsViews.ratePercent(refundsPrev.orders(),
                analytics.dealTotals(DEAL_CODES, before.from(), before.to()).orders());
        return new RefundStat(deal.orders(), StatsViews.money(deal.amount()), refunds.orders(), refunds.amount(),
                refundRate, cancels.orders(), cancels.amount(),
                StatsViews.ratePercent(cancels.orders(), deal.orders()),
                refunds.reasons(), cancels.reasons(),
                StatsViews.delta(refundRate, prevRefundRate, Thresholds.NEVER, Thresholds.REFUND_RISE, true));
    }

    private List<CategoryShare> buildCategories(Window current) {
        List<AnalyticsRepository.CategorySale> rows =
                analytics.categorySales(DEAL_CODES, current.from(), current.to());
        Map<String, BigDecimal> lastYear = new LinkedHashMap<>();
        Window yoy = yearOverYear(current);
        for (AnalyticsRepository.CategorySale row : analytics.categorySales(DEAL_CODES, yoy.from(), yoy.to())) {
            lastYear.put(row.category(), row.amount());
        }
        BigDecimal itemTotal = rows.stream().map(AnalyticsRepository.CategorySale::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CategoryShare> list = new ArrayList<>(rows.size());
        for (AnalyticsRepository.CategorySale row : rows) {
            BigDecimal base = lastYear.get(row.category());
            list.add(new CategoryShare(row.category(), StatsViews.money(row.amount()), row.units(), row.orders(),
                    StatsViews.shareOf(row.amount(), itemTotal),
                    base == null ? null : StatsViews.changeRate(row.amount().doubleValue(), base.doubleValue()),
                    base == null ? null : StatsViews.money(base)));
        }
        // 品类内部占比用本层聚合出的商品成交额做分母，与实付 GMV 差在券和运费上，不能混用
        return list;
    }

    private List<ProductSale> buildProducts(Window current, int limit) {
        List<AnalyticsRepository.ProductSale> rows =
                analytics.productSales(DEAL_CODES, current.from(), current.to(), limit);
        List<UUID> ids = rows.stream().map(AnalyticsRepository.ProductSale::productId).toList();
        Map<UUID, Integer> stocks = analytics.stockOf(ids);
        BigDecimal itemTotal = analytics.dealItemAmount(DEAL_CODES, current.from(), current.to());
        List<ProductSale> list = new ArrayList<>(rows.size());
        for (AnalyticsRepository.ProductSale row : rows) {
            Integer stock = stocks.get(row.productId());
            list.add(new ProductSale(row.productId(), row.productName(), row.units(), StatsViews.money(row.amount()),
                    StatsViews.shareOf(row.amount(), itemTotal), stock,
                    stock == null ? null : StatsViews.coverDays(stock, row.units(), current.days())));
        }
        return list;
    }

    private List<RegionSale> buildRegions(Window current, BigDecimal dealAmount, int limit) {
        List<GeoRow> rows = new ArrayList<>();
        for (AnalyticsRepository.OrderGeo row : analytics.dealOrdersWithAddress(DEAL_CODES, current.from(), current.to())) {
            rows.add(new GeoRow(row.address(), row.amount(), row.units()));
        }
        return StatsViews.regionRank(rows, dealAmount, limit);
    }

    private Funnel funnel(Window window) {
        long created = 0;
        long dealCreated = 0;
        for (AnalyticsRepository.StatusCount row : analytics.statusByCreatedTime(DEAL_CODES, window.from(), window.to())) {
            long orders = row.orders();
            created += orders;
            if (DEAL_CODES.contains(row.status())) {
                dealCreated += orders;
            }
        }
        return new Funnel(created, dealCreated);
    }

    private record Funnel(long created, long dealCreated) {
    }

    private CloseStats closeStats(List<String> statuses, Window window) {
        List<AnalyticsRepository.ReasonRow> rows = analytics.closeReasons(statuses, window.from(), window.to());
        long orders = rows.stream().mapToLong(AnalyticsRepository.ReasonRow::orders).sum();
        BigDecimal amount = rows.stream().map(AnalyticsRepository.ReasonRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Reason> reasons = new ArrayList<>(rows.size());
        for (AnalyticsRepository.ReasonRow row : rows) {
            reasons.add(new Reason(row.reason(), row.orders(), StatsViews.money(row.amount()),
                    StatsViews.shareOf(row.orders(), orders)));
        }
        return new CloseStats(orders, StatsViews.money(amount), reasons);
    }

    private record CloseStats(long orders, BigDecimal amount, List<Reason> reasons) {
    }

    private static void collect(List<Anomaly> sink, Anomaly... items) {
        for (Anomaly item : items) {
            if (!StatsViews.LEVEL_FLAT.equals(item.level())) {
                sink.add(item);
            }
        }
    }

    private static Delta toDelta(Anomaly anomaly) {
        return new Delta(anomaly.changeRate(), anomaly.level());
    }

    private static double doubleOf(BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }

    private static int clamp(int value, int min, int max) {
        if (value <= 0) {
            return min;
        }
        return Math.min(Math.max(value, min), max);
    }
}
