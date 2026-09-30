package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 后台订单操作能力（C11–C14 / C21–C25 / G19–G21）。
 *
 * <p>与 {@link OrderService} 分家的原因：并行的订单主改造正在改 OrderServiceImpl，
 * 而本批次的写侧只涉及「条件 UPDATE + 留痕」这类无库存副作用的动作，自己走 Repository 更安全。
 * 取消、退款、确认收款要回补库存与券，仍由 OrderService 逐单处理，本服务不重复实现一套回补。
 */
public interface AdminOrderOpsService {

    /**
     * 运费可填上限与原因最短字数：下发给页面的口径必须和服务端校验取同一个常量，
     * 否则改了校验忘改文案，运营会照着页面填出一个必然被拒的数字。
     */
    BigDecimal FREIGHT_MAX = new BigDecimal("999.00");
    int REASON_MIN_CHARS = 4;

    /**
     * 物流公司字典（C11/C12）。noHint 只用于页面提示，真正的格式校验在服务端按 noPattern 执行；
     * trackUrlTemplate 由服务端按域名白名单校验后才下发，模板里没有运单号时用 __NO__ 占位。
     */
    record ExpressCompany(String code, String name, String noExample, String noHint, String trackUrlTemplate) {
    }

    /** 批量回执的一行：name 放订单号，页面直接显示，未成功时 reason 写清原因 */
    record BatchItem(UUID id, String name, boolean ok, String reason) {
    }

    record BatchResult(List<BatchItem> items, int succeeded, int failed) {
    }

    /** C22 批量发货的一行：同一批只填一次物流公司，运单号逐单一条 */
    record ShipLine(UUID orderId, String expressNo) {
    }

    /** C12 详情页的物流块：trackUrl 为空表示无法安全外开，页面只显示复制不显示链接 */
    record ShippingInfo(UUID orderId, String orderNo, String companyCode, String companyName, String expressNo,
                        String trackUrl, LocalDateTime shipTime, String status, String statusLabel) {
    }

    record RemarkEdit(LocalDateTime at, String operator, String before, String after, String reason) {
    }

    record FreightAdjust(LocalDateTime at, String operator, BigDecimal freightBefore, BigDecimal freightAfter,
                         BigDecimal payBefore, BigDecimal payAfter, String reason) {
    }

    record TraceDeletion(LocalDateTime at, String deletedBy, String code, String title, String description,
                         String nodeOperator, String reason) {
    }

    /**
     * 详情页时间轴的节点：共享的 OrderView.traces 里没有节点 id，删不动，
     * 所以后台这屏用自己的查询取一份带 id 的
     */
    record TraceNode(String id, String code, String title, String description, String operator,
                     LocalDateTime at, boolean deletable) {
    }

    record OpsDetail(ShippingInfo shipping, List<RemarkEdit> remarkEdits, List<FreightAdjust> freightAdjustments,
                     List<TraceDeletion> traceDeletions, List<TraceNode> nodes, boolean canAdjustFreight) {
    }

    /** C25 异常看板的一格 */
    record AnomalyCard(String code, String label, String rule, long count) {
    }

    record FreightResult(BigDecimal freightBefore, BigDecimal freightAfter,
                         BigDecimal payBefore, BigDecimal payAfter) {
    }

    List<ExpressCompany> companies();

    /**
     * 后台列表与导出共用的一条查询（C25/G21/C21）：anomaly 传 ship_overdue / pay_pending /
     * refunding / any，空即不按异常过滤。看板上的计数与点进去的列表都出自这里，不会出现两套判定。
     */
    Page<OrderView> searchForAdmin(OrderStatus status, String anomaly, String keyword, Pageable pageable);

    /** 产地字典（C21）：商品上的 origin_id 翻成中文产地名 */
    Map<UUID, String> originNames(Collection<UUID> originIds);

    /** 详情页与操作留痕一次取回，页面不必为每个卡片各发一次请求 */
    OpsDetail opsDetail(UUID orderId, User operator);

    ShippingInfo shippingInfo(UUID orderId);

    /** C25/G21 同一口径：格子里的数字与点进去的列表用的是同一条 SQL */
    List<AnomalyCard> anomalySummary();

    /** G20 一键定位的候选 */
    List<Map<String, Object>> locate(String keyword);

    /** C11 发货：必填物流公司 + 运单号，且单号必须过该公司的格式 */
    void ship(UUID orderId, String companyCode, String expressNo, User operator);

    /** C22 批量发货：逐单独立事务，失败的那条不影响已成功的 */
    BatchResult batchShip(String companyCode, List<ShipLine> lines, User operator);

    /** G19 批量流转：只放行没有库存副作用的目标状态，其余逐条给出原因 */
    BatchResult batchTransit(List<UUID> orderIds, OrderStatus target, String reason, User operator);

    /** C13 删除误记节点：原文先进留痕表，再从轨迹表删除 */
    void deleteTraceNode(UUID orderId, UUID traceId, String reason, User operator);

    /** C23 后台改备注：改前改后都落 order_remark_edit */
    void updateRemark(UUID orderId, String remark, String reason, User operator);

    /** C24 运费微调：改完的实付与服务端 calculatePayAmount 口径一致，且不低于 ¥0.01 */
    FreightResult adjustFreight(UUID orderId, BigDecimal nextFreight, String reason, User operator);

    boolean canAdjustFreight(User operator);
}
