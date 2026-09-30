package org.liuym.flowerv1springboot.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 看板/报表专用只读聚合层：所有统计都在数据库里 GROUP BY 完成，只把聚合行（几十到几百行）带回应用。
 *
 * <p>刻意不复用别人的 Repository：统计口径会频繁调整，写在这里改错了也只影响看板，
 * 不会把下单、发货等业务查询一起带偏。
 *
 * <p>口径约定（全站唯一）：
 * <ul>
 *   <li>成交类指标（GMV / 客单价 / 复购 / 品类 / 商品 / 地区 / 券带动）＝ status ∈ DEAL_STATUSES
 *       且成交时间落在窗口内；待付款没收到钱、取消与退款已把钱退回，都不算成交。</li>
 *   <li>成交时间取 {@code COALESCE(pay_time, created_at)}：历史脏数据里存在已支付却没写 pay_time 的单，
 *       若按 pay_time 过滤这些单会被静默丢掉，成交额会凭空少一截。</li>
 *   <li>订单维度的金额用 pay_amount（实付），品类/商品维度用 order_item.subtotal（成交额构成），
 *       两者相差优惠券与运费，报表里分开标注不混用。</li>
 *   <li>漏斗类指标（下单数、支付转化率）按 created_at 归属；退款/取消按 finish_time（关闭时刻）归属，
 *       缺失时退回 updated_at。</li>
 * </ul>
 */
@Repository
public class AnalyticsRepository {

    /** 成交时间表达式（订单别名固定为 o），所有成交类查询共用，避免各处写法漂移 */
    private static final String DEAL_TIME = "COALESCE(o.pay_time, o.created_at)";

    /** 窗口内成交订单的谓词片段：:deal / :from / :to 三个命名参数由 bind() 统一提供 */
    private static final String DEAL_IN_WINDOW =
            "o.status IN (:deal) AND " + DEAL_TIME + " >= :from AND " + DEAL_TIME + " < :to";

