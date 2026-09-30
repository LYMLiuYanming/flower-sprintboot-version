package org.liuym.flowerv1springboot.service.impl;

import jakarta.servlet.http.HttpServletRequest;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.AdminOrderQueryRepository;
import org.liuym.flowerv1springboot.service.AdminAuditService;
import org.liuym.flowerv1springboot.service.AdminOrderOpsService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 后台订单操作：所有写侧都是「先读快照 → 条件 UPDATE → 判影响行数 → 补轨迹/留痕」，
 * 批量再给逐条成败明细。取消、退款、确认收款需要回补库存与券，仍留在 OrderService 逐单处理。
 */
@Service
public class AdminOrderOpsServiceImpl implements AdminOrderOpsService {

    private static final Logger log = LoggerFactory.getLogger(AdminOrderOpsServiceImpl.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** C12：查询链接的域名白名单。字典表里配了别的域名的行一律不发链接，只发复制 */
    private static final Set<String> ALLOWED_TRACK_HOSTS =
            Set.of("www.kuaidi100.com", "m.kuaidi100.com", "www.17track.net");
    private static final String NO_PLACEHOLDER = "__NO__";

    private static final int MAX_BATCH = 100;
    /** 上限与原因长度改用接口常量，页面下发的是同两个值 */

    /** 批量流转只放行无库存副作用的目标；取消/退款/确认收款要回补库存与券，必须逐单走 OrderService */
    private static final List<OrderStatus> BATCH_TARGETS =
            List.of(OrderStatus.PROCESSING, OrderStatus.DELIVERED, OrderStatus.COMPLETED);
    /** 运费只能在这些状态下改：已发货或已关单再改金额，会与支付流水对不上 */
    private static final List<OrderStatus> FREIGHT_EDITABLE =
            List.of(OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.PROCESSING);

    /** 只有后台补记的节点可以删；系统流转节点删掉等于篡改履约时间轴，只能用「补记」更正 */
    private static final String DELETABLE_TRACE_CODE = "custom";
    private static final String SYSTEM_OPERATOR = "系统";

    /** 与 OrderServiceImpl 的自动节点保持同一套 code/标题，轨迹时间轴才不会两种写法混排 */
    private static final Map<OrderStatus, String> TRANSIT_TRACE_CODE = Map.of(
            OrderStatus.PROCESSING, "processing",
            OrderStatus.DELIVERED, "delivered",
            OrderStatus.COMPLETED, "completed");
    private static final Map<OrderStatus, String> TRANSIT_TRACE_TITLE = Map.of(
            OrderStatus.PROCESSING, "花艺师备花中",
            OrderStatus.DELIVERED, "花礼已送达",
            OrderStatus.COMPLETED, "交易完成");

    /** orderSnapshots / orderSnapshot 的列序，集中一处好核对 */
    private static final int SNAP_ID = 0, SNAP_ORDER_NO = 1, SNAP_STATUS = 2, SNAP_COMPANY = 3, SNAP_EXPRESS_NO = 4,
            SNAP_TOTAL = 6, SNAP_DISCOUNT = 7, SNAP_FREIGHT = 8, SNAP_PAY = 9, SNAP_REMARK = 10,
            SNAP_RECEIVER = 11, SNAP_PHONE = 12, SNAP_DELIVERY_METHOD = 13;

    private static final int NODE_ID = 0, NODE_ORDER_ID = 1, NODE_CODE = 2, NODE_TITLE = 3,
            NODE_DESC = 4, NODE_OPERATOR = 5, NODE_CREATED = 6;

    private final AdminOrderQueryRepository repository;
    private final AdminAuditService adminAuditService;
    private final TransactionTemplate transactionTemplate;
    private final AdminOrderQueryRepository.Windows windows;
    private final CompanyCache companyCache;
    /** 可调整运费的后台账号名单，逗号分隔；留空表示所有管理员可用 */
    private final Set<String> freightOperators;

    public AdminOrderOpsServiceImpl(AdminOrderQueryRepository repository,
                                    AdminAuditService adminAuditService,
                                    PlatformTransactionManager transactionManager,
                                    @Value("${admin.order.ship-overdue-hours:24}") int shipOverdueHours,
                                    @Value("${order.pay-timeout-minutes:30}") int payTimeoutMinutes,
                                    @Value("${admin.order.refund-follow-days:7}") int refundFollowDays,
                                    @Value("${admin.order.freight-operators:}") String freightOperators) {
        this.repository = repository;
        this.adminAuditService = adminAuditService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.windows = new AdminOrderQueryRepository.Windows(
                shipOverdueHours <= 0 ? 24 : shipOverdueHours,
                payTimeoutMinutes <= 0 ? 30 : payTimeoutMinutes,
                refundFollowDays <= 0 ? 7 : refundFollowDays);
        this.companyCache = new CompanyCache(repository);
        this.freightOperators = splitToSet(freightOperators);
    }

    // ------------------------------------------------------------------ 字典与读侧

    @Override
    public List<ExpressCompany> companies() {
        return companyCache.all().stream().map(Rule::view).toList();
    }

    /**
     * 列表与导出（C25/G21/C21）：先按条件分页取订单号，再一次性把这页捞全明细，
     * 顺序由第一条查询决定——异常单要按「最早该发的排最前」，这个顺序不能让 IN 查询打乱。
     */
    @Override
    @Transactional(readOnly = true)
    public Page<OrderView> searchForAdmin(OrderStatus status, String anomaly, String keyword, Pageable pageable) {
        LocalDateTime now = LocalDateTime.now();
        long total = repository.countOrders(status, anomaly, keyword, windows, now);
        if (total == 0) {
            return Page.empty(pageable);
        }
        int size = Math.max(pageable.getPageSize(), 1);
        List<UUID> ids = repository.searchOrderIds(status, anomaly, keyword, windows, now,
                (int) pageable.getOffset(), size);
        Map<UUID, Order> loaded = new LinkedHashMap<>();
        for (Order order : repository.loadOrders(ids)) {
            loaded.put(order.getId(), order);
        }
        List<OrderView> views = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Order order = loaded.get(id);
            if (order != null) {
                // canReview 不参与后台列表，省掉一次 review 表查询；轨迹只在详情页取
                views.add(OrderView.of(order, Set.of(), true));
            }
        }
        return new PageImpl<>(views, pageable, total);
    }

