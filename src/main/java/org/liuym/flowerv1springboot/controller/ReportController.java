package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.liuym.flowerv1springboot.service.AnalyticsService;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 报表中心（H 组）：页面入口与「当前视图 CSV 导出」（H12）。
 *
 * <p>导出取数完全复用看板的那批缓存方法，参数也只用时间范围，
 * 因此导出的数字与页面上看到的数字一定一致——否则报表导出反而会误导运营。
 */
@Controller
@Tag(name = "后台 · 报表中心")
public class ReportController {

    /** BOM：Excel 双击打开 csv 时靠它认出 UTF-8，否则中文全变乱码 */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final AnalyticsService analyticsService;

    public ReportController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/admin/report")
    @Operation(summary = "报表中心页面")
    public String reportPage() {
        return "admin/report";
    }

    @GetMapping("/api/admin/report/export")
    @Operation(summary = "报表导出（当前视图 CSV）",
            description = "section：summary/trend/categories/products/regions/origins/coupons/"
                    + "refunds/fulfillment/inventory/stock-alerts/customers/sources/reputation")
    @ResponseBody
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "summary") String section,
                                         @RequestParam(defaultValue = "30") String range,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(defaultValue = "day") String granularity) {
        StatsViews.Range view = analyticsService.resolveRange(range, from, to, granularity);
        byte[] body = csv(section, view);
        String ascii = "huayu-" + safe(section) + "-" + view.from() + "_" + view.to() + ".csv";
        String unicode = "花语轩报表-" + safe(section) + "-" + view.from() + "_" + view.to() + ".csv";
        String disposition = "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''"
                + URLEncoder.encode(unicode, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /** 导出内容 = 表头 + 数据行，全部走 StatsViews.csvRow 统一转义 */
    private byte[] csv(String section, StatsViews.Range view) {
        List<String> lines = new ArrayList<>();
        lines.add(StatsViews.csvRow("统计范围", view.label()));
        lines.add(StatsViews.csvRow("成交口径", "已支付及后续流转状态（不含待付款、取消、退款）"));
        switch (section == null ? "summary" : section) {
            case "trend" -> {
                lines.add(StatsViews.csvRow("时间桶", "成交额(元)", "成交单量", "客单价(元)"));
                for (StatsViews.TrendPoint point : analyticsService.trend(view)) {
                    lines.add(StatsViews.csvRow(point.label(), point.amount(), point.orders(), point.avgOrderAmount()));
                }
            }
            case "categories" -> {
                lines.add(StatsViews.csvRow("分类", "成交额(元)", "件数", "单量", "占比", "去年同期(元)", "同比"));
                for (StatsViews.CategoryShare row : analyticsService.categoryShare(view)) {
                    lines.add(StatsViews.csvRow(row.category(), row.amount(), row.units(), row.orders(),
                            StatsViews.percent(row.percent(), 1), row.yoyAmount(),
                            rateText(row.yoyRate())));
                }
            }
            case "products" -> {
                lines.add(StatsViews.csvRow("商品", "销量(件)", "成交额(元)", "成交额占比", "当前库存", "可售天数"));
                for (StatsViews.ProductSale row : analyticsService.topProducts(view, 30)) {
                    lines.add(StatsViews.csvRow(row.productName(), row.units(), row.amount(),
                            StatsViews.percent(row.amountPercent(), 1), row.stock(), row.coverDays()));
                }
            }
            case "regions" -> {
                lines.add(StatsViews.csvRow("城市", "单量", "枝数", "成交额(元)", "占比", "经度", "纬度"));
                for (StatsViews.RegionSale row : analyticsService.regions(view, 30)) {
                    lines.add(StatsViews.csvRow(row.city(), row.orders(), row.units(), row.amount(),
                            StatsViews.percent(row.percent(), 1), row.lng(), row.lat()));
                }
            }
            case "origins" -> {
                lines.add(StatsViews.csvRow("产地", "城市", "枝数", "单量", "占比"));
                for (StatsViews.OriginSale row : analyticsService.origins(view, 30)) {
                    lines.add(StatsViews.csvRow(row.name(), row.city(), row.units(), row.orders(),
                            StatsViews.percent(row.percent(), 1)));
                }
            }
            case "coupons" -> {
                StatsViews.CouponBoard board = analyticsService.coupons(view, 30);
                StatsViews.CouponSummary summary = board.summary();
                lines.add(StatsViews.csvRow("券模板数", summary.templates(), "发放", summary.issued(),
                        "核销", summary.used(), "核销率", StatsViews.percent(summary.redeemPercent(), 1),
                        "本期带动成交额(元)", summary.gmvInWindow(), "本期优惠(元)", summary.discountInWindow()));
                lines.add(StatsViews.csvRow());
                lines.add(StatsViews.csvRow("券名", "类型", "状态", "发放", "累计核销", "已过期", "核销率",
                        "本期核销", "本期带动成交额(元)", "本期抵扣(元)"));
                for (StatsViews.CouponPerf row : board.items()) {
                    lines.add(StatsViews.csvRow(row.name(), row.type(), row.status(), row.issued(), row.used(),
                            row.expired(), StatsViews.percent(row.redeemPercent(), 1), row.usedInWindow(),
                            row.gmvInWindow(), row.discountInWindow()));
                }
            }
            case "refunds" -> {
                StatsViews.RefundStat stat = analyticsService.refunds(view);
                lines.add(StatsViews.csvRow("成交单量", stat.dealOrders(), "退款单量", stat.refundOrders(),
                        "退款率", StatsViews.percent(stat.refundPercent(), 1),
                        "取消单量", stat.cancelOrders(), "取消率", StatsViews.percent(stat.cancelPercent(), 1)));
                lines.add(StatsViews.csvRow());
                lines.add(StatsViews.csvRow("类型", "原因", "单量", "金额(元)", "占比"));
                appendReasons(lines, "退款", stat.refundReasons());
                appendReasons(lines, "取消", stat.cancelReasons());
            }
            case "fulfillment" -> {
                StatsViews.Fulfillment data = analyticsService.fulfillment(view);
                lines.add(StatsViews.csvRow("承诺单量", data.promised(), "超时单量", data.late(),
                        "准时率", StatsViews.percent(data.onTimePercent(), 1)));
                lines.add(StatsViews.csvRow());
                lines.add(StatsViews.csvRow("环节", "P25(小时)", "中位(小时)", "P90(小时)", "样本"));
                for (StatsViews.TimingStage stage : data.stages()) {
                    lines.add(StatsViews.csvRow(stage.label(), stage.p25(), stage.p50(), stage.p90(), stage.samples()));
                }
            }
            case "inventory" -> {
                StatsViews.Inventory data = analyticsService.inventory(view, 30);
                lines.add(StatsViews.csvRow("窗口销量(件)", data.soldUnits(), "在库(件)", data.onHandStock(),
                        "库存周转率", data.turnoverRate(), "在售商品数", data.activeProducts(),
                        "告急商品数", data.alertCount()));
                lines.add(StatsViews.csvRow());
                lines.add(StatsViews.csvRow("商品", "窗口销量(件)", "成交额(元)", "当前库存", "周转率", "可售天数"));
                for (StatsViews.Turnover row : data.top()) {
                    lines.add(StatsViews.csvRow(row.productName(), row.units(), row.amount(), row.stock(),
                            row.turnoverRate(), row.coverDays()));
                }
            }
            case "stock-alerts" -> {
                lines.add(StatsViews.csvRow("商品", "当前库存", "窗口销量(件)", "可售天数", "告急原因"));
                for (StatsViews.StockAlert row : analyticsService.inventory(view, 30).alerts()) {
                    lines.add(StatsViews.csvRow(row.productName(), row.stock(), row.units(), row.coverDays(),
                            row.reason()));
                }
            }
            case "customers" -> {
                StatsViews.CustomerMix mix = analyticsService.customers(view);
                lines.add(StatsViews.csvRow("客群", "人数", "成交额(元)", "客单价(元)", "占成交人数"));
                lines.add(StatsViews.csvRow("新客", mix.newUsers(), mix.newAmount(), mix.avgNewAmount(),
                        StatsViews.percent(mix.newPercent(), 1)));
                lines.add(StatsViews.csvRow("老客", mix.oldUsers(), mix.oldAmount(), mix.avgOldAmount(),
                        StatsViews.percent(100d - mix.newPercent(), 1)));
            }
            case "sources" -> {
                lines.add(StatsViews.csvRow("首单品类", "新客数", "成交额(元)", "占新客"));
                for (StatsViews.Source row : analyticsService.customers(view).sources()) {
                    lines.add(StatsViews.csvRow(row.name(), row.users(), row.amount(),
                            StatsViews.percent(row.percent(), 1)));
                }
            }
            case "reputation" -> {
                StatsViews.Reputation data = analyticsService.reputation(view);
                lines.add(StatsViews.csvRow("评价数", data.reviews(), "平均分", data.avgRating(),
                        "差评数", data.bad(), "差评率", StatsViews.percent(data.badPercent(), 1),
                        "好评(5星)", data.great(), "好评率", StatsViews.percent(data.greatPercent(), 1)));
            }
            default -> {
                StatsViews.Kpi kpi = analyticsService.kpi(view);
                lines.add(StatsViews.csvRow("指标", "本期", "上期变化率", "标记"));
                line(lines, "成交额(元)", kpi.gmv(), kpi.gmvDelta());
                line(lines, "成交单量", kpi.dealOrders(), kpi.orderDelta());
                line(lines, "客单价(元)", kpi.avgOrderAmount(), kpi.avgDelta());
                line(lines, "支付转化率(%)", kpi.payConvertPercent(), kpi.convertDelta());
                line(lines, "复购率(%)", kpi.repurchasePercent(), kpi.repurchaseDelta());
                line(lines, "退款率(%)", kpi.refundPercent(), kpi.refundDelta());
                lines.add(StatsViews.csvRow());
                lines.add(StatsViews.csvRow("下单量", kpi.createdOrders(), "成交用户数", kpi.buyers(),
                        "复购用户数", kpi.repeatBuyers(), "退款单量", kpi.refundOrders(),
                        "取消单量", kpi.cancelOrders(), "累计成交额(元)", kpi.totalGmv()));
                for (StatsViews.Anomaly item : analyticsService.dashboard(view).anomalies()) {
                    lines.add(StatsViews.csvRow("异常提示", item.metric(), item.text()));
                }
            }
        }
        String text = String.join("\r\n", lines) + "\r\n";
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[BOM.length + payload.length];
        System.arraycopy(BOM, 0, withBom, 0, BOM.length);
        System.arraycopy(payload, 0, withBom, BOM.length, payload.length);
        return withBom;
    }

    private static void appendReasons(List<String> lines, String type, List<StatsViews.Reason> reasons) {
        if (reasons.isEmpty()) {
            lines.add(StatsViews.csvRow(type, "本期无记录", 0, 0, ""));
            return;
        }
        for (StatsViews.Reason reason : reasons) {
            lines.add(StatsViews.csvRow(type, reason.name(), reason.orders(), reason.amount(),
                    StatsViews.percent(reason.percent(), 1)));
        }
    }

    private static void line(List<String> lines, String label, Object value, StatsViews.Delta delta) {
        lines.add(StatsViews.csvRow(label, value, rateText(delta == null ? null : delta.changeRate()),
                mark(delta)));
    }

    private static String mark(StatsViews.Delta delta) {
        if (delta == null || StatsViews.LEVEL_FLAT.equals(delta.level())) {
            return "";
        }
        return StatsViews.LEVEL_DOWN.equals(delta.level()) ? "异常下滑" : "异常上涨";
    }

    /** 变化率写成有符号百分数，方便直接进 Excel 做条件格式 */
    private static String rateText(Double rate) {
        if (rate == null) {
            return "—";
        }
        return (rate >= 0 ? "+" : "") + StatsViews.percent(rate * 100d, 1);
    }

    /** section 参与文件名，先过滤成安全字符，避免路径穿越或非法文件名 */
    private static String safe(String section) {
        return section == null ? "summary" : section.replaceAll("[^A-Za-z0-9_-]", "");
    }
}
