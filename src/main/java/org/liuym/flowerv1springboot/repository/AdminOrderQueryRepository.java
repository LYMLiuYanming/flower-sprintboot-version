package org.liuym.flowerv1springboot.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.RefundStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台订单操作批次（C11–C14 / C21–C25 / G19–G21）专属的数据访问。
 *
 * <p>本批次与并行的订单主改造共用 Order / OrderItem / order_trace，为避免往别人的 Repository
 * 加方法，这里自带读侧与「条件 UPDATE」写侧：写一律 `WHERE status = :期望` 并回传影响行数，
 * 由调用方按 0 行判定并发冲突。新建的四张表（物流公司字典与三张留痕表）没有实体映射，只能走原生 SQL。
 */
@Repository
public class AdminOrderQueryRepository {

    /** 排序键全部是白名单常量，前端传进来的字符串永远进不了 ORDER BY */
    private static final String ORDER_BY_CREATED = " ORDER BY o.createdAt DESC, o.id DESC";
    private static final String ORDER_BY_DUE = " ORDER BY COALESCE(o.expectedArriveAt, o.createdAt) ASC, o.id DESC";

    @Autowired
    private EntityManager entityManager;

    // ------------------------------------------------------------------ 物流公司字典（C11/C12）

    /** 启用中的物流公司：code, name, no_pattern, no_example, no_hint, track_host, track_path */
    @Transactional(readOnly = true)
    public List<Object[]> activeCompanies() {
        Query query = entityManager.createNativeQuery("""
                SELECT code, name, no_pattern, no_example, no_hint, track_host, track_path
                FROM shipping_company WHERE is_active = true ORDER BY sort_order, name""");
        return castRows(query.getResultList());
    }

    // ------------------------------------------------------------------ 订单读（C25/G21 列表与导出）