    /** 跨批次表存在性探测结果，进程内缓存一次即可 */
    private static final Map<String, Boolean> RELATION_CACHE = new ConcurrentHashMap<>();

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsRepository(DataSource dataSource) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
    }

    /* ─────────────── H01/H02 销售趋势 ─────────────── */

    /** 按天聚合的成交金额与单量；周/月由服务层在这些日桶上再上卷 */
    public List<DayBucket> salesByDay(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT to_char(" + DEAL_TIME + ", 'YYYY-MM-DD') AS day, "
                + "COALESCE(SUM(o.pay_amount), 0) AS amount, COUNT(o.id) AS orders "
                + "FROM \"order\" o WHERE " + DEAL_IN_WINDOW + " GROUP BY 1 ORDER BY 1";
        return jdbc.query(sql, bind(deal, from, to),
                (rs, i) -> new DayBucket(rs.getString("day"), rs.getBigDecimal("amount"), rs.getLong("orders")));
    }

    /* ─────────────── H03 KPI：漏斗 / 复购 ─────────────── */

    /** 窗口内创建的订单按状态分组，用于支付转化率与取消/退款占比的分母 */
    public List<StatusCount> statusByCreatedTime(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT o.status AS status, COUNT(o.id) AS orders, COALESCE(SUM(o.pay_amount), 0) AS amount "
                + "FROM \"order\" o WHERE o.created_at >= :from AND o.created_at < :to "
                + "GROUP BY o.status ORDER BY orders DESC";
        return jdbc.query(sql, bind(deal, from, to),
                (rs, i) -> new StatusCount(rs.getString("status"), rs.getLong("orders"), rs.getBigDecimal("amount")));
    }

    /**
     * 复购率：窗口内成交 ≥2 单的用户占成交用户的比例。
     * 内层先按 user_id 聚合，外层再数人头，全程没有把订单捞回应用；顺带把 GMV、单量一次取回。
     */
    public Repurchase repurchase(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COUNT(*) AS buyers, COALESCE(SUM(t.orders), 0) AS orders, "
                + "COALESCE(SUM(t.amount), 0) AS amount, "
                + "COUNT(*) FILTER (WHERE t.orders >= 2) AS repeat_buyers "
                + "FROM (SELECT o.user_id AS uid, COUNT(o.id) AS orders, SUM(o.pay_amount) AS amount "
                + "FROM \"order\" o WHERE " + DEAL_IN_WINDOW + " GROUP BY o.user_id) t";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new Repurchase(
                        rs.getLong("buyers"), rs.getLong("orders"), amount(rs, "amount"),
                        rs.getLong("repeat_buyers")))
                .stream().findFirst().orElseGet(Repurchase::empty);
    }

    /** 累计口径（不受时间窗影响），概览卡的「累计成交额」用 */
    public BigDecimal totalDealAmount(List<String> deal) {
        BigDecimal value = jdbc.queryForObject("SELECT COALESCE(SUM(o.pay_amount), 0) FROM \"order\" o WHERE o.status IN (:deal)",
                new MapSqlParameterSource("deal", deal), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** 窗口内成交总额与成交单量（订单维度，实付口径） */
    public DealTotals dealTotals(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE(SUM(o.pay_amount), 0) AS amount, COUNT(o.id) AS orders "
                + "FROM \"order\" o WHERE " + DEAL_IN_WINDOW;
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new DealTotals(
                amount(rs, "amount"), rs.getLong("orders"))).stream().findFirst().orElseGet(DealTotals::empty);
    }

    /** TopN 商品的实时库存：销量榜要并列展示库存与可售天数 */
    public Map<UUID, Integer> stockOf(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT p.id AS product_id, p.stock AS stock FROM product p WHERE p.id IN (:ids)";
        Map<UUID, Integer> out = new LinkedHashMap<>();
        jdbc.query(sql, new MapSqlParameterSource("ids", productIds), (rs, i) -> {
            out.put(uuid(rs.getString("product_id")), rs.getInt("stock"));
            return out;
        });
        return out;
    }

    /* ─────────────── H04 品类占比与同比 ─────────────── */

    public List<CategorySale> categorySales(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE(c.name, '未分类') AS category, COALESCE(SUM(i.subtotal), 0) AS amount, "
                + "COALESCE(SUM(i.quantity), 0) AS units, COUNT(DISTINCT i.order_id) AS orders "
                + "FROM order_item i JOIN \"order\" o ON o.id = i.order_id "
                + "LEFT JOIN product p ON p.id = i.product_id LEFT JOIN category c ON c.id = p.category_id "
                + "WHERE " + DEAL_IN_WINDOW + " GROUP BY 1 ORDER BY amount DESC";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new CategorySale(
                rs.getString("category"), amount(rs, "amount"), rs.getLong("units"), rs.getLong("orders")));
    }

    /** 成交商品总额（order_item.subtotal 口径）：品类/商品占比的分母，与实付 GMV 分开算不混用 */
    public BigDecimal dealItemAmount(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE(SUM(i.subtotal), 0) AS amount FROM order_item i "
                + "JOIN \"order\" o ON o.id = i.order_id WHERE " + DEAL_IN_WINDOW;
        BigDecimal value = jdbc.queryForObject(sql, bind(deal, from, to), BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    /* ─────────────── H05 商品销量 TopN ─────────────── */

    public List<ProductSale> productSales(List<String> deal, LocalDateTime from, LocalDateTime to, int limit) {
        String sql = "SELECT i.product_id AS product_id, MAX(i.product_name) AS product_name, "
                + "COALESCE(SUM(i.quantity), 0) AS units, COALESCE(SUM(i.subtotal), 0) AS amount "
                + "FROM order_item i JOIN \"order\" o ON o.id = i.order_id WHERE " + DEAL_IN_WINDOW + " "
                + "GROUP BY i.product_id ORDER BY units DESC, amount DESC LIMIT :limit";
        return jdbc.query(sql, bind(deal, from, to).addValue("limit", limit), (rs, i) -> new ProductSale(
                uuid(rs.getString("product_id")), rs.getString("product_name"),
                rs.getLong("units"), amount(rs, "amount")));
    }

    /* ─────────────── H06 地区销售榜 / 产地榜 ─────────────── */

    /**
     * 一单一行（明细已折叠），收货地址留给服务层用 CityGeo 判城：
     * 地址是自由文本，在 SQL 里正则取「市」既会漏也会错，口径集中到一处更稳。
     */
    public List<OrderGeo> dealOrdersWithAddress(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT o.receiver_address AS addr, COALESCE(o.pay_amount, 0) AS amount, "
                + "COALESCE(SUM(i.quantity), 0) AS units FROM \"order\" o JOIN order_item i ON i.order_id = o.id "
                + "WHERE " + DEAL_IN_WINDOW + " GROUP BY o.id, o.receiver_address, o.pay_amount";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new OrderGeo(
                rs.getString("addr"), amount(rs, "amount"), rs.getLong("units")));
    }

    /** 产地发货量：与前台鲜花地图同一套 flower_origin 节点，看板点进去能接着看地图 */
    public List<OriginSale> originSales(List<String> deal, LocalDateTime from, LocalDateTime to, int limit) {
        String sql = "SELECT fo.name AS name, COALESCE(fo.city, fo.province, '') AS city, "
                + "COALESCE(SUM(i.quantity), 0) AS units, COUNT(DISTINCT o.id) AS orders "
                + "FROM order_item i JOIN \"order\" o ON o.id = i.order_id "
                + "JOIN product p ON p.id = i.product_id JOIN flower_origin fo ON fo.id = p.origin_id "
                + "WHERE " + DEAL_IN_WINDOW + " GROUP BY fo.id, fo.name, fo.city, fo.sort_order "
                + "ORDER BY units DESC LIMIT :limit";
        return jdbc.query(sql, bind(deal, from, to).addValue("limit", limit), (rs, i) -> new OriginSale(
                rs.getString("name"), rs.getString("city"), rs.getLong("units"), rs.getLong("orders")));
    }

    /* ─────────────── H07 优惠券核销与带动 GMV ─────────────── */

    public List<CouponPerf> couponPerformance(List<String> deal, LocalDateTime from, LocalDateTime to, int limit) {
        String win = "o.status IN (:deal) AND " + DEAL_TIME + " >= :from AND " + DEAL_TIME + " < :to";
        String sql = "SELECT cp.id AS coupon_id, cp.name AS name, cp.type AS type, cp.status AS status, "
                + "COUNT(uc.id) AS issued, COUNT(uc.id) FILTER (WHERE uc.status = 'used') AS used, "
                + "COUNT(uc.id) FILTER (WHERE uc.status = 'expired') AS expired, "
                + "COUNT(uc.id) FILTER (WHERE uc.used_at >= :from AND uc.used_at < :to) AS used_win, "
                + "COALESCE(SUM(o.pay_amount) FILTER (WHERE " + win + "), 0) AS gmv_win, "
                + "COALESCE(SUM(o.coupon_amount) FILTER (WHERE " + win + "), 0) AS discount_win "
                + "FROM coupon cp LEFT JOIN user_coupon uc ON uc.coupon_id = cp.id "
                + "LEFT JOIN \"order\" o ON o.user_coupon_id = uc.id "
                + "GROUP BY cp.id, cp.name, cp.type, cp.status ORDER BY gmv_win DESC, used DESC LIMIT :limit";
        return jdbc.query(sql, bind(deal, from, to).addValue("limit", limit), (rs, i) -> new CouponPerf(
                uuid(rs.getString("coupon_id")), rs.getString("name"), rs.getString("type"), rs.getString("status"),
                rs.getLong("issued"), rs.getLong("used"), rs.getLong("expired"), rs.getLong("used_win"),
                amount(rs, "gmv_win"), amount(rs, "discount_win")));
    }

    /** 全站券汇总：核销率与带动 GMV 的总数，必须覆盖全部券模板（不能只统计 TopN 那几行） */
    public CouponTotal couponTotal(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String win = "o.status IN (:deal) AND " + DEAL_TIME + " >= :from AND " + DEAL_TIME + " < :to";
        String sql = "SELECT COUNT(uc.id) AS issued, COUNT(uc.id) FILTER (WHERE uc.status = 'used') AS used, "
                + "COUNT(uc.id) FILTER (WHERE uc.status = 'expired') AS expired, "
                + "COUNT(o.id) FILTER (WHERE " + win + ") AS orders_win, "
                + "COALESCE(SUM(o.pay_amount) FILTER (WHERE " + win + "), 0) AS gmv_win, "
                + "COALESCE(SUM(o.coupon_amount) FILTER (WHERE " + win + "), 0) AS discount_win "
                + "FROM user_coupon uc LEFT JOIN \"order\" o ON o.user_coupon_id = uc.id";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new CouponTotal(
                rs.getLong("issued"), rs.getLong("used"), rs.getLong("expired"),
                rs.getLong("orders_win"), amount(rs, "gmv_win"), amount(rs, "discount_win")))
                .stream().findFirst().orElseGet(CouponTotal::empty);
    }

    public long couponTemplateCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(c.id) FROM coupon c", new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /* ─────────────── H08 退款率与原因 ─────────────── */

    /** 关闭（取消/退款）原因分布：按关闭时刻归属窗口，finish_time 缺失退回 updated_at */
    public List<ReasonRow> closeReasons(List<String> statuses, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE(NULLIF(TRIM(o.cancel_reason), ''), '未填写原因') AS reason, "
                + "COUNT(o.id) AS orders, COALESCE(SUM(o.pay_amount), 0) AS amount "
                + "FROM \"order\" o WHERE o.status IN (:statuses) "
                + "AND COALESCE(o.finish_time, o.updated_at) >= :from AND COALESCE(o.finish_time, o.updated_at) < :to "
                + "GROUP BY 1 ORDER BY orders DESC";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("statuses", statuses)
                .addValue("from", ts(from))
                .addValue("to", ts(to));
        return jdbc.query(sql, params, (rs, i) -> new ReasonRow(
                rs.getString("reason"), rs.getLong("orders"), amount(rs, "amount")));
    }

    /* ─────────────── H09 履约时效 ─────────────── */

    /**
     * 三段时长的分位数（小时）。发货→签收没有独立时间列，取「已签收」单的 updated_at 近似：
     * 签收之后到确认完成之间一般没有其他写入，偏差可控，服务层已标注该口径。
     */
    public List<TimingRow> fulfillmentTiming(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String createdToPaid = "GREATEST(EXTRACT(EPOCH FROM (o.pay_time - o.created_at)) / 3600.0, 0)";
        String createdToShip = "GREATEST(EXTRACT(EPOCH FROM (o.ship_time - o.created_at)) / 3600.0, 0)";
        String createdToSign = "GREATEST(EXTRACT(EPOCH FROM (o.updated_at - o.created_at)) / 3600.0, 0)";
        String shipToSign = "GREATEST(EXTRACT(EPOCH FROM (o.updated_at - o.ship_time)) / 3600.0, 0)";
        String window = "o.created_at >= :from AND o.created_at < :to";
        String sql = "SELECT 'created_to_paid' AS seg, percentile_cont(0.25) WITHIN GROUP (ORDER BY " + createdToPaid + ") AS p25, "
                + "percentile_cont(0.50) WITHIN GROUP (ORDER BY " + createdToPaid + ") AS p50, "
                + "percentile_cont(0.90) WITHIN GROUP (ORDER BY " + createdToPaid + ") AS p90, COUNT(*) AS samples "
                + "FROM \"order\" o WHERE o.pay_time IS NOT NULL AND " + window
                + " UNION ALL "
                + "SELECT 'created_to_ship', percentile_cont(0.25) WITHIN GROUP (ORDER BY " + createdToShip + "), "
                + "percentile_cont(0.50) WITHIN GROUP (ORDER BY " + createdToShip + "), "
                + "percentile_cont(0.90) WITHIN GROUP (ORDER BY " + createdToShip + "), COUNT(*) "
                + "FROM \"order\" o WHERE o.ship_time IS NOT NULL AND " + window
                + " UNION ALL "
                + "SELECT 'ship_to_sign', percentile_cont(0.25) WITHIN GROUP (ORDER BY " + shipToSign + "), "
                + "percentile_cont(0.50) WITHIN GROUP (ORDER BY " + shipToSign + "), "
                + "percentile_cont(0.90) WITHIN GROUP (ORDER BY " + shipToSign + "), COUNT(*) "
                + "FROM \"order\" o WHERE o.ship_time IS NOT NULL AND o.status = 'delivered' AND " + window
                + " UNION ALL "
                + "SELECT 'created_to_sign', percentile_cont(0.25) WITHIN GROUP (ORDER BY " + createdToSign + "), "
                + "percentile_cont(0.50) WITHIN GROUP (ORDER BY " + createdToSign + "), "
                + "percentile_cont(0.90) WITHIN GROUP (ORDER BY " + createdToSign + "), COUNT(*) "
                + "FROM \"order\" o WHERE o.ship_time IS NOT NULL AND o.status = 'delivered' AND " + window;
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> {
            double p25 = rs.getDouble("p25");
            boolean none = rs.wasNull() || rs.getLong("samples") == 0;
            return new TimingRow(rs.getString("seg"),
                    none ? null : round(p25), none ? null : round(rs.getDouble("p50")),
                    none ? null : round(rs.getDouble("p90")), rs.getLong("samples"));
        });
    }

    /** 承诺达成：窗口内已签收且写了预计送达时间的单，签收时刻是否超约 */
    public FulfillCompliance fulfillCompliance(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COUNT(*) AS promised, "
                + "COUNT(*) FILTER (WHERE o.updated_at > o.expected_arrive_at) AS late "
                + "FROM \"order\" o WHERE o.status = 'delivered' AND o.expected_arrive_at IS NOT NULL "
                + "AND o.created_at >= :from AND o.created_at < :to";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new FulfillCompliance(
                rs.getLong("promised"), rs.getLong("late"))).stream().findFirst().orElseGet(FulfillCompliance::empty);
    }

    /* ─────────────── H10 库存周转与告急 ─────────────── */

    public List<StockRow> stockTurnover(List<String> deal, LocalDateTime from, LocalDateTime to, int limit) {
        String sql = "SELECT p.id AS product_id, p.name AS product_name, p.stock AS stock, "
                + "COALESCE(SUM(i.quantity), 0) AS units, COALESCE(SUM(i.subtotal), 0) AS amount "
                + "FROM order_item i JOIN \"order\" o ON o.id = i.order_id JOIN product p ON p.id = i.product_id "
                + "WHERE " + DEAL_IN_WINDOW + " AND p.is_active = true "
                + "GROUP BY p.id, p.name, p.stock ORDER BY units DESC, amount DESC LIMIT :limit";
        return jdbc.query(sql, bind(deal, from, to).addValue("limit", limit), this::toStockRow);
    }

    /**
     * 告急清单在 SQL 里判定：可售天数 = 库存 / 日均销量 &lt; 覆盖天数，或库存已低于绝对下限。
     * 阈值由服务层传入（集中在 StatsViews.Thresholds），改口径不用动 SQL。
     */
    public List<StockRow> stockAlerts(List<String> deal, LocalDateTime from, LocalDateTime to,
                                      int spanDays, double coverDays, int floor, int limit) {
        String sql = alertBaseSql() + " ORDER BY (p.stock::numeric / NULLIF(s.units, 0)) NULLS FIRST, p.stock ASC"
                + " LIMIT :limit";
        MapSqlParameterSource params = bind(deal, from, to)
                .addValue("floor", floor).addValue("spanDays", spanDays)
                .addValue("coverDays", coverDays).addValue("limit", limit);
        return jdbc.query(sql, params, this::toStockRow);
    }

    /** 全店周转概览：窗口内销量总量与在售库存总量，用于「库存周转率」一张卡 */
    public StockSummary stockSummary(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE((SELECT SUM(i.quantity) FROM order_item i JOIN \"order\" o ON o.id = i.order_id "
                + "WHERE " + DEAL_IN_WINDOW + "), 0) AS units, "
                + "COALESCE((SELECT SUM(p.stock) FROM product p WHERE p.is_active = true), 0) AS stock, "
                + "COUNT(p.id) FILTER (WHERE p.is_active = true) AS active_products "
                + "FROM product p";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new StockSummary(
                rs.getLong("units"), rs.getLong("stock"), rs.getLong("active_products")))
                .stream().findFirst().orElseGet(StockSummary::empty);
    }

    /* ─────────────── H11 新客/老客构成与来源 ─────────────── */

    /** 新客＝窗口内完成首笔成交的用户；首单时间来自全历史，不受窗口截断影响 */
    public List<MixRow> customerMix(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT CASE WHEN f.first_time >= :from THEN 'new' ELSE 'old' END AS bucket, "
                + "COUNT(*) AS users, COALESCE(SUM(w.orders), 0) AS orders, COALESCE(SUM(w.amount), 0) AS amount "
                + "FROM (SELECT o.user_id AS uid, COUNT(o.id) AS orders, SUM(o.pay_amount) AS amount "
                + "FROM \"order\" o WHERE " + DEAL_IN_WINDOW + " GROUP BY o.user_id) w "
                + "JOIN (SELECT o.user_id AS uid, MIN(" + DEAL_TIME + ") AS first_time FROM \"order\" o "
                + "WHERE o.status IN (:deal) AND " + DEAL_TIME + " < :to GROUP BY o.user_id) f ON f.uid = w.uid "
                + "GROUP BY 1";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("deal", deal).addValue("from", ts(from)).addValue("to", ts(to));
        return jdbc.query(sql, params, (rs, i) -> new MixRow(
                rs.getString("bucket"), rs.getLong("users"), rs.getLong("orders"), amount(rs, "amount")));
    }

    /**
     * 新客来源＝首单买的品类：NOT EXISTS 判定「本单是该用户历史第一笔成交」，
     * 因此本单落在窗口内就意味着这个新客是本期拉进来的，不存在把老客复购算成新客。
     */
    public List<SourceRow> newCustomerSources(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COALESCE(c.name, '未分类') AS source, COUNT(DISTINCT o.user_id) AS users, "
                + "COALESCE(SUM(i.subtotal), 0) AS amount FROM \"order\" o "
                + "JOIN order_item i ON i.order_id = o.id "
                + "LEFT JOIN product p ON p.id = i.product_id LEFT JOIN category c ON c.id = p.category_id "
                + "WHERE " + DEAL_IN_WINDOW + " AND NOT EXISTS (SELECT 1 FROM \"order\" o2 "
                + "WHERE o2.user_id = o.user_id AND o2.status IN (:deal) AND o2.id <> o.id "
                + "AND " + DEAL_TIME.replace("o.", "o2.") + " < " + DEAL_TIME + ") "
                + "GROUP BY 1 ORDER BY users DESC, amount DESC";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new SourceRow(
                rs.getString("source"), rs.getLong("users"), amount(rs, "amount")));
    }

    /* ─────────────── 口碑（报表页辅助卡） ─────────────── */

    public RatingRow ratingWindow(List<String> deal, LocalDateTime from, LocalDateTime to) {
        String sql = "SELECT COUNT(r.id) AS reviews, COALESCE(AVG(r.rating), 0) AS avg_rating, "
                + "COUNT(r.id) FILTER (WHERE r.rating <= 2) AS bad, "
                + "COUNT(r.id) FILTER (WHERE r.rating = 5) AS great "
                + "FROM review r WHERE r.visible = true AND r.created_at >= :from AND r.created_at < :to";
        return jdbc.query(sql, bind(deal, from, to), (rs, i) -> new RatingRow(
                rs.getLong("reviews"), round(rs.getDouble("avg_rating")), rs.getLong("bad"), rs.getLong("great")))
                .stream().findFirst().orElseGet(RatingRow::empty);
    }

    /* ─────────────── G27 后台首页待办 ─────────────── */

    /** 待发货：已付款或处理中、但发货时间还没落的单。待付款不计入——那时还不该备货。 */
    public long awaitingShipCount(List<String> statuses) {
        String sql = "SELECT COUNT(o.id) FROM \"order\" o WHERE o.status IN (:st) AND o.ship_time IS NULL";
        Long count = jdbc.queryForObject(sql, new MapSqlParameterSource("st", statuses), Long.class);
        return count == null ? 0L : count;
    }

    /** 待审核退款单数。退款流程（C19）的表尚未建库时返回 null，界面写「未启用」，不能报 0 冒充「没有待办」 */
    public Long pendingRefundCount() {
        if (!tableExists("order_refund")) {
            return null;
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(r.id) FROM order_refund r WHERE r.status IN ('pending', 'reviewing')",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0L : count;
    }

    /** 告急款数：与 {@link #stockAlerts} 共用同一段谓词，待办卡与报表清单绝不会各说一套 */
    public long stockAlertCount(List<String> deal, LocalDateTime from, LocalDateTime to,
                                int spanDays, double coverDays, int floor) {
        String sql = "SELECT COUNT(x.product_id) FROM (" + alertBaseSql() + ") x";
        Long count = jdbc.queryForObject(sql, bind(deal, from, to)
                .addValue("floor", floor).addValue("spanDays", spanDays).addValue("coverDays", coverDays), Long.class);
        return count == null ? 0L : count;
    }

    /* ─────────────── 私有工具 ─────────────── */

    /** 告急谓词的公共部分：可售天数 = 库存 / 日均销量 &lt; 覆盖天数，或库存已低于绝对下限 */
    private String alertBaseSql() {
        return "SELECT p.id AS product_id, p.name AS product_name, p.stock AS stock, "
                + "COALESCE(s.units, 0) AS units, COALESCE(s.amount, 0) AS amount FROM product p "
                + "LEFT JOIN (SELECT i.product_id AS pid, SUM(i.quantity) AS units, SUM(i.subtotal) AS amount "
                + "FROM order_item i JOIN \"order\" o ON o.id = i.order_id WHERE " + DEAL_IN_WINDOW + " "
                + "GROUP BY i.product_id) s ON s.pid = p.id "
                + "WHERE p.is_active = true AND (p.stock <= :floor "
                + "OR (COALESCE(s.units, 0) > 0 AND p.stock * :spanDays < :coverDays * COALESCE(s.units, 0)))";
    }

    /**
     * 关系（表）是否已建好。跨批次并行施工时，订单/退款批次的新表可能还没随应用启动落库，
     * 看板不能因为一次「表不存在」整块报错，所以先探测再取数；结果进程内缓存，不会每次刷新都查字典。
     */
    private boolean tableExists(String table) {
        return RELATION_CACHE.computeIfAbsent(table, name -> !jdbc.queryForList(
                "SELECT 1 FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = :name",
                new MapSqlParameterSource("name", name), Integer.class).isEmpty());
    }

    /* ─────────────── 私有工具 ─────────────── */

    /** deal 列表恒不为空（DEAL_STATUSES 是常量），from/to 转 Timestamp 以避开 PG 的参数类型推断歧义 */
    private MapSqlParameterSource bind(List<String> deal, LocalDateTime from, LocalDateTime to) {
        return new MapSqlParameterSource()
                .addValue("deal", deal)
                .addValue("from", ts(from))
                .addValue("to", ts(to));
    }

    private static Timestamp ts(LocalDateTime value) {
        return Timestamp.valueOf(value);
    }

    private StockRow toStockRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new StockRow(uuid(rs.getString("product_id")), rs.getString("product_name"),
                rs.getInt("stock"), rs.getLong("units"), amount(rs, "amount"));
    }

    private static BigDecimal amount(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static UUID uuid(String raw) {
        return raw == null || raw.isBlank() ? null : UUID.fromString(raw);
    }

    /** 分位数与均值保留三位小数：看板只展示到 0.1 小时，多留一位便于环比比较 */
    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /* ─────────────── 聚合行 ─────────────── */

    public record DayBucket(String day, BigDecimal amount, long orders) {
    }

    public record StatusCount(String status, long orders, BigDecimal amount) {
    }

    public record Repurchase(long buyers, long orders, BigDecimal amount, long repeatBuyers) {
        public static Repurchase empty() {
            return new Repurchase(0, 0, BigDecimal.ZERO, 0);
        }
    }

    public record DealTotals(BigDecimal amount, long orders) {
        public static DealTotals empty() {
            return new DealTotals(BigDecimal.ZERO, 0);
        }
    }

    public record CategorySale(String category, BigDecimal amount, long units, long orders) {
    }

    public record ProductSale(UUID productId, String productName, long units, BigDecimal amount) {
    }

    public record OrderGeo(String address, BigDecimal amount, long units) {
    }

    public record OriginSale(String name, String city, long units, long orders) {
    }

    public record CouponPerf(UUID couponId, String name, String type, String status, long issued, long used,
                             long expired, long usedInWindow, BigDecimal gmvInWindow, BigDecimal discountInWindow) {
    }

    public record CouponTotal(long issued, long used, long expired, long ordersInWindow,
                              BigDecimal gmvInWindow, BigDecimal discountInWindow) {
        public static CouponTotal empty() {
            return new CouponTotal(0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    public record ReasonRow(String reason, long orders, BigDecimal amount) {
    }

    public record TimingRow(String segment, Double p25, Double p50, Double p90, long samples) {
    }

    public record FulfillCompliance(long promised, long late) {
        public static FulfillCompliance empty() {
            return new FulfillCompliance(0, 0);
        }
    }

    public record StockRow(UUID productId, String productName, int stock, long units, BigDecimal amount) {
    }

    public record StockSummary(long units, long stock, long activeProducts) {
        public static StockSummary empty() {
            return new StockSummary(0, 0, 0);
        }
    }

    public record MixRow(String bucket, long users, long orders, BigDecimal amount) {
        /** 窗口内没有对应客群时用的零值，避免服务层到处判空 */
        public static final MixRow ZERO = new MixRow("none", 0, 0, BigDecimal.ZERO);
    }

    public record SourceRow(String source, long users, BigDecimal amount) {
    }

    public record RatingRow(long reviews, double avgRating, long bad, long great) {
        public static RatingRow empty() {
            return new RatingRow(0, 0, 0, 0);
        }
    }
}