    @Override
    public Map<UUID, String> originNames(Collection<UUID> originIds) {
        return repository.originNames(originIds);
    }

    @Override
    public OpsDetail opsDetail(UUID orderId, User operator) {
        ShippingInfo shipping = buildShippingInfo(orderId);
        List<RemarkEdit> remarks = new ArrayList<>();
        for (Object[] row : repository.remarkEdits(orderId)) {
            remarks.add(new RemarkEdit(time(row[0]), str(row[1]), str(row[2]), str(row[3]), str(row[4])));
        }
        List<FreightAdjust> adjusts = new ArrayList<>();
        for (Object[] row : repository.freightAdjustments(orderId)) {
            adjusts.add(new FreightAdjust(time(row[0]), str(row[1]), money(row[2]), money(row[3]),
                    money(row[4]), money(row[5]), str(row[6])));
        }
        List<TraceDeletion> deletions = new ArrayList<>();
        for (Object[] row : repository.traceDeletions(orderId)) {
            deletions.add(new TraceDeletion(time(row[0]), str(row[1]), str(row[2]), str(row[3]),
                    str(row[4]), str(row[5]), str(row[6])));
        }
        List<TraceNode> nodes = new ArrayList<>();
        for (Object[] row : repository.traceNodes(orderId)) {
            String code = str(row[1]);
            String nodeOperator = str(row[4]);
            boolean deletable = DELETABLE_TRACE_CODE.equals(code)
                    && !blank(nodeOperator) && !SYSTEM_OPERATOR.equals(nodeOperator);
            nodes.add(new TraceNode(String.valueOf(row[0]), code, str(row[2]), str(row[3]),
                    nodeOperator, time(row[5]), deletable));
        }
        return new OpsDetail(shipping, remarks, adjusts, deletions, nodes, canAdjustFreight(operator));
    }