    /**
     * 后台列表的订单号分页：状态 / 异常口径 / 关键词三个条件都收敛在这条 JPQL 里，
     * C25 看板、G21 筛选项与 C21 导出都调本方法，保证三处看到的是同一批订单。
     */
    @Transactional(readOnly = true)
    public List<UUID> searchOrderIds(OrderStatus status, String anomaly, String keyword,
                                     Windows windows, LocalDateTime now, int offset, int size) {
        Clause clause = clauseOf(status, anomaly, keyword, windows, now);
        Query query = entityManager.createQuery("SELECT o.id FROM Order o" + clause.where() + clause.orderBy());
        clause.params().forEach(query::setParameter);
        query.setMaxResults(size);
        if (offset > 0) {
            query.setFirstResult(offset);
        }
        List<?> rows = query.getResultList();
        List<UUID> ids = new ArrayList<>(rows.size());
        for (Object row : rows) {
            ids.add(asUuid(row));
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public long countOrders(OrderStatus status, String anomaly, String keyword,
                            Windows windows, LocalDateTime now) {
        Clause clause = clauseOf(status, anomaly, keyword, windows, now);
        Query query = entityManager.createQuery("SELECT COUNT(o.id) FROM Order o" + clause.where());
        clause.params().forEach(query::setParameter);
        Object total = query.getSingleResult();
        return total instanceof Number n ? n.longValue() : 0L;
    }

    /** 每种异常口径的判定说明：看板的提示文案与 SQL 同源，改判定不会漏改文案 */
    public List<Map<String, Object>> anomalyRules(Windows windows) {
        return List.of(
                Map.of("code", "ship_overdue", "label", "超时未发货",
                        "rule", "已付款/处理中，付款满 " + windows.shipGraceHours() + " 小时且已到预计送达时点"),
                Map.of("code", "pay_pending", "label", "长时间未付款",
                        "rule", "待付款且已超过自动关单时限 " + windows.payTimeoutMinutes() + " 分钟"),
                Map.of("code", "refunding", "label", "退款跟进中",
                        "rule", "有待处理的退款申请，或近 " + windows.refundFollowDays()
                                + " 天已退款需确认原路与到账"));
    }

    /** 一次取回页内订单的明细与商品：列表要显示商品名与产地，逐单懒加载会打出 N+1 条 SQL */
    @Transactional(readOnly = true)
    public List<Order> loadOrders(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery("""
                        SELECT DISTINCT o FROM Order o
                        LEFT JOIN FETCH o.items i LEFT JOIN FETCH i.product LEFT JOIN FETCH o.user
                        WHERE o.id IN :ids""", Order.class)
                .setParameter("ids", ids)
                .getResultList();
    }

    /** 批量操作的逐单判定材料：只取标量列，条件 UPDATE 之后不再碰懒加载关联 */
    @Transactional(readOnly = true)
    public List<Object[]> orderSnapshots(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return entityManager.createQuery(SNAPSHOT_SELECT + " WHERE o.id IN :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public Object[] orderSnapshot(UUID orderId) {
        return entityManager.createQuery(SNAPSHOT_SELECT + " WHERE o.id = :id", Object[].class)
                .setParameter("id", orderId)
                .getResultStream().findFirst().orElse(null);
    }

    /** 一键定位（G20）：订单号完全一致的那条排在最前，其余按下单时间倒序 */
    @Transactional(readOnly = true)
    public List<Object[]> locate(String keyword, int limit) {
        String like = likeOf(keyword);
        return entityManager.createQuery("""
                        SELECT o.id, o.orderNo, o.status, o.receiverName, o.expressNo, o.createdAt
                        FROM Order o
                        WHERE o.orderNo LIKE :like OR o.receiverName LIKE :like OR o.receiverPhone LIKE :like
                           OR COALESCE(o.expressNo, '') LIKE :like
                           OR EXISTS (SELECT 1 FROM User u WHERE u.id = o.user.id
                                        AND (u.username LIKE :like OR u.fullName LIKE :like))
                        ORDER BY CASE WHEN o.orderNo = :exact THEN 0 ELSE 1 END, o.createdAt DESC""", Object[].class)
                .setParameter("like", like)
                .setParameter("exact", keyword == null ? "" : keyword.trim())
                .setMaxResults(Math.min(Math.max(limit, 1), 20))
                .getResultList();
    }

    /** 产地名：导出要把商品上的 origin_id 翻成中文产地名 */
    @Transactional(readOnly = true)
    public Map<UUID, String> originNames(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new LinkedHashMap<>();
        entityManager.createQuery("SELECT f.id, f.name FROM FlowerOrigin f WHERE f.id IN :ids", Object[].class)
                .setParameter("ids", ids)
                .getResultList()
                .forEach(row -> out.put(asUuid(row[0]), row[1] == null ? null : row[1].toString()));
        return out;
    }

    // ------------------------------------------------------------------ 轨迹节点（C13）

    /** 轨迹节点带 id：详情页要按节点删除，而共享的 TraceView 里没有 id，只能从本查询取 */
    @Transactional(readOnly = true)
    public List<Object[]> traceNodes(UUID orderId) {
        return entityManager.createQuery("""
                        SELECT t.id, t.code, t.title, t.description, t.operator, t.createdAt
                        FROM OrderTrace t WHERE t.order.id = :orderId ORDER BY t.createdAt ASC, t.id ASC""",
                        Object[].class)
                .setParameter("orderId", orderId)
                .getResultList();
    }

    /** 节点原文：删除前先快照一份，删完库里就查不到了 */
    @Transactional(readOnly = true)
    public Object[] traceNode(UUID traceId) {
        return entityManager.createQuery("""
                        SELECT t.id, t.order.id, t.code, t.title, t.description, t.operator, t.createdAt
                        FROM OrderTrace t WHERE t.id = :id""", Object[].class)
                .setParameter("id", traceId)
                .getResultStream().findFirst().orElse(null);
    }

    /** 条件删除：同时限定所属订单，避免拿别人的 traceId 来删 */
    public int deleteTraceNode(UUID traceId, UUID orderId) {
        Query query = entityManager.createQuery("""
                DELETE FROM OrderTrace t WHERE t.id = :id AND t.order.id = :orderId""");
        query.setParameter("id", traceId);
        query.setParameter("orderId", orderId);
        int updated = query.executeUpdate();
        // 与 @Modifying(clearAutomatically = true) 同义：清空一级缓存，后续读必须重新取库
        entityManager.clear();
        return updated;
    }

    public void insertTrace(UUID orderId, String code, String title, String description, String operator) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("orderId", orderId);
        params.put("code", code);
        params.put("title", title);
        params.put("description", description);
        params.put("operator", operator);
        nativeUpdate("""
                INSERT INTO order_trace (id, order_id, code, title, description, operator, created_at)
                VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), :code, :title, :description, :operator, now())
                """, params);
    }

    // ------------------------------------------------------------------ 订单写（条件 UPDATE）

    /** 发货：状态与物流字段同一条 CAS 写入，只有从「期望状态」出发的那一次能成功 */
    public int markShipped(UUID orderId, OrderStatus expected, OrderStatus target,
                           String company, String expressNo, LocalDateTime shipTime) {
        Query query = entityManager.createQuery("""
                UPDATE Order o SET o.expressCompany = :company, o.expressNo = :no, o.shipTime = :shipTime,
                       o.status = :target, o.updatedAt = CURRENT_TIMESTAMP
                WHERE o.id = :id AND o.status = :expected""");
        query.setParameter("company", company);
        query.setParameter("no", expressNo);
        query.setParameter("shipTime", shipTime);
        query.setParameter("target", target);
        query.setParameter("id", orderId);
        query.setParameter("expected", expected);
        int updated = query.executeUpdate();
        entityManager.clear();
        return updated;
    }

    /** 纯状态跃迁（转处理中 / 确认签收 / 完成）：没有库存与券副作用，后台直接 CAS + 补轨迹 */
    public int transitStatus(UUID orderId, OrderStatus expected, OrderStatus target) {
        Query query = entityManager.createQuery("""
                UPDATE Order o SET o.status = :target, o.updatedAt = CURRENT_TIMESTAMP
                WHERE o.id = :id AND o.status = :expected""");
        query.setParameter("target", target);
        query.setParameter("id", orderId);
        query.setParameter("expected", expected);
        int updated = query.executeUpdate();
        entityManager.clear();
        return updated;
    }

    /**
     * 运费微调（C24）：CAS 同时卡住 freight 与 pay_amount 的旧值——
     * 两个人同时改同一单时后提交那次必须失败，否则会各自基于旧值算出不同的实付。
     * 历史订单的 freight 可能为 NULL，用 COALESCE 兜成 0 参与比较。
     */
    public int updateFreight(UUID orderId, List<OrderStatus> allowed, BigDecimal expectedFreight,
                             BigDecimal expectedPay, BigDecimal nextFreight, BigDecimal nextPay) {
        Query query = entityManager.createQuery("""
                UPDATE Order o SET o.freight = :nextFreight, o.payAmount = :nextPay,
                       o.updatedAt = CURRENT_TIMESTAMP
                WHERE o.id = :id AND o.status IN :allowed
                  AND COALESCE(o.freight, 0) = :expectedFreight AND o.payAmount = :expectedPay""");
        query.setParameter("nextFreight", nextFreight);
        query.setParameter("nextPay", nextPay);
        query.setParameter("id", orderId);
        query.setParameter("allowed", allowed);
        query.setParameter("expectedFreight", expectedFreight);
        query.setParameter("expectedPay", expectedPay);
        int updated = query.executeUpdate();
        entityManager.clear();
        return updated;
    }

    /**
     * 备注（C23）：旧值已进 order_remark_edit，订单上只留最新值。
     * 必须卡住读到的旧值，否则两个人同时改同一单时，后提交的那条会把别人的改动盖掉，
     * 而留痕里记的「改前」还是一个根本没生效过的内容。
     */
    public int updateRemark(UUID orderId, String remark, String expectedRemark) {
        Query query = entityManager.createQuery("""
                UPDATE Order o SET o.remark = :remark, o.updatedAt = CURRENT_TIMESTAMP
                WHERE o.id = :id AND COALESCE(o.remark, '') = :expectedRemark""");
        query.setParameter("remark", remark);
        query.setParameter("id", orderId);
        query.setParameter("expectedRemark", expectedRemark);
        int updated = query.executeUpdate();
        entityManager.clear();
        return updated;
    }

    // ------------------------------------------------------------------ 留痕表

    public void insertTraceAudit(UUID orderId, UUID traceId, String code, String title, String description,
                                 String nodeOperator, LocalDateTime nodeCreatedAt, String reason,
                                 String operator, UUID operatorId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("orderId", orderId);
        params.put("traceId", traceId);
        params.put("code", code);
        params.put("title", title);
        params.put("description", description);
        params.put("nodeOperator", nodeOperator);
        params.put("nodeCreatedAt", nodeCreatedAt);
        params.put("reason", reason);
        params.put("operator", operator);
        params.put("operatorId", operatorId);
        nativeUpdate("""
                INSERT INTO order_trace_audit (id, order_id, trace_id, code, title, description, node_operator,
                                               node_created_at, reason, deleted_by, deleted_by_id, deleted_at)
                VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), CAST(:traceId AS uuid), :code, :title,
                        :description, :nodeOperator, :nodeCreatedAt, :reason, :operator,
                        CAST(:operatorId AS uuid), now())
                """, params);
    }

    public void insertRemarkEdit(UUID orderId, String before, String after, String reason,
                                 String operator, UUID operatorId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("orderId", orderId);
        params.put("before", before);
        params.put("after", after);
        params.put("reason", reason);
        params.put("operator", operator);
        params.put("operatorId", operatorId);
        nativeUpdate("""
                INSERT INTO order_remark_edit (id, order_id, remark_before, remark_after, reason,
                                               operator_name, operator_id, created_at)
                VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), :before, :after, :reason,
                        :operator, CAST(:operatorId AS uuid), now())
                """, params);
    }

    public void insertFreightAdjust(UUID orderId, BigDecimal freightBefore, BigDecimal freightAfter,
                                    BigDecimal payBefore, BigDecimal payAfter, String reason,
                                    String operator, UUID operatorId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", UUID.randomUUID());
        params.put("orderId", orderId);
        params.put("freightBefore", freightBefore);
        params.put("freightAfter", freightAfter);
        params.put("payBefore", payBefore);
        params.put("payAfter", payAfter);
        params.put("reason", reason);
        params.put("operator", operator);
        params.put("operatorId", operatorId);
        nativeUpdate("""
                INSERT INTO order_freight_adjust (id, order_id, freight_before, freight_after, pay_before, pay_after,
                                                  reason, operator_name, operator_id, created_at)
                VALUES (CAST(:id AS uuid), CAST(:orderId AS uuid), :freightBefore, :freightAfter, :payBefore,
                        :payAfter, :reason, :operator, CAST(:operatorId AS uuid), now())
                """, params);
    }

    /** 详情页「后台改过什么」：三张留痕表按订单各自取回 */
    @Transactional(readOnly = true)
    public List<Object[]> remarkEdits(UUID orderId) {
        return nativeRows("""
                SELECT created_at, operator_name, remark_before, remark_after, reason
                FROM order_remark_edit WHERE order_id = CAST(:oid AS uuid) ORDER BY created_at DESC""", orderId);
    }

    @Transactional(readOnly = true)
    public List<Object[]> freightAdjustments(UUID orderId) {
        return nativeRows("""
                SELECT created_at, operator_name, freight_before, freight_after, pay_before, pay_after, reason
                FROM order_freight_adjust WHERE order_id = CAST(:oid AS uuid) ORDER BY created_at DESC""", orderId);
    }

    @Transactional(readOnly = true)
    public List<Object[]> traceDeletions(UUID orderId) {
        return nativeRows("""
                SELECT deleted_at, deleted_by, code, title, description, node_operator, reason
                FROM order_trace_audit WHERE order_id = CAST(:oid AS uuid) ORDER BY deleted_at DESC""", orderId);
    }

    // ------------------------------------------------------------------ 条件拼装

    private record Clause(String where, Map<String, Object> params, String orderBy) {
    }

    /**
     * 异常判定只有这一处（C25 看板、G21 筛选项、C21 导出共用）：
     * ship_overdue 已付款/处理中、超过发货宽限期且送达时点已到（预约未到期的单不算迟到）；
     * pay_pending 待付款且已过自动关单时限，与定时任务同口径，捞的是漏网单；
     * refunding 有待处理的退款申请，或近 N 天已退款——退款在途时订单本身还是「已付款/处理中」，
     * 只看 order.status 会把最需要人工跟进的这批整个漏掉。
     *
     * <p>多个口径之间必须是 OR：一条订单不可能同时是「超时未发货」和「长时间未付款」，
     * 按 AND 拼出来的「全部异常」永远查不出东西。
     */
    private Clause clauseOf(OrderStatus status, String anomaly, String keyword,
                            Windows windows, LocalDateTime now) {
        List<String> conditions = new ArrayList<>();
        List<String> anomalyConditions = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        if (status != null) {
            conditions.add("o.status = :status");
            params.put("status", status);
        }
        String normalized = normalizeAnomaly(anomaly);
        if (normalized != null) {
            if (containsAny(normalized, "ship_overdue")) {
                anomalyConditions.add("(o.status IN :shipOverdueStatuses"
                        + " AND COALESCE(o.payTime, o.createdAt) <= :shipDeadline"
                        + " AND COALESCE(o.expectedArriveAt, o.createdAt) <= :now)");
                params.put("shipOverdueStatuses", Windows.SHIP_OVERDUE_STATUSES);
                params.put("shipDeadline", windows.shipDeadline(now));
                params.put("now", now);
            }
            if (containsAny(normalized, "pay_pending")) {
                anomalyConditions.add("(o.status = :pendingStatus AND o.createdAt <= :payDeadline)");
                params.put("pendingStatus", OrderStatus.PENDING);
                params.put("payDeadline", windows.payDeadline(now));
            }
            if (containsAny(normalized, "refunding")) {
                anomalyConditions.add("(o.refundStatus IN :openRefundStatuses"
                        + " OR (o.status = :refundedStatus AND o.updatedAt >= :refundSince))");
                params.put("openRefundStatuses", RefundStatus.OPEN_LIST);
                params.put("refundedStatus", OrderStatus.REFUNDED);
                params.put("refundSince", windows.refundSince(now));
            }
            conditions.add(anomalyConditions.size() == 1
                    ? anomalyConditions.get(0)
                    : "(" + String.join(" OR ", anomalyConditions) + ")");
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("""
                    (o.orderNo LIKE :like OR o.receiverName LIKE :like OR o.receiverPhone LIKE :like
                     OR COALESCE(o.expressNo, '') LIKE :like
                     OR EXISTS (SELECT 1 FROM User u WHERE u.id = o.user.id
                                AND (u.username LIKE :like OR u.fullName LIKE :like)))""");
            params.put("like", likeOf(keyword));
        }
        String orderBy = "ship_overdue".equals(normalized) || "any".equals(normalized)
                ? ORDER_BY_DUE : ORDER_BY_CREATED;
        return new Clause(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params, orderBy);
    }

    /** 未识别的口径按「不过滤」处理：页面拼错参数时退回全量，而不是抛 500 */
    private static String normalizeAnomaly(String anomaly) {
        if (anomaly == null || anomaly.isBlank() || "none".equals(anomaly)) {
            return null;
        }
        return switch (anomaly) {
            case "ship_overdue", "pay_pending", "refunding", "any" -> anomaly;
            default -> null;
        };
    }

    private static boolean containsAny(String normalized, String key) {
        return "any".equals(normalized) || normalized.contains(key);
    }

    /** 与 OrderRepository.search 一致：空串代表不过滤，null 会让 PG 无法推断 LIKE 参数类型 */
    private static String likeOf(String keyword) {
        return "%" + (keyword == null ? "" : keyword.trim()) + "%";
    }

    private static final String SNAPSHOT_SELECT = """
            SELECT o.id, o.orderNo, o.status, o.expressCompany, o.expressNo, o.shipTime,
                   o.totalAmount, o.discountAmount, o.freight, o.payAmount, o.remark,
                   o.receiverName, o.receiverPhone, o.deliveryMethod, o.expectedArriveAt, o.payTime, o.createdAt
            FROM Order o""";

    private List<Object[]> nativeRows(String sql, UUID orderId) {
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("oid", orderId);
        return castRows(query.getResultList());
    }

    private int nativeUpdate(String sql, Map<String, Object> params) {
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return query.executeUpdate();
    }

    private static List<Object[]> castRows(List<?> rows) {
        List<Object[]> out = new ArrayList<>(rows.size());
        for (Object row : rows) {
            out.add((Object[]) row);
        }
        return out;
    }

    static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }

    /**
     * 异常判定的时间窗：阈值只在服务层算成具体时间点，SQL 里不做时间加减，
     * 免得 interval 写法在方言上翻车
     */
    public record Windows(int shipGraceHours, int payTimeoutMinutes, int refundFollowDays) {

        static final List<OrderStatus> SHIP_OVERDUE_STATUSES = List.of(OrderStatus.PAID, OrderStatus.PROCESSING);

        public LocalDateTime shipDeadline(LocalDateTime now) {
            return now.minusHours(Math.max(shipGraceHours, 1));
        }

        public LocalDateTime payDeadline(LocalDateTime now) {
            return now.minusMinutes(Math.max(payTimeoutMinutes, 1));
        }

        public LocalDateTime refundSince(LocalDateTime now) {
            return now.minusDays(Math.max(refundFollowDays, 1));
        }
    }
}
