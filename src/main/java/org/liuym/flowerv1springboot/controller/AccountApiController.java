package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.AccountPolicy;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.OrderRepository;
import org.liuym.flowerv1springboot.service.AddressService;
import org.liuym.flowerv1springboot.service.CartService;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.FavoriteService;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.service.SessionService;
import org.liuym.flowerv1springboot.service.UserService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.liuym.flowerv1springboot.vo.SupportViews.AccountSummaryView;
import org.liuym.flowerv1springboot.vo.SupportViews.CouponAccountView;
import org.liuym.flowerv1springboot.vo.SupportViews.FavoriteView;
import org.liuym.flowerv1springboot.vo.SupportViews.PointRecordView;
import org.liuym.flowerv1springboot.vo.SupportViews.SessionView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 账户聚合接口（D13–D20）：券包增强、订单角标与倒计时、积分明细、设备会话、注销、数据导出。
 *
 * <p>归属边界一律取会话用户，不接受前端传 userId；口令不出现在任何响应或日志中。
 * 这些端点走 {@code CurrentUser.require} 自行鉴权（未登录抛 401 由全局异常收敛），
 * 不依赖拦截器路径注册，避免与并行批次的 WebMvcConfig 冲突。
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "前台 · 账户中心")
public class AccountApiController {

    /** 导出/明细一次读取的订单上限：单用户订单量有限，取满即可，避免分页拼接的复杂度 */
    private static final int ORDER_SCAN_LIMIT = 500;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final UserService userService;
    private final CouponService couponService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final FavoriteService favoriteService;
    private final AddressService addressService;
    private final CartService cartService;
    private final SessionService sessionService;
    private final int payTimeoutMinutes;

    public AccountApiController(UserService userService,
                               CouponService couponService,
                               OrderService orderService,
                               OrderRepository orderRepository,
                               FavoriteService favoriteService,
                               AddressService addressService,
                               CartService cartService,
                               SessionService sessionService,
                               @Value("${order.pay-timeout-minutes:30}") int payTimeoutMinutes) {
        this.userService = userService;
        this.couponService = couponService;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.favoriteService = favoriteService;
        this.addressService = addressService;
        this.cartService = cartService;
        this.sessionService = sessionService;
        this.payTimeoutMinutes = payTimeoutMinutes <= 0 ? AccountPolicy.PAY_TIMEOUT_MINUTES : payTimeoutMinutes;
    }

    /* ==================== D15 订单角标与待付款倒计时 + 侧栏角标聚合 ==================== */

    @GetMapping("/summary")
    public Result<AccountSummaryView> summary(HttpSession session) {
        User user = CurrentUser.require(session);
        LocalDateTime now = LocalDateTime.now();

        List<Order> orders = scanOrders(user.getId());
        long totalOrders = orders.size();
        Order newestPending = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING)
                .max(Comparator.comparing(Order::getCreatedAt))
                .orElse(null);
        long pendingPayCount = orders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        LocalDateTime deadline = newestPending == null ? null : newestPending.getCreatedAt().plusMinutes(payTimeoutMinutes);

        // 券包：区分未使用与「未使用且即将过期」
        List<UserCoupon> mine = couponService.findMine(user.getId());
        long unused = mine.stream()
                .filter(c -> UserCoupon.STATUS_UNUSED.equals(c.getStatus()) && !c.isExpiredAt(now))
                .count();
        long expiringSoon = mine.stream()
                .filter(c -> UserCoupon.STATUS_UNUSED.equals(c.getStatus()) && AccountPolicy.isExpiringSoon(c.getExpireAt(), now))
                .count();

