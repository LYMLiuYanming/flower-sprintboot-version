package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.AdminOrderOpsService;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 后台订单 API（/api/admin/** 由 AuthInterceptor 保证仅管理员可达）。
 *
 * <p>写侧分两类：改状态但要回补库存的（取消、退款、确认收款）走 {@link OrderService} 逐单处理；
 * 发货、批量流转、备注、运费、轨迹节点这些由 {@link AdminOrderOpsService} 自己条件 UPDATE + 留痕，
 * 与并行的订单主改造互不覆盖。
 */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "后台 · 订单管理")
public class AdminOrderController {

    private static final int EXPORT_LIMIT = 5000;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("HH:mm");

    private final OrderService orderService;
    private final AdminOrderOpsService opsService;

    public AdminOrderController(OrderService orderService, AdminOrderOpsService opsService) {
        this.orderService = orderService;
        this.opsService = opsService;
    }

    /**
     * 列表（C25/G21）：status + keyword + anomaly 三个条件交给服务端，异常口径与看板计数同一条 SQL
     */
    @GetMapping
    public Result<List<OrderView>> list(@RequestParam(defaultValue = "1") int page,
                                        @RequestParam(defaultValue = "10") int limit,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String anomaly) {
        Page<OrderView> result = opsService.searchForAdmin(parseStatus(status), anomaly, keyword,
                Pages.of(page, limit));
        return Result.page(result.getContent(), result.getTotalElements());
    }

    @GetMapping("/recent")
    public Result<List<OrderView>> recent(@RequestParam(defaultValue = "8") int limit) {
        Page<OrderView> result = orderService.listForAdmin(null, null,
                PageRequest.of(0, Math.min(Math.max(limit, 1), Pages.MAX_SIZE)));
        return Result.ok(result.getContent());
    }

    /** C25 异常看板：三格计数 + 判定说明，点进去就是把 anomaly 参数带上的同一份列表 */
    @GetMapping("/anomaly-summary")
    public Result<List<AdminOrderOpsService.AnomalyCard>> anomalySummary() {
        return Result.ok(opsService.anomalySummary());
    }

    /** G20 一键定位：订单号完全一致的那条排最前，页面据此直接深链到详情 */
    @GetMapping("/locate")
    public Result<List<Map<String, Object>>> locate(@RequestParam(required = false) String q) {
        return Result.ok(opsService.locate(q));
    }

    /** C11/C12 字典：单号格式提示与查询链接模板都由服务端按域名白名单下发 */
    @GetMapping("/shipping-companies")
    public Result<List<AdminOrderOpsService.ExpressCompany>> shippingCompanies() {
        return Result.ok(opsService.companies());
    }

    /** C24 权限位：页面按它决定「调整运费」是否可点，服务端仍会再校验一次 */
    @GetMapping("/permissions")
    public Result<Map<String, Object>> permissions(HttpSession session) {
        User operator = CurrentUser.requireAdmin(session);
        return Result.ok(Map.of(
                "canAdjustFreight", opsService.canAdjustFreight(operator),
                "freightMax", AdminOrderOpsService.FREIGHT_MAX.toPlainString(),
                "reasonMinChars", AdminOrderOpsService.REASON_MIN_CHARS));
    }

    @GetMapping("/{id}")
    public Result<OrderView> detail(@PathVariable UUID id) {
        return Result.ok(orderService.detailForAdmin(id));
    }

    /** C12 详情页物流块：运单号原文 + 已校验过域名的查询链接 */
    @GetMapping("/{id}/shipping")
    public Result<AdminOrderOpsService.ShippingInfo> shipping(@PathVariable UUID id) {
        return Result.ok(opsService.shippingInfo(id));
    }

    /** C13/C23/C24：这笔订单被后台改过什么，一次取回 */
    @GetMapping("/{id}/ops")
    public Result<AdminOrderOpsService.OpsDetail> ops(@PathVariable UUID id, HttpSession session) {
        return Result.ok(opsService.opsDetail(id, CurrentUser.requireAdmin(session)));
    }

    @PostMapping("/{id}/status")
    public Result<OrderView> transit(@PathVariable UUID id,
                                     @Valid @RequestBody StatusForm form,
                                     HttpSession session) {
        return Result.ok("状态更新成功",
                orderService.transitByAdmin(id, OrderStatus.fromCode(form.status()), null, form.reason(),
                        operator(session).getUsername()));
    }

    /** C11 发货：物流公司必须在字典里，运单号必须过该公司的格式 */
    @PostMapping("/{id}/ship")
    public Result<OrderView> ship(@PathVariable UUID id,
                                  @Valid @RequestBody ShipForm form,
                                  HttpSession session) {
        opsService.ship(id, form.expressCompanyCode(), form.expressNo(), operator(session));
        return Result.ok("发货成功", orderService.detailForAdmin(id));
    }