    @Override
    public ShippingInfo shippingInfo(UUID orderId) {
        return buildShippingInfo(orderId);
    }

    private ShippingInfo buildShippingInfo(UUID orderId) {
        Object[] snap = requireSnapshot(orderId);
        Rule rule = companyCache.byName().get(str(snap[SNAP_COMPANY]));
        String no = str(snap[SNAP_EXPRESS_NO]);
        OrderStatus status = statusOf(snap[SNAP_STATUS]);
        return new ShippingInfo(orderId, str(snap[SNAP_ORDER_NO]),
                rule == null ? null : rule.code(), str(snap[SNAP_COMPANY]), no,
                rule == null ? null : rule.trackUrl(no),
                time(snap[5]), status == null ? null : status.getCode(),
                status == null ? "未知" : status.getLabel());
    }

    @Override
    public List<AnomalyCard> anomalySummary() {
        LocalDateTime now = LocalDateTime.now();
        List<AnomalyCard> cards = new ArrayList<>();
        cards.add(new AnomalyCard("any", "全部异常", "下面三类之一，点卡片即为已筛选好的列表",
                repository.countOrders(null, "any", null, windows, now)));
        for (Map<String, Object> rule : repository.anomalyRules(windows)) {
            String code = String.valueOf(rule.get("code"));
            cards.add(new AnomalyCard(code, String.valueOf(rule.get("label")), String.valueOf(rule.get("rule")),
                    repository.countOrders(null, code, null, windows, now)));
        }
        return cards;
    }

    @Override
    public List<Map<String, Object>> locate(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String trimmed = keyword.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : repository.locate(trimmed, 10)) {
            OrderStatus status = statusOf(row[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(row[0]));
            item.put("orderNo", str(row[1]));
            item.put("status", status == null ? null : status.getCode());
            item.put("statusLabel", status == null ? "未知" : status.getLabel());
            item.put("receiverName", str(row[3]));
            item.put("expressNo", str(row[4]));
            item.put("createdAt", text(time(row[5])));
            // 命中方式回给页面：订单号完全一致时前端直接深链到详情，不必再让运营点一次
            item.put("exact", trimmed.equalsIgnoreCase(str(row[1])));
            out.add(item);
        }
        return out;
    }

    @Override
    public boolean canAdjustFreight(User operator) {
        if (operator == null || !User.TYPE_ADMIN.equals(operator.getUserType())) {
            return false;
        }
        return freightOperators.isEmpty() || freightOperators.contains(operator.getUsername());
    }

    // ------------------------------------------------------------------ C11 / C22 发货

    @Override
    public void ship(UUID orderId, String companyCode, String expressNo, User operator) {
        Rule company = requireCompany(companyCode);
        transactionTemplate.execute(status -> {
            doShip(orderId, company, expressNo, operator, "发货");
            return Boolean.TRUE;
        });
    }

