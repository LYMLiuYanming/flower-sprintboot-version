package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.AnalyticsService;
import org.liuym.flowerv1springboot.service.StatsService;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台看板统计（H 组）：口径全部下沉到数据库聚合，页面不再拉全表。
 *
 * <p>两套接口并存：旧接口（overview/sales-trend/top-products/top-spenders）保持原契约，
 * 新接口统一接受 range(7/30/90/custom) + from/to + granularity(day/week/month) 的时间范围参数。
 */
@RestController
@RequestMapping("/api/admin/stats")
@Tag(name = "后台 · 统计看板")
public class StatsController {

    private final StatsService statsService;
    private final AnalyticsService analyticsService;

    public StatsController(StatsService statsService, AnalyticsService analyticsService) {
        this.statsService = statsService;
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    @Operation(summary = "概览计数", description = "商品/订单/用户等基数与累计成交额")
    public Result<Map<String, Object>> overview() {
        return Result.ok(statsService.overview());
    }

    @GetMapping("/sales-trend")
    @Operation(summary = "近 N 天成交趋势（旧接口）")
    public Result<List<Map<String, Object>>> salesTrend(@RequestParam(defaultValue = "14") int days) {
        return Result.ok(statsService.salesTrend(days));
    }

    @GetMapping("/top-products")
    @Operation(summary = "热销商品 TopN（旧接口）")
    public Result<List<Map<String, Object>>> topProducts(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(statsService.topProducts(limit));
    }

    @GetMapping("/top-spenders")
    @Operation(summary = "消费额 TopN 用户")
    public Result<List<Map<String, Object>>> topSpenders(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(statsService.topSpenders(limit));
    }

    /* ══════════════ H01–H14 看板/报表接口 ══════════════ */

    @GetMapping("/dashboard")
    @Operation(summary = "看板首屏", description = "KPI 指标卡 + 趋势 + 环比异常标记 + 数据生成时间")
    public Result<StatsViews.Dashboard> dashboard(@RequestParam(defaultValue = "30") String range,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "day") String granularity) {
        return Result.ok(analyticsService.dashboard(resolve(range, from, to, granularity)));
    }