    /** C22 批量发货：一批只填一次物流公司，运单号逐单一条，回执逐条给成败 */
    @PostMapping("/batch-ship")
    public Result<AdminOrderOpsService.BatchResult> batchShip(@Valid @RequestBody BatchShipForm form,
                                                              HttpSession session) {
        AdminOrderOpsService.BatchResult result =
                opsService.batchShip(form.expressCompanyCode(), form.toLines(), operator(session));
        return Result.ok(batchMessage(result), result);
    }

    /** G19 批量流转：只放行无库存副作用的目标状态，其余逐条回原因 */
    @PostMapping("/batch-status")
    public Result<AdminOrderOpsService.BatchResult> batchStatus(@Valid @RequestBody BatchStatusForm form,
                                                                HttpSession session) {
        AdminOrderOpsService.BatchResult result =
                opsService.batchTransit(form.ids(), OrderStatus.fromCode(form.status()), form.reason(),
                        operator(session));
        return Result.ok(batchMessage(result), result);
    }

    /** 补记轨迹节点：不改状态，只把门店侧的动作为收花人留痕 */
    @PostMapping("/{id}/trace")
    public Result<OrderView> addTrace(@PathVariable UUID id,
                                      @Valid @RequestBody TraceForm form,
                                      HttpSession session) {
        return Result.ok("轨迹已补记", orderService.addAdminTrace(id, form.title(), form.description(),
                operator(session).getUsername()));
    }

    /** C13 删除误记节点：原文先进留痕表再删，页面拿回刷新过的详情 */
    @DeleteMapping("/{id}/trace/{traceId}")
    public Result<OrderView> deleteTrace(@PathVariable UUID id, @PathVariable UUID traceId,
                                         @Valid @RequestBody TraceDeleteForm form,
                                         HttpSession session) {
        opsService.deleteTraceNode(id, traceId, form.reason(), operator(session));
        return Result.ok("误记节点已删除，原文已在操作留痕里存底", orderService.detailForAdmin(id));
    }

    /** C23 后台改备注：改前改后都落库留痕 */
    @PutMapping("/{id}/remark")
    public Result<OrderView> updateRemark(@PathVariable UUID id,
                                          @Valid @RequestBody RemarkForm form,
                                          HttpSession session) {
        opsService.updateRemark(id, form.remark(), form.reason(), operator(session));
        return Result.ok("备注已更新", orderService.detailForAdmin(id));
    }

    /** C24 运费人工微调 */
    @PostMapping("/{id}/freight")
    public Result<Map<String, Object>> adjustFreight(@PathVariable UUID id,
                                                     @Valid @RequestBody FreightForm form,
                                                     HttpSession session) {
        AdminOrderOpsService.FreightResult result =
                opsService.adjustFreight(id, form.freight(), form.reason(), operator(session));
        return Result.ok("运费已调整：¥" + result.freightBefore().toPlainString()
                        + " → ¥" + result.freightAfter().toPlainString()
                        + "，实付同步为 ¥" + result.payAfter().toPlainString(),
                Map.of(
                        "freightBefore", result.freightBefore(),
                        "freightAfter", result.freightAfter(),
                        "payBefore", result.payBefore(),
                        "payAfter", result.payAfter()));
    }

    /**
     * 订单导出 CSV（C21）：带 UTF-8 BOM，Excel 双击可正确识别中文。
     * 列里补上配送方式、预约时段、送达偏好与产地，条件与屏幕上的列表完全一致
     */
    @GetMapping("/export")
    public void export(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String keyword,
                       @RequestParam(required = false) String anomaly,
                       HttpServletResponse response) throws IOException {
        List<OrderView> orders = opsService
                .searchForAdmin(parseStatus(status), anomaly, keyword, PageRequest.of(0, EXPORT_LIMIT))
                .getContent();
        Map<UUID, String> origins = opsService.originNames(originIdsOf(orders));

        String filename = URLEncoder.encode("orders.csv", StandardCharsets.UTF_8);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        PrintWriter writer = response.getWriter();
        writer.write("\uFEFF");
        writer.println("订单号,下单用户,收货人,手机号,收货地址,商品总额,实付金额,状态,支付方式,物流公司,物流单号,"
                + "下单时间,支付时间,配送方式,预约时段,预计送达,送达偏好,联系偏好,产地,贺卡留言");
        for (OrderView order : orders) {
            writer.println(String.join(",",
                    csv(order.orderNo()), csv(order.userName()), csv(order.receiverName()), csv(order.receiverPhone()),
                    csv(order.receiverAddress()), csv(order.totalAmount()), csv(order.payAmount()),
                    csv(order.statusLabel()), csv(order.payMethod()), csv(order.expressCompany()), csv(order.expressNo()),
                    csv(order.createdAt() == null ? null : order.createdAt().format(DATE_TIME)),
                    csv(order.payTime() == null ? null : order.payTime().format(DATE_TIME)),
                    csv(order.deliveryMethodName()),
                    csv(slotText(order.deliverySlot())),
                    csv(order.expectedArriveAt() == null ? null : order.expectedArriveAt().format(DATE_TIME)),
                    csv(order.deliveryPreferenceName()), csv(order.contactPreferenceName()),
                    csv(originText(order, origins)),
                    csv(order.card() == null ? null : order.card().message())));
        }
        writer.flush();
    }