    @Override
    public BatchResult batchShip(String companyCode, List<ShipLine> lines, User operator) {
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("请先勾选要发货的订单");
        }
        if (lines.size() > MAX_BATCH) {
            throw new BusinessException("单次批量发货最多 " + MAX_BATCH + " 笔，请分批操作");
        }
        // 公司整批共用：字典里没有这家公司时不必给每条都回同一个错
        Rule company = requireCompany(companyCode);
        Map<UUID, String> names = namesOf(lines.stream().map(ShipLine::orderId).toList());
        List<BatchItem> items = new ArrayList<>(lines.size());
        int succeeded = 0;
        for (ShipLine line : lines) {
            String name = names.getOrDefault(line.orderId(), String.valueOf(line.orderId()));
            try {
                transactionTemplate.execute(status -> {
                    doShip(line.orderId(), company, line.expressNo(), operator, "批量发货");
                    return Boolean.TRUE;
                });
                items.add(new BatchItem(line.orderId(), name, true, "已发货"));
                succeeded++;
            } catch (BusinessException e) {
                items.add(new BatchItem(line.orderId(), name, false, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("批量发货失败 orderNo={}: {}", name, e.getMessage());
                items.add(new BatchItem(line.orderId(), name, false, "系统处理失败，这条没有写入"));
            }
        }
        return new BatchResult(items, succeeded, lines.size() - succeeded);
    }

    /** 单笔发货的全部动作：必须在调用方的事务里执行，批量时每条各自一个事务 */
    private void doShip(UUID orderId, Rule company, String rawNo, User operator, String action) {
        Object[] snap = requireSnapshot(orderId);
        OrderStatus current = requireStatus(snap);
        if (!current.canTransitTo(OrderStatus.SHIPPED)) {
            throw new BusinessException("「" + current.getLabel() + "」状态的订单不能发货，请先确认收款");
        }
        String expressNo = company.requireNo(rawNo);
        if (repository.markShipped(orderId, current, OrderStatus.SHIPPED,
                company.name(), expressNo, LocalDateTime.now()) == 0) {
            throw new BusinessException(409, "这条订单的状态刚被别人改过，本次发货没有写入，请刷新后重试");
        }
        repository.insertTrace(orderId, "shipped", "已出库发货",
                company.name() + " " + expressNo + " · " + deliveryMethodText(snap[SNAP_DELIVERY_METHOD]),
                operatorName(operator));
        audit(operator, action, "订单 " + snap[SNAP_ORDER_NO],
                "订单号：" + snap[SNAP_ORDER_NO]
                        + "｜收货人：" + text(snap[SNAP_RECEIVER])
                        + "｜手机：" + maskPhone(snap[SNAP_PHONE])
                        + "｜物流：" + company.name() + " " + expressNo
                        + "｜状态：" + current.getLabel() + " → 已发货");
    }

    // ------------------------------------------------------------------ G19 批量流转

    @Override
    public BatchResult batchTransit(List<UUID> orderIds, OrderStatus target, String reason, User operator) {
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BusinessException("请先勾选要处理的订单");
        }
        if (orderIds.size() > MAX_BATCH) {
            throw new BusinessException("单次批量最多处理 " + MAX_BATCH + " 笔，请分批操作");
        }
        if (target == null || !BATCH_TARGETS.contains(target)) {
            throw new BusinessException("批量只支持「转处理中 / 确认签收 / 完成」：发货请用「批量发货」，"
                    + "取消、退款与确认收款要逐单处理（要回补库存和优惠券）");
        }
        Map<UUID, String> names = namesOf(orderIds);
        List<BatchItem> items = new ArrayList<>(orderIds.size());
        int succeeded = 0;
        for (UUID id : orderIds) {
            String name = names.getOrDefault(id, String.valueOf(id));
            try {
                transactionTemplate.execute(status -> {
                    doTransit(id, target, reason, operator);
                    return Boolean.TRUE;
                });
                items.add(new BatchItem(id, name, true, "已变更为「" + target.getLabel() + "」"));
                succeeded++;
            } catch (BusinessException e) {
                items.add(new BatchItem(id, name, false, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("批量流转失败 orderNo={} target={}: {}", name, target, e.getMessage());
                items.add(new BatchItem(id, name, false, "系统处理失败，这条没有写入"));
            }
        }
        return new BatchResult(items, succeeded, orderIds.size() - succeeded);
    }

    /** 单条状态跃迁：同样必须在调用方事务里执行 */
    private void doTransit(UUID orderId, OrderStatus target, String reason, User operator) {
        Object[] snap = requireSnapshot(orderId);
        OrderStatus current = requireStatus(snap);
        if (current == target) {
            throw new BusinessException("已经是「" + target.getLabel() + "」，无需重复处理");
        }
        if (!current.canTransitTo(target)) {
            throw new BusinessException("「" + current.getLabel() + "」不能直接变更为「" + target.getLabel() + "」");
        }
        if (repository.transitStatus(orderId, current, target) == 0) {
            throw new BusinessException(409, "这条订单的状态刚被别人改过，本次没有写入，请刷新后重试");
        }
        String detail = "门店批量把订单状态更新为「" + target.getLabel() + "」"
                + (blank(reason) ? "" : "；备注：" + cut(reason.trim(), 120));
        repository.insertTrace(orderId, TRANSIT_TRACE_CODE.getOrDefault(target, "status"),
                TRANSIT_TRACE_TITLE.getOrDefault(target, "状态更新"), detail, operatorName(operator));
        audit(operator, "批量流转", "订单 " + snap[SNAP_ORDER_NO],
                "订单号：" + snap[SNAP_ORDER_NO] + "｜状态：" + current.getLabel() + " → " + target.getLabel()
                        + "｜手机：" + maskPhone(snap[SNAP_PHONE]));
    }

    // ------------------------------------------------------------------ C13 删除误记节点

    @Override
    @Transactional
    public void deleteTraceNode(UUID orderId, UUID traceId, String reason, User operator) {
        Object[] snap = requireSnapshot(orderId);
        Object[] node = repository.traceNode(traceId);
        if (node == null || !orderId.equals(asUuid(node[NODE_ORDER_ID]))) {
            throw BusinessException.notFound("轨迹节点不存在，或它不属于这条订单");
        }
        String code = str(node[NODE_CODE]);
        String title = str(node[NODE_TITLE]);
        if (!DELETABLE_TRACE_CODE.equals(code)) {
            throw new BusinessException("「" + title + "」是系统流转节点，删掉等于篡改履约时间轴；"
                    + "记错了请用「补记节点」写一条更正说明");
        }
        if (SYSTEM_OPERATOR.equals(str(node[NODE_OPERATOR]))) {
            throw new BusinessException("「" + title + "」由系统写入，不允许删除");
        }
        requireReason(reason, "删除原因");
        // 先写快照再删：万一删除条件没命中，整个事务回滚，不会留下一条没发生的删除记录
        repository.insertTraceAudit(orderId, traceId, code, title, str(node[NODE_DESC]),
                str(node[NODE_OPERATOR]), time(node[NODE_CREATED]), cut(reason.trim(), 200),
                operatorName(operator), operator == null ? null : operator.getId());
        if (repository.deleteTraceNode(traceId, orderId) == 0) {
            throw new BusinessException(409, "该节点刚被别人删掉了，本次没有重复删除");
        }
        audit(operator, "删除轨迹节点", "订单 " + snap[SNAP_ORDER_NO],
                "订单号：" + snap[SNAP_ORDER_NO] + "｜删除节点：" + title + "（" + nullToEmpty(str(node[NODE_DESC])) + "）"
                        + "｜原操作人：" + text(node[NODE_OPERATOR])
                        + "｜原时间：" + text(time(node[NODE_CREATED]))
                        + "｜原因：" + cut(reason.trim(), 120));
    }

    // ------------------------------------------------------------------ C23 备注

    @Override
    @Transactional
    public void updateRemark(UUID orderId, String remark, String reason, User operator) {
        Object[] snap = requireSnapshot(orderId);
        String before = str(snap[SNAP_REMARK]);
        String next = cut(trimToNull(remark), 500);
        if (Objects.equals(before, next)) {
            throw new BusinessException("备注内容没有变化，不必留一条空改动");
        }
        requireReason(reason, "修改原因");
        // 订单刚在本事务里读过快照，不存在「查不到」；0 行只可能是别人在这中间改过
        if (repository.updateRemark(orderId, next, nullToEmpty(before)) == 0) {
            throw new BusinessException(409, "这条订单的备注刚被别人改过，本次没有写入，请刷新后重试");
        }
        repository.insertRemarkEdit(orderId, before, next, cut(reason.trim(), 200),
                operatorName(operator), operator == null ? null : operator.getId());
        audit(operator, "改订单备注", "订单 " + snap[SNAP_ORDER_NO],
                "订单号：" + snap[SNAP_ORDER_NO]
                        + "｜改前：" + (blank(before) ? "（空）" : cut(before, 300))
                        + "｜改后：" + (blank(next) ? "（空）" : cut(next, 300))
                        + "｜原因：" + cut(reason.trim(), 120));
    }

    // ------------------------------------------------------------------ C24 运费微调

    @Override
    @Transactional
    public FreightResult adjustFreight(UUID orderId, BigDecimal nextFreight, String reason, User operator) {
        if (!canAdjustFreight(operator)) {
            throw BusinessException.forbidden("当前账号不在可调整运费的名单里（admin.order.freight-operators），"
                    + "请联系有权限的同事操作");
        }
        Object[] snap = requireSnapshot(orderId);
        OrderStatus current = requireStatus(snap);
        if (!FREIGHT_EDITABLE.contains(current)) {
            throw new BusinessException("「" + current.getLabel() + "」的订单不能再调整运费："
                    + "已发货或已关单后改金额，会和支付流水对不上");
        }
        if (nextFreight == null) {
            throw new BusinessException("请填写调整后的运费");
        }
        requireReason(reason, "调整原因");
        BigDecimal freightBefore = money(snap[SNAP_FREIGHT]);
        BigDecimal payBefore = money(snap[SNAP_PAY]);
        BigDecimal next = nextFreight.setScale(2, RoundingMode.HALF_UP);
        if (next.compareTo(BigDecimal.ZERO) < 0 || next.compareTo(FREIGHT_MAX) > 0) {
            throw new BusinessException("运费要填 ¥0.00 ~ ¥" + FREIGHT_MAX.toPlainString() + " 之间");
        }
        if (next.compareTo(freightBefore) == 0) {
            throw new BusinessException("调整后的运费与当前一致，不必提交");
        }
        // 与服务端建单同一个口径：实付 = 货款 − 优惠 + 配送费用（含礼品包装），抵平仍按 ¥0.01 收
        BigDecimal payAfter = recomputePay(money(snap[SNAP_TOTAL]), money(snap[SNAP_DISCOUNT]), next);
        if (repository.updateFreight(orderId, FREIGHT_EDITABLE, freightBefore, payBefore, next, payAfter) == 0) {
            throw new BusinessException(409, "这条订单的运费或实付刚被别人改过，本次调整没有写入，请刷新后重试");
        }
        repository.insertFreightAdjust(orderId, freightBefore, next, payBefore, payAfter,
                cut(reason.trim(), 200), operatorName(operator), operator == null ? null : operator.getId());
        audit(operator, "调整运费", "订单 " + snap[SNAP_ORDER_NO],
                "订单号：" + snap[SNAP_ORDER_NO]
                        + "｜状态：" + current.getLabel()
                        + "｜运费：¥" + freightBefore.toPlainString() + " → ¥" + next.toPlainString()
                        + "｜实付：¥" + payBefore.toPlainString() + " → ¥" + payAfter.toPlainString()
                        + "｜原因：" + cut(reason.trim(), 120)
                        + "｜操作人：" + operatorName(operator));
        return new FreightResult(freightBefore, next, payBefore, payAfter);
    }

    /** 与 CheckoutPolicy 的兜底一致：券与积分把金额抵平时仍按 ¥0.01 收 */
    private static BigDecimal recomputePay(BigDecimal total, BigDecimal discount, BigDecimal freight) {
        BigDecimal raw = total.subtract(discount).add(freight).setScale(2, RoundingMode.HALF_UP);
        return raw.compareTo(CheckoutPolicy.MIN_PAY_AMOUNT) < 0 ? CheckoutPolicy.MIN_PAY_AMOUNT : raw;
    }

    // ------------------------------------------------------------------ 内部工具

    private Rule requireCompany(String companyCode) {
        if (blank(companyCode)) {
            throw new BusinessException("请填写物流公司");
        }
        Rule rule = companyCache.byCode().get(companyCode.trim());
        if (rule == null) {
            throw new BusinessException("物流公司不在可选列表里，请重新选择");
        }
        return rule;
    }

    private Object[] requireSnapshot(UUID orderId) {
        Object[] snap = repository.orderSnapshot(orderId);
        if (snap == null) {
            throw BusinessException.notFound("订单不存在");
        }
        return snap;
    }

    private OrderStatus requireStatus(Object[] snap) {
        OrderStatus status = statusOf(snap[SNAP_STATUS]);
        if (status == null) {
            throw new BusinessException("订单状态为空，先让技术核对这条数据");
        }
        return status;
    }

    private Map<UUID, String> namesOf(Collection<UUID> ids) {
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Object[] row : repository.orderSnapshots(ids)) {
            names.put(asUuid(row[SNAP_ID]), str(row[SNAP_ORDER_NO]));
        }
        return names;
    }

    private void requireReason(String reason, String label) {
        if (blank(reason)) {
            throw new BusinessException("请填写" + label + "，说明是谁提出的、依据什么改");
        }
        if (reason.trim().length() < REASON_MIN_CHARS) {
            throw new BusinessException(label + "太短了，请写清楚（至少 " + REASON_MIN_CHARS + " 个字）");
        }
    }

    private static String deliveryMethodText(Object raw) {
        return ShippingPolicy.find(str(raw)).map(ShippingPolicy.Method::name).orElse("标准配送");
    }

    private static String operatorName(User operator) {
        return operator == null ? SYSTEM_OPERATOR : operator.getUsername();
    }

    /**
     * 业务级留痕：AdminAuditFilter 已经记了一条通用请求日志，这里补的是「改前改后」这种
     * 事后从请求体里复原不出来的内容。手机号一律打码，任何情况下不写口令类字段。
     */
    private void audit(User operator, String action, String target, String detail) {
        AdminAuditLog entry = new AdminAuditLog();
        entry.setOperatorId(operator == null ? null : operator.getId());
        entry.setOperatorName(operator == null ? "未登录" : operator.getUsername());
        entry.setModule("订单");
        entry.setAction(action);
        HttpServletRequest request = currentRequest();
        entry.setMethod(request == null ? "SERVICE" : request.getMethod());
        entry.setUri(cut((request == null ? "/api/admin/orders" : request.getRequestURI())
                + (target == null ? "" : " → " + target), 300));
        entry.setDetail(cut(detail, 1900));
        entry.setResultCode(200);
        entry.setResultMsg(cut(action + "成功", 200));
        entry.setIp(cut(request == null ? null : clientIp(request), 64));
        adminAuditService.record(entry);
    }

    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet ? servlet.getRequest() : null;
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 审计摘要里的手机号只留头尾，中间四位打码 */
    private static String maskPhone(Object raw) {
        String phone = nullToEmpty(str(raw)).replaceAll("[^0-9]", "");
        if (phone.length() != 11) {
            return phone.isEmpty() ? "（无）" : phone.substring(0, Math.min(3, phone.length())) + "…";
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    private static Set<String> splitToSet(String value) {
        Set<String> out = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split("[,，]")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    private static UUID asUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return value == null ? null : UUID.fromString(value.toString());
    }

    private static OrderStatus statusOf(Object value) {
        if (value instanceof OrderStatus status) {
            return status;
        }
        if (value instanceof String code) {
            try {
                return OrderStatus.fromCode(code);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toLocalDateTime();
        }
        return null;
    }

    private static BigDecimal money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2, RoundingMode.HALF_UP);
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO.setScale(2);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.format(TIME);
        }
        return value.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        String trimmed = value == null ? null : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * 物流公司规则：单号格式与查询链接都从 shipping_company 读，
     * 但域名要过白名单，库里配了别的域名就不发链接。
     */
    private record Rule(String code, String name, Pattern pattern, String noExample, String noHint,
                        String trackUrlTemplate) {

        ExpressCompany view() {
            return new ExpressCompany(code, name, noExample, noHint, trackUrlTemplate);
        }

        /** C11：非法单号给明确文案——说清该公司应该长什么样，并把当前填的内容回显出来 */
        String requireNo(String rawNo) {
            String value = rawNo == null ? "" : rawNo.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
            if (value.isEmpty()) {
                throw new BusinessException("请填写物流单号");
            }
            if (value.length() > 60) {
                throw new BusinessException("物流单号不超过 60 个字符");
            }
            if (!pattern.matcher(value).matches()) {
                throw new BusinessException("物流单号不符合「" + name + "」的规则：" + noHint
                        + "（示例 " + noExample + "），当前填写 " + cut(value, 30));
            }
            return value;
        }

        /** C12：只有服务端拼好的链接才允许出网，运单号做 URL 编码，不接受任何来自页面的域名 */
        String trackUrl(String expressNo) {
            if (trackUrlTemplate == null || blank(expressNo)) {
                return null;
            }
            return trackUrlTemplate.replace(NO_PLACEHOLDER,
                    UriUtils.encode(expressNo, StandardCharsets.UTF_8));
        }
    }

    /**
     * 字典表很小，一次读进来放进程内缓存即可；后台改字典后要重启才生效，
     * 但物流公司本来就不是每天变的东西，不值得为它做失效逻辑。
     */
    static class CompanyCache {

        private final AdminOrderQueryRepository repository;
        private volatile Map<String, Rule> byCode = Map.of();
        private volatile Map<String, Rule> byName = Map.of();

        CompanyCache(AdminOrderQueryRepository repository) {
            this.repository = repository;
        }

        Map<String, Rule> byCode() {
            ensureLoaded();
            return byCode;
        }

        Map<String, Rule> byName() {
            ensureLoaded();
            return byName;
        }

        List<Rule> all() {
            ensureLoaded();
            return List.copyOf(byCode.values());
        }

        private void ensureLoaded() {
            if (!byCode.isEmpty()) {
                return;
            }
            synchronized (this) {
                if (!byCode.isEmpty()) {
                    return;
                }
                Map<String, Rule> codes = new LinkedHashMap<>();
                Map<String, Rule> names = new LinkedHashMap<>();
                for (Object[] row : repository.activeCompanies()) {
                    Rule rule = toRule(row);
                    if (rule == null) {
                        continue;
                    }
                    codes.put(rule.code(), rule);
                    names.put(rule.name(), rule);
                }
                byCode = codes;
                byName = names;
            }
        }

        private static Rule toRule(Object[] row) {
            String code = row.length > 0 ? str(row[0]) : null;
            String name = row.length > 1 ? str(row[1]) : null;
            if (blank(code) || blank(name)) {
                return null;
            }
            String rawPattern = row.length > 2 ? str(row[2]) : null;
            Pattern pattern;
            try {
                pattern = Pattern.compile(blank(rawPattern) ? "^[A-Za-z0-9-]{6,60}$" : rawPattern);
            } catch (PatternSyntaxException e) {
                // 字典里写坏一条正则不该让整个发货按钮不可用：退成宽松规则并留日志
                LoggerFactory.getLogger(CompanyCache.class)
                        .warn("物流公司 {} 的单号规则不是合法正则，已退回宽松匹配：{}", code, e.getMessage());
                pattern = Pattern.compile("^[A-Za-z0-9-]{6,60}$");
            }
            String example = row.length > 3 ? str(row[3]) : null;
            String hint = row.length > 4 ? str(row[4]) : null;
            return new Rule(code, name, pattern,
                    blank(example) ? "以快递面单为准" : example,
                    blank(hint) ? "与面单上的运单号一致" : hint,
                    trackTemplate(row.length > 5 ? str(row[5]) : null, row.length > 6 ? str(row[6]) : null));
        }

        /** 域名不在白名单里的行只给复制不给链接；模板必须 https 且带 __NO__ 占位 */
        private static String trackTemplate(String host, String path) {
            if (blank(host) || blank(path) || !ALLOWED_TRACK_HOSTS.contains(host.trim().toLowerCase(Locale.ROOT))) {
                return null;
            }
            if (!path.startsWith("/") || !path.contains(NO_PLACEHOLDER)) {
                return null;
            }
            return "https://" + host.trim() + path;
        }
    }
}