    @GetMapping("/kpi")
    @Operation(summary = "指标卡（H03）", description = "客单价、支付转化率、复购率、退款率及各自环比")
    public Result<StatsViews.Kpi> kpi(@RequestParam(defaultValue = "30") String range,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.kpi(resolve(range, from, to, null)));
    }

    @GetMapping("/trend")
    @Operation(summary = "销售趋势（H02）", description = "granularity=day/week/month")
    public Result<List<StatsViews.TrendPoint>> trend(@RequestParam(defaultValue = "30") String range,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(defaultValue = "day") String granularity) {
        return Result.ok(analyticsService.trend(resolve(range, from, to, granularity)));
    }

    @GetMapping("/categories")
    @Operation(summary = "分类销售占比与同比（H04）")
    public Result<List<StatsViews.CategoryShare>> categories(@RequestParam(defaultValue = "30") String range,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.categoryShare(resolve(range, from, to, null)));
    }

    @GetMapping("/sales-products")
    @Operation(summary = "商品销量 TopN（H05）", description = "支持时间窗，并列给出库存与可售天数")
    public Result<List<StatsViews.ProductSale>> salesProducts(@RequestParam(defaultValue = "30") String range,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                              @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(analyticsService.topProducts(resolve(range, from, to, null), limit));
    }

    @GetMapping("/regions")
    @Operation(summary = "地区销售榜（H06）", description = "含城市经纬度，可联动鲜花地图")
    public Result<List<StatsViews.RegionSale>> regions(@RequestParam(defaultValue = "30") String range,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                       @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(analyticsService.regions(resolve(range, from, to, null), limit));
    }

    @GetMapping("/origins")
    @Operation(summary = "产地发货榜（H06 联动地图）")
    public Result<List<StatsViews.OriginSale>> origins(@RequestParam(defaultValue = "30") String range,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                       @RequestParam(defaultValue = "8") int limit) {
        return Result.ok(analyticsService.origins(resolve(range, from, to, null), limit));
    }

    @GetMapping("/coupons")
    @Operation(summary = "优惠券核销率与带动 GMV（H07）")
    public Result<StatsViews.CouponBoard> coupons(@RequestParam(defaultValue = "30") String range,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(analyticsService.coupons(resolve(range, from, to, null), limit));
    }

    @GetMapping("/refunds")
    @Operation(summary = "退款率与原因分布（H08）")
    public Result<StatsViews.RefundStat> refunds(@RequestParam(defaultValue = "30") String range,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.refunds(resolve(range, from, to, null)));
    }

    @GetMapping("/fulfillment")
    @Operation(summary = "履约时效分位（H09）", description = "下单→支付→发货→签收的 P25/P50/P90 小时数")
    public Result<StatsViews.Fulfillment> fulfillment(@RequestParam(defaultValue = "30") String range,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.fulfillment(resolve(range, from, to, null)));
    }

    @GetMapping("/inventory")
    @Operation(summary = "库存周转与告急清单（H10）")
    public Result<StatsViews.Inventory> inventory(@RequestParam(defaultValue = "30") String range,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(analyticsService.inventory(resolve(range, from, to, null), limit));
    }

    @GetMapping("/customers")
    @Operation(summary = "新客/老客构成与来源（H11）", description = "来源口径为成交首单的品类")
    public Result<StatsViews.CustomerMix> customers(@RequestParam(defaultValue = "30") String range,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.customers(resolve(range, from, to, null)));
    }

    @GetMapping("/reputation")
    @Operation(summary = "口碑：评价量与差评占比")
    public Result<StatsViews.Reputation> reputation(@RequestParam(defaultValue = "30") String range,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.ok(analyticsService.reputation(resolve(range, from, to, null)));
    }

    /**
     * 后台首页待办卡（G27）。待回复评价不在这里出：那个指标评价批次已有权威口径
     * （GET /api/admin/reviews/todo 的 unreplied），首页直接复用，避免两处算出两个数。
     */
    @GetMapping("/todos")
    @Operation(summary = "待办计数（G27）", description = "待发货 / 待审核退款 / 库存告急")
    public Result<StatsViews.TodoBoard> todos() {
        return Result.ok(analyticsService.todos());
    }

    /**
     * 报表中心整包数据：逐卡取数都走各自的缓存条目，这里只做拼装，
     * 所以首屏、报表页与 CSV 导出用的是同一批已缓存结果，不会把聚合查询再跑一遍。
     */
    @GetMapping("/report")
    @Operation(summary = "报表中心整包", description = "时间范围内的全部图卡数据")
    public Result<StatsViews.Report> report(@RequestParam(defaultValue = "30") String range,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(defaultValue = "day") String granularity) {
        StatsViews.Range view = resolve(range, from, to, granularity);
        return Result.ok(buildReport(view));
    }

    /** 拼装单独成方法：报表页与导出接口共用同一套取数，保证「当前视图」与页面所见一致 */
    private StatsViews.Report buildReport(StatsViews.Range view) {
        return new StatsViews.Report(
                view,
                analyticsService.kpi(view),
                analyticsService.trend(view),
                analyticsService.categoryShare(view),
                analyticsService.topProducts(view, 10),
                analyticsService.regions(view, 12),
                analyticsService.origins(view, 8),
                analyticsService.coupons(view, 10),
                analyticsService.refunds(view),
                analyticsService.fulfillment(view),
                analyticsService.inventory(view, 10),
                analyticsService.customers(view),
                analyticsService.reputation(view),
                LocalDateTime.now());
    }

    private StatsViews.Range resolve(String range, LocalDate from, LocalDate to, String granularity) {
        return analyticsService.resolveRange(range, from, to, granularity);
    }

    /** 看板「刷新」按钮：两套统计缓存一起失效（60s TTL 内不手动清会读到旧值） */
    @PostMapping("/refresh")
    @Operation(summary = "刷新统计数据")
    public Result<Void> refresh() {
        statsService.clearCache();
        analyticsService.clearCache();
        return Result.ok("统计数据已刷新", null);
    }
}