    // ------------------------------------------------------------------ 入参

    /** 批量发货的行：页面按勾选顺序逐行给出订单号与运单号 */
    public record ShipLineForm(@NotNull UUID orderId, @Size(max = 60) String expressNo) {
    }

    public record StatusForm(@NotBlank(message = "目标状态不能为空") String status,
                             @Size(max = 200, message = "原因不超过 200 字") String reason) {
    }

    public record ShipForm(@NotBlank(message = "请选择物流公司") @Size(max = 30) String expressCompanyCode,
                           @NotBlank(message = "请填写物流单号") @Size(max = 60, message = "物流单号不超过 60 个字符")
                           String expressNo) {
    }

    public record BatchShipForm(@NotBlank(message = "请选择物流公司") @Size(max = 30) String expressCompanyCode,
                                @NotEmpty(message = "请先勾选要发货的订单")
                                @Size(max = 100, message = "单次批量发货最多 100 笔，请分批操作")
                                @Valid List<ShipLineForm> lines) {

        List<AdminOrderOpsService.ShipLine> toLines() {
            List<AdminOrderOpsService.ShipLine> out = new ArrayList<>(lines.size());
            for (ShipLineForm line : lines) {
                out.add(new AdminOrderOpsService.ShipLine(line.orderId(), line.expressNo()));
            }
            return out;
        }
    }

    public record BatchStatusForm(@NotEmpty(message = "请先勾选要处理的订单")
                                  @Size(max = 100, message = "单次批量最多处理 100 笔，请分批操作") List<UUID> ids,
                                  @NotBlank(message = "目标状态不能为空") String status,
                                  @Size(max = 200, message = "原因不超过 200 字") String reason) {
    }

    public record TraceForm(@NotBlank(message = "请填写节点标题") @Size(max = 60, message = "节点标题不超过 60 字") String title,
                            @Size(max = 255, message = "节点说明不超过 255 字") String description) {
    }

    public record TraceDeleteForm(@NotBlank(message = "请填写删除原因")
                                  @Size(max = 200, message = "删除原因不超过 200 字") String reason) {
    }

    public record RemarkForm(@Size(max = 500, message = "备注不超过 500 字") String remark,
                             @NotBlank(message = "请填写修改原因") @Size(max = 200) String reason) {
    }

    public record FreightForm(@NotNull(message = "请填写调整后的运费") BigDecimal freight,
                              @NotBlank(message = "请填写调整原因") @Size(max = 200) String reason) {
    }

    // ------------------------------------------------------------------ 小工具

    private static User operator(HttpSession session) {
        return CurrentUser.requireAdmin(session);
    }

    private static String batchMessage(AdminOrderOpsService.BatchResult result) {
        return result.failed() == 0
                ? "批量完成：" + result.succeeded() + " 条已处理"
                : "批量完成：" + result.succeeded() + " 条已处理，" + result.failed() + " 条未成功，明细见列表";
    }

    /** 一笔订单可能涉及多个产地，先收集全部 id 再一次取回，避免逐单查库 */
    private static Collection<UUID> originIdsOf(List<OrderView> orders) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (OrderView order : orders) {
            if (order.items() == null) {
                continue;
            }
            order.items().stream()
                    .map(item -> item.product() == null ? null : item.product().originId())
                    .filter(java.util.Objects::nonNull)
                    .forEach(ids::add);
        }
        return ids;
    }

    private static String originText(OrderView order, Map<UUID, String> origins) {
        if (order.items() == null || order.items().isEmpty()) {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        order.items().forEach(item -> {
            UUID originId = item.product() == null ? null : item.product().originId();
            String name = originId == null ? null : origins.get(originId);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        });
        return names.isEmpty() ? null : String.join("、", names);
    }

    /**
     * 预约时段（C21）：库里存的是 ISO 起始时刻，导出成人能读的「几点到几点」。
     * 时段窗口固定 1 小时，与 ShippingPolicy.SLOT_WINDOW_HOURS 同口径；解析不了就原样带出。
     */
    private static String slotText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            java.time.LocalDateTime from = java.time.LocalDateTime.parse(raw.replace(' ', 'T'));
            return from.format(DATE_SHORT) + "–" + from.plusHours(1).format(TIME_ONLY);
        } catch (RuntimeException e) {
            return raw;
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }

    private static OrderStatus parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return OrderStatus.fromCode(status);
    }
}