        AccountSummaryView view = AccountSummaryView.of(
                totalOrders, pendingPayCount,
                newestPending == null ? null : newestPending.getOrderNo(),
                deadline, now,
                favoriteService.count(user.getId()),
                addressService.list(user.getId()).size(),
                unused, expiringSoon,
                user.getPoints());
        return Result.ok(view);
    }

    /* ==================== D13/D14 券包：即将过期置顶 + 门槛差额 ==================== */

    /**
     * 我的券包增强视图。传入结算/购物车上下文时（fromCart=true）门槛差额以购物车合计为基准，
     * 否则按 0 计，展示为「满 X 元可用」。排序：即将过期的未使用券置顶，其次按到期时间升序。
     */
    @GetMapping("/coupons")
    public Result<List<CouponAccountView>> coupons(@RequestParam(defaultValue = "true") boolean fromCart,
                                                   HttpSession session) {
        User user = CurrentUser.require(session);
        LocalDateTime now = LocalDateTime.now();
        BigDecimal base = fromCart ? cartSubtotal(user.getId()) : BigDecimal.ZERO;
        List<UserCoupon> mine = couponService.findMine(user.getId());
        List<CouponAccountView> views = mine.stream()
                .map(c -> CouponAccountView.from(c, base, now))
                .sorted(couponComparator(now))
                .toList();
        return Result.ok(views);
    }

    /* ==================== D20 积分明细（由订单推导，无独立流水表） ==================== */

    /**
     * 积分收支明细。direction=get 只看获得，use 只看扣减，all（默认）看全部。
     * 余额与总收支一并返回，前端顶部展示。
     */
    @GetMapping("/points")
    public Result<Map<String, Object>> points(@RequestParam(required = false, defaultValue = "all") String direction,
                                              HttpSession session) {
        User user = CurrentUser.require(session);
        List<PointRecordView> ledger = pointsLedger(user.getId());
        List<PointRecordView> filtered = ledger.stream()
                .filter(r -> "all".equalsIgnoreCase(direction)
                        || ("get".equalsIgnoreCase(direction) && "get".equals(r.direction()))
                        || ("use".equalsIgnoreCase(direction) && "use".equals(r.direction())))
                .toList();
        int totalGet = ledger.stream().filter(r -> "get".equals(r.direction())).mapToInt(PointRecordView::points).sum();
        int totalUse = ledger.stream().filter(r -> "use".equals(r.direction())).mapToInt(PointRecordView::points).sum();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balance", user.getPoints() == null ? 0 : user.getPoints());
        data.put("totalGet", totalGet);
        data.put("totalUse", totalUse);
        data.put("records", filtered);
        return Result.ok(data);
    }

    /* ==================== D18 登录设备与会话 ==================== */

    @GetMapping("/sessions")
    public Result<List<SessionView>> sessions(HttpSession session) {
        User user = CurrentUser.require(session);
        return Result.ok(sessionService.listDevices(user.getId(), deviceToken(session)));
    }

    /**
     * 退出其他设备：只失效除当前会话外的服务端会话记录，当前会话不受影响（安全红线）。
     */
    @PostMapping("/sessions/revoke-others")
    public Result<Integer> revokeOthers(HttpSession session) {
        User user = CurrentUser.require(session);
        int revoked = sessionService.revokeOthers(user.getId(), deviceToken(session));
        return Result.ok("已退出其他 " + revoked + " 台设备", revoked);
    }

    /* ==================== D16 注销：软删除 + 冷静期 ==================== */

    /** 注销状态：是否在冷静期、剩余天数、完成后的影响说明（供设置页展示） */
    @GetMapping("/deletion")
    public Result<Map<String, Object>> deletionStatus(HttpSession session) {
        User user = CurrentUser.require(session);
        Map<String, Object> data = new LinkedHashMap<>();
        LocalDateTime requested = user.getDeletionRequestedAt();
        boolean pending = requested != null;
        LocalDateTime doneAt = pending ? requested.plusDays(AccountPolicy.DELETION_COOLDOWN_DAYS) : null;
        long daysLeft = 0;
        if (pending) {
            LocalDateTime now = LocalDateTime.now();
            daysLeft = doneAt.isAfter(now) ? java.time.Duration.between(now, doneAt).toDays() + 1 : 0;
        }
        data.put("pending", pending);
        data.put("requestedAt", requested);
        data.put("cooldownDays", AccountPolicy.DELETION_COOLDOWN_DAYS);
        data.put("daysLeft", daysLeft);
        data.put("willDeleteAt", doneAt);
        return Result.ok(data);
    }

    /**
     * 提交注销申请（D16）：二次校验登录密码，防误触与劫持后会话下的静默注销。
     * 命中后进入 {@link AccountPolicy#DELETION_COOLDOWN_DAYS} 天冷静期，期内可撤销，绝不物理删数据。
     */
    @PostMapping("/deletion")
    public Result<Void> requestDeletion(@Valid @RequestBody UserDtos.DeletionRequest request, HttpSession session) {
        User user = CurrentUser.require(session);
        User fresh = userService.findById(user.getId())
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (!userService.checkPassword(fresh, request.password())) {
            throw new BusinessException("密码校验未通过，无法提交注销申请");
        }
        userService.requestDeletion(user.getId());
        // 会话里的用户快照刷新，设置页据此显示冷静期横幅
        session.setAttribute(CurrentUser.SESSION_KEY, userService.findById(user.getId()).orElse(fresh));
        return Result.ok("注销申请已提交，" + AccountPolicy.DELETION_COOLDOWN_DAYS + " 天内可撤销", null);
    }

    @PostMapping("/deletion/cancel")
    public Result<Void> cancelDeletion(HttpSession session) {
        User user = CurrentUser.require(session);
        userService.cancelDeletion(user.getId());
        session.setAttribute(CurrentUser.SESSION_KEY,
                userService.findById(user.getId()).orElse(user));
        return Result.ok("已撤销注销申请，账户恢复正常", null);
    }

    /* ==================== D17 数据导出（CSV） ==================== */

    /**
     * 导出本人订单 / 收藏 / 地址为 CSV。带 UTF-8 BOM 让 Excel 正确识别中文；
     * 只导出登录用户自己的数据，不含口令，收件手机号属本人数据原样导出。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String type, HttpSession session) {
        User user = CurrentUser.require(session);
        String csv = switch (type == null ? "" : type) {
            case "orders" -> exportOrders(user.getId());
            case "favorites" -> exportFavorites(user.getId());
            case "addresses" -> exportAddresses(user.getId());
            default -> throw new BusinessException("导出类型不合法");
        };
        String filename = "huayuxuan-" + type + "-" + LocalDate.now().format(DATE) + ".csv";
        byte[] body = withBom(csv);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    /* ==================== 内部实现 ==================== */

    private List<Order> scanOrders(UUID userId) {
        return orderRepository.findByUserId(userId, PageRequest.of(0, ORDER_SCAN_LIMIT)).getContent();
    }

    private BigDecimal cartSubtotal(UUID userId) {
        try {
            var cart = cartService.getCartByUserId(userId);
            if (cart == null || cart.getTotalAmount() == null) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(cart.getTotalAmount()).setScale(2, java.math.RoundingMode.HALF_UP);
        } catch (RuntimeException ex) {
            // 无购物车属正常，按 0 计门槛差额
            return BigDecimal.ZERO;
        }
    }

    private UUID deviceToken(HttpSession session) {
        Object token = session.getAttribute(CurrentUser.DEVICE_TOKEN_KEY);
        return token instanceof UUID uuid ? uuid : null;
    }

    private static Comparator<CouponAccountView> couponComparator(LocalDateTime now) {
        Comparator<CouponAccountView> unusedFirst = Comparator.comparing(v -> !UserCoupon.STATUS_UNUSED.equals(v.status()));
        Comparator<CouponAccountView> expiringFirst = Comparator.comparing((CouponAccountView v) -> !Boolean.TRUE.equals(v.expiringSoon()));
        Comparator<CouponAccountView> byExpire = Comparator.comparing(CouponAccountView::expireAt,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return unusedFirst.thenComparing(expiringFirst).thenComparing(byExpire);
    }

    /**
     * 从订单流水还原积分收支：抵扣发生在下单、奖励发生在支付；取消/退款时退还已抵扣分。
     * 时间倒序，越近越靠前。
     */
    private List<PointRecordView> pointsLedger(UUID userId) {
        List<Order> orders = scanOrders(userId);
        List<PointRecordView> ledger = new ArrayList<>();
        for (Order o : orders) {
            OrderStatus status = o.getStatus() == null ? OrderStatus.PENDING : o.getStatus();
            boolean deal = OrderStatus.DEAL_STATUSES.contains(status);
            boolean reversed = status == OrderStatus.CANCELLED || status == OrderStatus.REFUNDED;
            int used = o.getPointsUsed() == null ? 0 : o.getPointsUsed();
            int earned = o.getPointsEarned() == null ? 0 : o.getPointsEarned();

            if (used > 0 && !reversed) {
                ledger.add(PointRecordView.of("order_use", "use", "下单抵扣 · " + o.getOrderNo(),
                        o.getOrderNo(), used, o.getCreatedAt()));
            }
            if (used > 0 && reversed) {
                ledger.add(PointRecordView.of("order_use", "use", "下单抵扣 · " + o.getOrderNo(),
                        o.getOrderNo(), used, o.getCreatedAt()));
                ledger.add(PointRecordView.of("refund", "get", "订单" + (status == OrderStatus.REFUNDED ? "退款" : "取消") + "返还 · " + o.getOrderNo(),
                        o.getOrderNo(), used, o.getUpdatedAt()));
            }
            if (earned > 0 && deal) {
                LocalDateTime when = o.getPayTime() != null ? o.getPayTime() : o.getCreatedAt();
                ledger.add(PointRecordView.of("order_earn", "get", "消费奖励 · " + o.getOrderNo(),
                        o.getOrderNo(), earned, when));
            }
        }
        ledger.sort(Comparator.comparing(PointRecordView::time, Comparator.nullsLast(Comparator.reverseOrder())));
        return ledger;
    }

    private String exportOrders(UUID userId) {
        List<OrderView> orders = orderService.listForUser(userId, null);
        StringBuilder sb = new StringBuilder();
        sb.append("订单号,下单时间,状态,商品明细,商品件数,商品金额,优惠,实付,收货人,收货电话,收货地址\n");
        for (OrderView o : orders) {
            String detail = o.items() == null ? "" : o.items().stream()
                    .map(i -> (i.productName() == null ? "商品" : i.productName()) + " x" + i.quantity())
                    .collect(java.util.stream.Collectors.joining(" / "));
            int qty = o.items() == null ? 0 : o.items().stream()
                    .mapToInt(i -> i.quantity() == null ? 0 : i.quantity()).sum();
            sb.append(AccountPolicy.csvRow(
                    nz(o.orderNo()), fmt(o.createdAt()), nz(o.statusLabel()), nz(detail),
                    String.valueOf(qty), plain(o.totalAmount()), plain(o.discountAmount()), plain(o.payAmount()),
                    nz(o.receiverName()), nz(o.receiverPhone()), nz(o.receiverAddress()))).append('\n');
        }
        return sb.toString();
    }

    private String exportFavorites(UUID userId) {
        List<FavoriteView> favorites = favoriteService.list(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("收藏时间,商品名称,分类,价格,库存,状态\n");
        for (FavoriteView f : favorites) {
            var p = f.product();
            sb.append(AccountPolicy.csvRow(
                    fmt(f.createdAt()),
                    p == null ? "商品已删除" : nz(p.name()),
                    p == null ? "" : nz(p.categoryName()),
                    p == null ? "" : plain(p.price()),
                    p == null || p.stock() == null ? "" : String.valueOf(p.stock()),
                    p == null ? "已失效" : (Boolean.FALSE.equals(p.isActive()) ? "已下架" : "在售")))
                    .append('\n');
        }
        return sb.toString();
    }

    private String exportAddresses(UUID userId) {
        var addresses = addressService.list(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("收货人,手机号,省,市,区,详细地址,标签,是否默认,创建时间\n");
        addresses.forEach(a -> sb.append(AccountPolicy.csvRow(
                nz(a.receiverName()), nz(a.receiverPhone()), nz(a.province()), nz(a.city()), nz(a.district()),
                nz(a.detail()), nz(a.tag()), Boolean.TRUE.equals(a.isDefault()) ? "是" : "否", fmt(a.createdAt())))
                .append('\n'));
        return sb.toString();
    }

    private static byte[] withBom(String csv) {
        byte[] payload = csv.getBytes(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[bom.length + payload.length];
        System.arraycopy(bom, 0, out, 0, bom.length);
        System.arraycopy(payload, 0, out, bom.length, payload.length);
        return out;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }

    private static String fmt(LocalDateTime v) {
        return v == null ? "" : v.format(TS);
    }
}
