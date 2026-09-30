package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.vo.StatsViews;
import org.liuym.flowerv1springboot.vo.StatsViews.Range;

import java.time.LocalDate;
import java.util.List;

/**
 * 数据看板与报表（H 组）的取数与口径装配。
 *
 * <p>调用方先用 {@link #resolveRange} 把请求参数换成一个 {@link Range}（含粒度），再取各张图卡：
 * 这样首屏、报表中心与 CSV 导出共用同一个 Range，也就共用同一批缓存条目，换范围不会重复跑聚合。
 */
public interface AnalyticsService {

    /** 最大可查跨度（天）：自定义范围超限即收敛，避免一次拉两年的单把聚合拖垮 */
    int MAX_SPAN_DAYS = 366;

    /** 解析时间范围（H01）：range 支持 7/30/90/custom，自定义时取 from/to */
    Range resolveRange(String range, LocalDate from, LocalDate to, String granularity);

    /** 看板首屏（H01/H02/H03/H13/H14）：KPI + 趋势 + 异常标记 + 数据生成时间 */
    StatsViews.Dashboard dashboard(Range range);

    /** 指标卡（H03）：客单价、支付转化率、复购率、退款率，附环比（H14） */
    StatsViews.Kpi kpi(Range range);

    /** 销售趋势（H02）：按 range.granularity 的天/周/月聚合 */
    List<StatsViews.TrendPoint> trend(Range range);

    /** 分类销售占比与同比（H04） */
    List<StatsViews.CategoryShare> categoryShare(Range range);

    /** 商品销量 TopN（H05）：并列给出库存与可售天数 */
    List<StatsViews.ProductSale> topProducts(Range range, int limit);

    /** 地区销售榜（H06）：含经纬度，可直接联动地图 */
    List<StatsViews.RegionSale> regions(Range range, int limit);

    /** 产地发货榜（H06 联动鲜花地图） */
    List<StatsViews.OriginSale> origins(Range range, int limit);

    /** 优惠券核销率与带动 GMV（H07） */
    StatsViews.CouponBoard coupons(Range range, int limit);

    /** 退款率与原因分布（H08） */
    StatsViews.RefundStat refunds(Range range);

    /** 履约时效分位（H09） */
    StatsViews.Fulfillment fulfillment(Range range);

    /** 库存周转与告急清单（H10） */
    StatsViews.Inventory inventory(Range range, int limit);

    /** 新客/老客构成与来源（H11） */
    StatsViews.CustomerMix customers(Range range);

    /** 口碑卡：评价量与差评占比，报表页与退款一起看 */
    StatsViews.Reputation reputation(Range range);

    /** 后台首页待办卡（G27）：待发货 / 待审核退款 / 库存告急，固定按「当下」口径，不跟随看板时间范围 */
    StatsViews.TodoBoard todos();

    /** 清空看板缓存（H13）：手动刷新按钮调用，避免 60s TTL 内读到旧值 */
    void clearCache();
}
