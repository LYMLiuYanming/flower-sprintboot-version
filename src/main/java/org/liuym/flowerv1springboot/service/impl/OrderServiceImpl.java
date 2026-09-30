package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.common.DeliveryPolicy;
import org.liuym.flowerv1springboot.common.GreetingCardPolicy;
import org.liuym.flowerv1springboot.common.OrderFlowPolicy;
import org.liuym.flowerv1springboot.common.Pages;
import org.liuym.flowerv1springboot.common.PointsPolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.*;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.InviteService;
import org.liuym.flowerv1springboot.service.IdempotencyService;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.service.SlotQuotaService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);
    private static final DateTimeFormatter ORDER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TRACE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderTraceRepository orderTraceRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final ReviewRepository reviewRepository;
    private final CartRepository cartRepository;
    private final CheckoutQueryRepository checkoutQueryRepository;
    private final OrderRefundRepository orderRefundRepository;
    private final CouponService couponService;
    private final InviteService inviteService;
    private final SlotQuotaService slotQuotaService;
    private final IdempotencyService idempotencyService;

    @Value("${order.pay-timeout-minutes:30}")
    private int payTimeoutMinutes;

    /** 发货后自动确认收货的天数（C18），与 OrderFlowPolicy 的默认口径一致 */
    @Value("${order.auto-receive-days:15}")
    private int autoReceiveDays;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OrderItemRepository orderItemRepository,
                            OrderTraceRepository orderTraceRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            AddressRepository addressRepository,
                            ReviewRepository reviewRepository,
                            CartRepository cartRepository,
                            CheckoutQueryRepository checkoutQueryRepository,
                            OrderRefundRepository orderRefundRepository,
                            CouponService couponService,
                            InviteService inviteService,
                            SlotQuotaService slotQuotaService,
                            IdempotencyService idempotencyService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderTraceRepository = orderTraceRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.reviewRepository = reviewRepository;
        this.cartRepository = cartRepository;
        this.checkoutQueryRepository = checkoutQueryRepository;
        this.orderRefundRepository = orderRefundRepository;
        this.couponService = couponService;
        this.inviteService = inviteService;
        this.slotQuotaService = slotQuotaService;
        this.idempotencyService = idempotencyService;
    }

    @Override
    public OrderView create(UUID userId, OrderDtos.CreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));

        // 幂等凭证先行抢占：双击/网络重试只会有一笔走到建单，另一笔原样回吐首次订单
        Order replayed = idempotencyService.redeem(userId, request.idempotencyKey());
        if (replayed != null) {
            log.info("重复提交命中幂等凭证 orderNo={} user={}", replayed.getOrderNo(), userId);
            return view(replayed, Set.of(), false, true);
        }

        Receiver receiver = resolveReceiver(userId, request);

        // 购物车行的备注与包装标记必须在任何条件更新之前摊平：reduceStock 会清空一级缓存
        Map<UUID, CartRow> cartRows = cartRowsOf(userId);
        List<RequestedLine> requested = mergeLines(request, cartRows);
        Map<UUID, ProductSnapshot> catalog = loadProducts(requested);

        // B19：一次性把所有不可成交的行报完，而不是改一行退一次
        List<CheckoutPolicy.LineIssue> issues = sellableIssues(requested, catalog);
        if (!issues.isEmpty()) {
            throw new CheckoutPolicy.CheckoutValidation("items", CheckoutPolicy.lineErrorText(issues),
                    issues.stream().map(i -> "「" + i.product() + "」" + i.reason()).toList());
        }

        List<ConfirmedLine> confirmed = new ArrayList<>();
        for (RequestedLine line : requested) {
            ProductSnapshot product = catalog.get(line.productId());
            // 条件更新扣库存：stock >= quantity 才生效，行锁保证不会超卖
            if (productRepository.reduceStock(line.productId(), line.quantity()) == 0) {
                throw new CheckoutPolicy.CheckoutValidation("items",
                        "商品「" + product.name() + "」库存不足，请减少数量或另选花礼", List.of());
            }
            productRepository.increaseSalesCount(line.productId(), line.quantity());
            confirmed.add(new ConfirmedLine(product.productId(), product.name(), product.image(),
                    product.categoryId(), product.price(), line.quantity(),
                    product.price().multiply(BigDecimal.valueOf(line.quantity())).setScale(2, RoundingMode.HALF_UP),
                    product.unitWeightKg().multiply(BigDecimal.valueOf(line.quantity())),
                    line.note(), line.giftWrap()));
        }

        BigDecimal totalAmount = confirmed.stream().map(ConfirmedLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(totalAmount);
        order.setReceiverName(receiver.name());
        order.setReceiverPhone(receiver.phone());
        order.setReceiverAddress(receiver.address());
        order.setRemark(trimToNull(request.remark()));
        order.setPayMethod(blankToNull(request.payMethod()));

        LocalDateTime placedAt = LocalDateTime.now();
        ShippingPolicy.Method method = ShippingPolicy.resolve(request.deliveryMethod());
        BigDecimal weight = ShippingPolicy.totalWeightKg(confirmed.stream().map(ConfirmedLine::lineWeight).toList());
        LocalDateTime slot = method.slotAware()
                ? requireBookableSlot(method, placedAt, request.deliverySlot())
                : null;
        // 先抢运力名额：约满直接抛错整单回滚，下单失败不会留下占位
        slotQuotaService.occupy(slot);
        order.setDeliveryMethod(method.code());
        order.setDeliveryWeight(weight);
        order.setDeliverySlot(ShippingPolicy.slotText(slot));
        order.setExpectedArriveAt(ShippingPolicy.arriveAt(method, placedAt, slot));
        // 包邮门槛按商品总价判定，优惠只减免货款，不影响运费口径
        BigDecimal deliveryFee = ShippingPolicy.freightOf(method, totalAmount, weight);
        // B01 礼品包装按整单收一次；order 表暂无独立包装费列，并入配送费用保证实付与结算页一致
        BigDecimal giftWrapFee = CheckoutPolicy.giftWrapFee(wrappedKinds(confirmed, request));
        order.setFreight(deliveryFee.add(giftWrapFee));
        // 门店自提没有"放到门口"这回事，落库偏好只会误导配送员
        if (!DeliveryPolicy.SELF_PICKUP_METHOD.equals(method.code())) {
            order.setDeliveryPreference(DeliveryPolicy.placementCodeOrNull(request.deliveryPreference()));
            order.setContactPreference(DeliveryPolicy.contactCodeOrNull(request.contactPreference()));
        }

        String cardRecipient = trimToNull(request.cardRecipient());
        String cardMessage = trimToNull(request.cardMessage());
        String cardSignature = trimToNull(request.cardSignature());
        if (GreetingCardPolicy.requested(cardRecipient, cardMessage, cardSignature)) {
            order.setCardStyle(GreetingCardPolicy.normalizeStyle(request.cardStyle()));
            order.setCardRecipient(cardRecipient);
            order.setCardMessage(cardMessage);
            order.setCardSignature(cardSignature);
        }

        List<String> adjustments = new ArrayList<>();
        if (giftWrapFee.compareTo(BigDecimal.ZERO) > 0) {
            adjustments.add("礼品包装 ¥" + giftWrapFee.toPlainString() + "（已并入配送费用）");
        }
        BigDecimal discount = BigDecimal.ZERO;
        if (user.isVip()) {
            discount = discount.add(PointsPolicy.vipDiscount(order.getTotalAmount()));
        }
        // 优惠券先于积分：积分抵扣上限按券后金额算，避免券 + 积分把订单抵穿
        List<CouponPolicy.Line> couponLines = confirmed.stream()
                .map(line -> new CouponPolicy.Line(line.categoryId(), line.subtotal(), line.productId()))
                .toList();
        BigDecimal couponDiscount = BigDecimal.ZERO;
        if (request.userCouponId() != null) {
            assertCouponUsableOnTheseLines(userId, request.userCouponId(), couponLines);
            couponDiscount = couponService.requireUsableForOrder(userId, request.userCouponId(), couponLines);
            order.setCouponAmount(couponDiscount);
            order.setUserCouponId(request.userCouponId());
            discount = discount.add(couponDiscount);
        }
        CheckoutPolicy.PointsClamp points = applyPointsDeduction(user, order.getTotalAmount(),
                discount, request.pointsDeduction(), adjustments);
        discount = discount.add(points.deduction());

        order.setDiscountAmount(discount.setScale(2, RoundingMode.HALF_UP));
        order.calculatePayAmount();
        // B23：券 + 积分把金额抵平时仍按 ¥0.01 收，文案与结算页同源
        if (order.getPayAmount().compareTo(CheckoutPolicy.MIN_PAY_AMOUNT) < 0) {
            order.setPayAmount(CheckoutPolicy.MIN_PAY_AMOUNT);
            adjustments.add(CheckoutPolicy.minPayText());
        }
        order.setPointsUsed(points.points());

        Order saved = orderRepository.save(order);
        idempotencyService.attach(saved.getId(), request.idempotencyKey());
        confirmed.forEach(line -> {
            OrderItem item = new OrderItem();
            // 前面的条件更新已清空一级缓存，这里重新取受管引用，不再复用游离的 Product
            item.setProduct(productRepository.getReferenceById(line.productId()));
            item.setProductName(line.name());
            item.setProductImage(line.image());
            item.setPrice(line.price());
            item.setQuantity(line.quantity());
            item.calculateSubtotal();
            item.setItemNote(line.note());
            item.setGiftWrap(line.giftWrap());
            // 用 addItem 而不是裸 setOrder：建单响应里的明细要与结算页清单一致，否则前端拿到空 items
            saved.addItem(item);
            orderItemRepository.save(item);
        });
        if (request.userCouponId() != null) {
            // 条件核销：并发下同一张券只能被一单用掉，失败则整单回滚（库存改动一并撤销）
            if (!couponService.consume(request.userCouponId(), userId, saved.getId())) {
                throw new CheckoutPolicy.CheckoutValidation("userCouponId", "该优惠券已被使用", List.of());
            }
        }

        trace(saved, "created", "订单提交",
                "共 " + confirmed.size() + " 种花礼，实付 ¥" + saved.getPayAmount().toPlainString()
                        + (saved.getCouponAmount() == null ? "" : "（含券抵扣 ¥" + saved.getCouponAmount().toPlainString() + "）"),
                "系统");
        trace(saved, "plan", "配送安排", deliveryPlanText(saved), "系统");
        if (!adjustments.isEmpty()) {
            trace(saved, "adjust", "金额核算", String.join("；", adjustments), "系统");
        }
        if (saved.getCardStyle() != null) {
            trace(saved, "card", "贺卡定制", "样式「" + GreetingCardPolicy.styleName(saved.getCardStyle()) + "」· 致 "
                    + (saved.getCardRecipient() == null ? "收花人" : saved.getCardRecipient()), "系统");
        }
        long notedLines = confirmed.stream().filter(line -> line.note() != null && !line.note().isBlank()).count();
        if (notedLines > 0) {
            trace(saved, "note", "花束备注", notedLines + " 束写了单独说明，包扎时按行备注执行", "系统");
        }

        if (Boolean.TRUE.equals(request.clearCart())) {
            clearCart(userId);
        }

        log.info("订单创建成功 orderNo={} user={} amount={} freight={} delivery={} pointsUsed={}",
                saved.getOrderNo(), userId, saved.getPayAmount(), saved.getFreight(),
                saved.getDeliveryMethod(), saved.getPointsUsed());
        return view(saved, Set.of(), false, true);
    }

    /**
     * 结算页试算与建单共用的行摊平（B02）：备注优先取购物车行，其次取请求里的行备注。
     * 同商品多行合并数量，避免重复扣减同一行库存。
     */
    private List<RequestedLine> mergeLines(OrderDtos.CreateRequest request, Map<UUID, CartRow> cartRows) {
        Map<UUID, int[]> quantities = new LinkedHashMap<>();
        Map<UUID, String> notes = new LinkedHashMap<>();
        Map<UUID, Boolean> wraps = new LinkedHashMap<>();
        for (OrderDtos.ItemRequest item : request.items()) {
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new CheckoutPolicy.CheckoutValidation("items", "购买数量必须大于 0", List.of());
            }
            CartRow row = item.cartItemId() == null ? null : cartRows.get(item.cartItemId());
            quantities.computeIfAbsent(item.productId(), key -> new int[1])[0] += item.quantity();
            String note = row != null && row.note() != null && !row.note().isBlank() ? row.note() : item.note();
            // 用 compute 而不是 merge：merge 传 null 会把已有备注整条删掉，同商品多行时后面的空备注会吃掉前面的说明。
            // 同商品的多行会合并成一单行，所以只保留第一条非空备注——拼接会在 OrderItem 的 100 字清洗里被截半
            notes.compute(item.productId(), (key, existing) -> {
                if (existing != null && !existing.isBlank()) {
                    return existing;
                }
                return CheckoutPolicy.cleanNote(note);
            });
            boolean wrapped = Boolean.TRUE.equals(request.giftWrap())
                    || (row != null && row.giftWrap()) || Boolean.TRUE.equals(wraps.get(item.productId()));
            wraps.put(item.productId(), wrapped);
        }
        List<RequestedLine> lines = new ArrayList<>();
        quantities.forEach((productId, qty) ->
                lines.add(new RequestedLine(productId, qty[0], notes.get(productId),
                        Boolean.TRUE.equals(wraps.get(productId)))));
        return lines;
    }

    private Map<UUID, CartRow> cartRowsOf(UUID userId) {
        return cartRepository.findByUserIdWithItems(userId).map(cart -> {
            Map<UUID, CartRow> rows = new LinkedHashMap<>();
            for (CartItem row : cart.getItems()) {
                if (row.getId() != null) {
                    rows.put(row.getId(), new CartRow(row.getNote(), row.isGiftWrapped()));
                }
            }
            return rows;
        }).orElseGet(Map::of);
    }

    /**
     * 商品快照（B19/B20）：必须在任何 @Modifying(clearAutomatically) 之前把懒加载关联摊成纯值。
     * reduceStock 之后 Product 就成了游离对象，分类 id 只能在这里取；即使 Product 上的
     * @Fetch(JOIN) 被别的批次改成纯 LAZY，建单流程也不会漏出 LazyInitializationException。
     */
    private Map<UUID, ProductSnapshot> loadProducts(List<RequestedLine> lines) {
        List<UUID> ids = lines.stream().map(RequestedLine::productId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProductSnapshot> catalog = new LinkedHashMap<>();
        for (Product product : productRepository.findAllById(ids)) {
            UUID categoryId = product.getCategory() == null ? null : product.getCategory().getId();
            catalog.put(product.getId(), new ProductSnapshot(product.getId(), product.getName(),
                    product.getMainImage(), categoryId, product.getPrice(),
                    ShippingPolicy.unitWeightKg(product.getWeight()),
                    Boolean.TRUE.equals(product.getIsActive()),
                    product.getStock() == null ? 0 : product.getStock()));
        }
        return catalog;
    }

    /** 逐行给出不可成交原因（B19）：下架、售罄、库存不足、单行超上限都按用户看得懂的话列出 */
    private List<CheckoutPolicy.LineIssue> sellableIssues(List<RequestedLine> lines,
                                                          Map<UUID, ProductSnapshot> catalog) {
        List<CheckoutPolicy.LineIssue> issues = new ArrayList<>();
        for (RequestedLine line : lines) {
            ProductSnapshot product = catalog.get(line.productId());
            String reason = product == null ? "不存在或已下架" : issueReason(product, line.quantity());
            if (reason != null) {
                issues.add(new CheckoutPolicy.LineIssue(
                        product == null ? String.valueOf(line.productId()) : product.name(), reason));
            }
        }
        return issues;
    }

    private static String issueReason(ProductSnapshot product, int quantity) {
        if (!product.active()) {
            return "已下架";
        }
        int stock = product.stock();
        if (stock <= 0) {
            return "已售罄";
        }
        if (quantity > stock) {
            return "库存不足，仅剩 " + stock + " 件";
        }
        if (quantity > CheckoutPolicy.MAX_QUANTITY_PER_ITEM) {
            return "单个花礼最多购买 " + CheckoutPolicy.MAX_QUANTITY_PER_ITEM + " 件";
        }
        return null;
    }

    /** 整单只收一次包装费，这里给出勾选包装的行数（B01） */
    private static long wrappedKinds(List<ConfirmedLine> confirmed, OrderDtos.CreateRequest request) {
        if (Boolean.TRUE.equals(request.giftWrap())) {
            return confirmed.size();
        }
        return confirmed.stream().filter(ConfirmedLine::giftWrap).count();
    }

    /**
     * B20：券门槛按实际成交行计算；分类专享券在本单一行都没命中时给专属原因，
     * 否则用户只会看到含糊的「未达门槛」而反复加购
     */
    private void assertCouponUsableOnTheseLines(UUID userId, UUID userCouponId, List<CouponPolicy.Line> lines) {
        UserCoupon held = checkoutQueryRepository.findCouponOfUser(userCouponId, userId);
        if (held == null) {
            throw new CheckoutPolicy.CheckoutValidation("userCouponId", "优惠券不存在或不属于当前账号", List.of());
        }
        boolean categoryScope = Coupon.SCOPE_CATEGORY.equals(held.getScope()) && held.getCategoryId() != null;
        if (!categoryScope) {
            return;
        }
        List<UUID> scopeIds = checkoutQueryRepository.categoryScopeIds(held.getCategoryId());
        long matched = lines.stream()
                .filter(line -> line.categoryId() != null && scopeIds.contains(line.categoryId()))
                .count();
        CheckoutPolicy.couponMismatch(held.getName(), true, matched).ifPresent(text -> {
            throw new CheckoutPolicy.CheckoutValidation("userCouponId", text, List.of(text));
        });
    }

    /**
     * B22：预约时段先按运力日历判可约（给得出"这个时段已约满"这种具体原因），
     * 真正的互斥仍由 slotQuotaService.occupy 的条件更新兜底
     */
    private LocalDateTime requireBookableSlot(ShippingPolicy.Method method, LocalDateTime now, String slotText) {
        LocalDateTime slot;
        try {
            slot = ShippingPolicy.requireSlot(method, now, ShippingPolicy.parseSlot(slotText));
        } catch (BusinessException e) {
            throw new CheckoutPolicy.CheckoutValidation("deliverySlot", e.getMessage(), List.of());
        }
        boolean bookable = slotQuotaService.calendar(now, ShippingPolicy.MAX_SLOT_DAYS).stream()
                .filter(item -> item.date().equals(slot.toLocalDate()) && item.hour() == slot.getHour())
                .anyMatch(item -> !item.closed() && item.remaining() > 0);
        if (!bookable) {
            String text = ShippingPolicy.slotDateText(slot) + " " + ShippingPolicy.slotHourText(slot.getHour())
                    + " 已约满，请换个时段或改选「次日达」";
            throw new CheckoutPolicy.CheckoutValidation("deliverySlot", text, List.of(text));
        }
        return slot;
    }

    /**
     * 积分抵扣（B21）：先按折后 20% 与持有积分收敛，超额自动截断并把原因写进金额核算轨迹，
     * 再用条件更新扣减，余额被并发消耗时直接失败
     */
    private CheckoutPolicy.PointsClamp applyPointsDeduction(User user, BigDecimal goods, BigDecimal discountSoFar,
                                                            BigDecimal requested, List<String> adjustments) {
        CheckoutPolicy.PointsClamp clamp = CheckoutPolicy.clampPoints(requested,
                goods.subtract(discountSoFar), user.getPoints());
        if (clamp.deduction().compareTo(BigDecimal.ZERO) <= 0) {
            if (requested != null && requested.compareTo(BigDecimal.ZERO) > 0) {
                adjustments.add("本单可用积分抵扣 ¥" + clamp.cap().toPlainString()
                        + "（" + CheckoutPolicy.POINTS_CAP_TEXT + "）");
            }
            return clamp;
        }
        if (clamp.truncated()) {
            adjustments.add("积分抵扣已截断至 ¥" + clamp.deduction().toPlainString()
                    + "（" + CheckoutPolicy.POINTS_CAP_TEXT + "）");
        }
        if (userRepository.addPoints(user.getId(), -clamp.points()) == 0) {
            throw new CheckoutPolicy.CheckoutValidation("pointsDeduction", "可用积分不足", List.of());
        }
        return clamp;
    }

    /** 购物车行快照：备注与包装标记（B01/B02） */
    private record CartRow(String note, boolean giftWrap) {
    }

    /** 商品快照：建单全程只读它，不再碰受管实体（B19/B20 的分类与库存口径都取自这里） */
    private record ProductSnapshot(UUID productId, String name, String image, UUID categoryId, BigDecimal price,
                                   BigDecimal unitWeightKg, boolean active, int stock) {
    }

    /** 请求合并后的行：只有 id 与数量，供校验使用 */
    private record RequestedLine(UUID productId, int quantity, String note, boolean giftWrap) {
    }

    /** 校验通过后的行快照：条件更新清空一级缓存后仍可安全读取（含分类与重量，B20 的算价基数） */
    private record ConfirmedLine(UUID productId, String name, String image, UUID categoryId, BigDecimal price,
                                 Integer quantity, BigDecimal subtotal, BigDecimal lineWeight, String note,
                                 boolean giftWrap) {
    }

    @Override
    public OrderView pay(UUID orderId, UUID userId, String payMethod) {
        Order order = requireOwned(orderId, userId);
        if (order.getStatus() != OrderStatus.PENDING) {
            // C10：已取消/已退款的单要说清"为什么不能支付"，而不是只回一句状态不对
            String denial = order.getStatus().transitionDenial(OrderStatus.PAID);
            throw new BusinessException(409, denial == null
                    ? "订单当前状态为「" + label(order) + "」，无法支付" : denial);
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getPayTime() == null && isPayTimeout(order, now)) {
            throw new BusinessException(409, "订单已超过 " + payTimeoutMinutes + " 分钟支付时限，请重新下单");
        }
        int updated = orderRepository.markPaid(orderId, OrderStatus.PENDING, OrderStatus.PAID, now);
        if (updated == 0) {
            // 支付回调可能和关单任务撞在一起，回读真实状态说明白（C10）
            throw new BusinessException(409, statusChangedHint(orderId, OrderStatus.PENDING));
        }
        int earned = PointsPolicy.earnedPoints(order.getPayAmount());
        orderRepository.updateAfterPaid(orderId, blankToNull(payMethod), earned);
        if (earned > 0) {
            userRepository.addPoints(userId, earned);
        }
        upgradeMemberLevelIfNeeded(userId);
        trace(order, "paid", "支付完成", payMethodText(payMethod) + "，花艺师开始修剪搭配"
                + (earned > 0 ? "；本单获得 " + earned + " 积分" : ""), "系统");
        return view(orderRepository.findDetailById(orderId).orElseThrow(), reviewedItemIds(orderId), false, true);
    }

    /**
     * 累计消费满 1000 元自动升级为 VIP
     */
    private void upgradeMemberLevelIfNeeded(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.isVip()) {
            return;
        }
        BigDecimal spent = orderRepository.sumPayAmount(List.of(OrderStatus.PAID, OrderStatus.PROCESSING,
                OrderStatus.SHIPPED, OrderStatus.DELIVERED, OrderStatus.COMPLETED));
        if (spent != null && spent.compareTo(new BigDecimal("1000")) >= 0) {
            userRepository.updateMemberLevel(userId, User.MEMBER_VIP);
        }
    }

    @Override
    public OrderView cancelByUser(UUID orderId, UUID userId, OrderDtos.CancelRequest request) {
        Order order = requireOwned(orderId, userId);
        if (!order.getStatus().isCancellable()) {
            // C10：给出"花礼已在路上，无法再取消订单"这类可执行原因，而不是一句操作失败
            throw new BusinessException(409, order.getStatus().transitionDenial(OrderStatus.CANCELLED));
        }
        String reason = OrderFlowPolicy.reasonText(OrderFlowPolicy.CANCEL_REASONS,
                request == null ? null : request.reasonCode(),
                request == null ? null : request.reasonText(), null);
        if (reason == null) {
            throw new BusinessException(400, "请选择或填写取消原因，门店需要据此核对");
        }
        return closeOrder(order, OrderStatus.CANCELLED, reason, "用户 " + order.getUser().getUsername());
    }

    @Override
    public OrderView confirmReceipt(UUID orderId, UUID userId) {
        Order order = requireOwned(orderId, userId);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException(409, order.getStatus().transitionDenial(OrderStatus.DELIVERED));
        }
        LocalDateTime now = LocalDateTime.now();
        // markReceived 落签收时间：C26 的「送达 N 天后引导评价」必须有这个锚点
        int updated = orderRepository.markReceived(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED, now);
        if (updated == 0) {
            throw new BusinessException(409, statusChangedHint(orderId, OrderStatus.SHIPPED));
        }
        trace(order, "delivered", "已签收", "收花人确认已收到花礼", "用户");
        return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), false, true);
    }

    @Override
    public OrderView transitByAdmin(UUID orderId, OrderStatus target, OrderDtos.ShipRequest ship, String reason,
                                    String operator) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        OrderStatus current = order.getStatus();
        // C10：非法跃迁要说清"为什么不行 + 下一步做什么"，而不是一句不允许变更状态
        String denial = current.transitionDenial(target);
        if (denial != null) {
            throw new BusinessException(409, denial);
        }

        if (target == OrderStatus.SHIPPED) {
            if (ship == null || ship.expressCompany() == null || ship.expressNo() == null) {
                throw new BusinessException("发货需填写物流公司与单号");
            }
            int updated = orderRepository.markShipped(orderId, current, OrderStatus.SHIPPED,
                    ship.expressCompany().trim(), ship.expressNo().trim(), LocalDateTime.now());
            requireTransit(updated, orderId, current);
            Order shipped = orderRepository.findDetailById(orderId).orElseThrow();
            trace(shipped, "shipped", "已出库发货",
                    ship.expressCompany().trim() + " " + ship.expressNo().trim()
                            + " · " + ShippingPolicy.resolve(order.getDeliveryMethod()).name(),
                    operator);
            return view(shipped, Set.of(), true, true);
        }

        if (target == OrderStatus.CANCELLED || target == OrderStatus.REFUNDED) {
            return closeOrder(order, target, reason == null ? "管理员操作" : reason, operator);
        }

        if (target == OrderStatus.PAID) {
            int updated = orderRepository.markPaid(orderId, current, OrderStatus.PAID, LocalDateTime.now());
            requireTransit(updated, orderId, current);
            int earned = PointsPolicy.earnedPoints(order.getPayAmount());
            orderRepository.updatePointsEarned(orderId, earned);
            userRepository.addPoints(order.getUser().getId(), earned);
            trace(order, "paid", "支付完成", "后台确认收款" + (earned > 0 ? "；本单获得 " + earned + " 积分" : ""),
                    operator);
        } else {
            requireTransit(orderRepository.transitStatus(orderId, current, target), orderId, current);
            trace(order, traceCodeOf(target), traceTitleOf(target), traceDetailOf(target, order), operator);
        }
        return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), true, true);
    }

    /**
     * 后台补记自定义轨迹节点：花材到港、上门布置等门店侧动作不改变订单状态，只留痕
     */
    @Override
    public OrderView addAdminTrace(UUID orderId, String title, String description, String operator) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        String trimmed = title == null ? null : title.trim();
        if (trimmed == null || trimmed.isEmpty()) {
            throw new BusinessException("请填写轨迹节点标题");
        }
        trace(order, "custom", trimmed, trimToNull(description), operator);
        return view(order, Set.of(), true, true);
    }

    /**
     * 关单（取消/退款）统一出口：状态 CAS 决定谁赢，资源回退再经 rollback_at 闸门一次。
     * 两层判断叠加后，「用户取消 + 后台退款」两条路径并发进来也只回补一次（C09/C20）。
     */
    private OrderView closeOrder(Order order, OrderStatus target, String reason, String operator) {
        OrderStatus current = order.getStatus();
        String denial = current.transitionDenial(target);
        if (denial != null) {
            throw new BusinessException(409, denial);
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新带 clearAutomatically，执行后一级缓存被清空，此后再碰懒加载就是游离对象；
        // 所以回补明细、买家与时段必须在 CAS 之前摊平成普通值（定时任务捞出的订单是纯懒加载）
        ReleasePlan plan = flattenForRelease(order, current);
        int updated = target == OrderStatus.REFUNDED
                ? orderRepository.markRefunded(plan.orderId(), current, target, RefundStatus.REFUNDED,
                plan.payAmount(), trimToNull(reason), now)
                : orderRepository.markClosed(plan.orderId(), current, target, trimToNull(reason), now);
        if (updated == 0) {
            throw new BusinessException(409, statusChangedHint(plan.orderId(), current));
        }
        ReleaseResult result = releaseResources(plan, now);
        if (target == OrderStatus.REFUNDED) {
            settleOpenRefunds(plan.orderId(), operator, now);
        }
        trace(order, target == OrderStatus.REFUNDED ? "refunded" : "cancelled",
                target == OrderStatus.REFUNDED ? "订单已退款" : "订单已取消",
                (reason == null ? "" : reason + " · ") + releaseText(plan, result),
                operator);
        log.info("订单关闭 orderNo={} {} -> {} reason={} released={}", plan.orderNo(),
                current.getCode(), target.getCode(), reason, result.released());
        return view(orderRepository.findDetailById(plan.orderId()).orElseThrow(), Set.of(), false, true);
    }

    /** 退款在途单的收尾：后台直接改状态退款时，把 pending/reviewing 的申请单一并落成已退款 */
    private void settleOpenRefunds(UUID orderId, String operator, LocalDateTime now) {
        for (OrderRefund record : orderRefundRepository.findOpenOfOrder(orderId, RefundStatus.OPEN_LIST)) {
            orderRefundRepository.transit(record.getId(), record.getStatus(), RefundStatus.REFUNDED,
                    operator, null, null, now, now);
        }
    }

    /** 回退动作的可读结果，用于轨迹文案 */
    private static String releaseText(ReleasePlan plan, ReleaseResult result) {
        if (!result.released()) {
            return "本单资源此前已回退，未重复回补";
        }
        StringBuilder text = new StringBuilder();
        if (plan.restock().isEmpty()) {
            text.append("花材已送出，库存不再回补");
        } else {
            text.append("库存已回补 ").append(plan.restock().size()).append(" 个花礼");
        }
        if (result.pointsReturned() > 0) {
            text.append("，返还 ").append(result.pointsReturned()).append(" 积分");
        }
        if (result.pointsRevoked() > 0) {
            text.append("，收回本单发放的 ").append(result.pointsRevoked()).append(" 积分");
        }
        if (result.couponReturned()) {
            text.append("，优惠券已退回券包");
        }
        if (plan.deliverySlot() != null) {
            text.append("，预约时段名额已释放");
        }
        return text.toString();
    }

    /**
     * 资源回退（C20）：先抢 rollback_at 闸门，抢到 1 行才真正回补库存、销量、积分、券与时段名额。
     * 闸门是独立于状态跃迁的第二道 CAS，因此重复执行安全：第二次进来命中 0 行直接返回。
     */
    private ReleaseResult releaseResources(ReleasePlan plan, LocalDateTime now) {
        if (orderRepository.markRollbackGate(plan.orderId(), now) == 0) {
            log.info("订单 {} 资源已回退过，跳过重复回补", plan.orderNo());
            return new ReleaseResult(false, 0, 0, false);
        }
        plan.restock().forEach((productId, quantity) -> {
            productRepository.increaseStock(productId, quantity);
            productRepository.decreaseSalesCount(productId, quantity);
        });
        // 已发放的获客积分要收回，否则"下单-支付-取消"可以无限刷积分
        if (plan.pointsEarned() > 0) {
            userRepository.addPoints(plan.buyerId(), -plan.pointsEarned());
        }
        if (plan.pointsUsed() > 0) {
            userRepository.addPoints(plan.buyerId(), plan.pointsUsed());
        }
        if (plan.pointsEarned() > 0 || plan.pointsUsed() > 0) {
            orderRepository.updatePointsEarned(plan.orderId(), 0);
        }
        boolean couponReturned = couponService.releaseByOrder(plan.orderId());
        // 预约单关掉就把时段名额还回去；上面的闸门保证一单只释放一次
        slotQuotaService.release(ShippingPolicy.parseSlot(plan.deliverySlot()));
        return new ReleaseResult(true, plan.pointsUsed(), plan.pointsEarned(), couponReturned);
    }

    /** 在任何条件更新之前把懒加载关联摊平：关单语句会清空一级缓存，之后碰它就是游离对象 */
    private ReleasePlan flattenForRelease(Order order, OrderStatus current) {
        Map<UUID, Integer> restock = new LinkedHashMap<>();
        if (current.releasesStockOnLeave() && order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null) {
                    restock.merge(item.getProduct().getId(), item.getQuantity(), Integer::sum);
                }
            }
        }
        return new ReleasePlan(order.getId(), order.getOrderNo(), order.getPayAmount(),
                order.getPointsUsed() == null ? 0 : order.getPointsUsed(),
                order.getPointsEarned() == null ? 0 : order.getPointsEarned(),
                order.getUser().getId(), order.getDeliverySlot(), Map.copyOf(restock));
    }

    /** 状态跃迁失败时回读真实状态给出原因（C10），含糊的「刷新重试」会让用户反复点击 */
    private String statusChangedHint(UUID orderId, OrderStatus expected) {
        OrderStatus actual = orderRepository.findStatusById(orderId);
        if (actual == null || actual == expected) {
            return "订单状态已变更，请刷新后重试";
        }
        return "订单刚刚被更新为「" + actual.getLabel() + "」，本次操作未生效，请刷新后查看最新进度";
    }

    /** 关单前摊平的回退计划：全部是普通值，条件更新清空缓存后仍可安全读取 */
    private record ReleasePlan(UUID orderId, String orderNo, BigDecimal payAmount, int pointsUsed,
                               int pointsEarned, UUID buyerId, String deliverySlot,
                               Map<UUID, Integer> restock) {
    }

    /** 回退结果：released=false 表示闸门早已被占用，本次没有重复回退 */
    private record ReleaseResult(boolean released, int pointsReturned, int pointsRevoked, boolean couponReturned) {
    }

    @Override
    public int cancelPayTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(payTimeoutMinutes);
        List<UUID> timeoutIds = orderRepository.findPayTimeoutIds(OrderStatus.PENDING, deadline);
        int cancelled = 0;
        for (UUID id : timeoutIds) {
            try {
                // 逐笔现取：上一笔关单时的 clearAutomatically 已经清空过上下文，跨笔复用对象必然游离
                Order order = orderRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(404, "订单不存在"));
                closeOrder(order, OrderStatus.CANCELLED, "超时未支付，系统自动取消", "系统");
                cancelled++;
            } catch (BusinessException e) {
                log.debug("跳过订单 {}：{}", id, e.getMessage());
            }
        }
        if (cancelled > 0) {
            log.info("超时未付款订单自动取消 {} 笔", cancelled);
        }
        return cancelled;
    }

    @Override
    public int autoConfirmReceivedOrders() {
        int days = Math.max(autoReceiveDays, 1);
        LocalDateTime deadline = LocalDateTime.now().minusDays(days);
        // 批量取 ID 再逐单处理：与超时关单同风格，条件更新保证重复执行只会命中 0 行
        List<UUID> ids = orderRepository.findAutoReceiveIds(OrderStatus.SHIPPED, deadline,
                PageRequest.of(0, OrderFlowPolicy.AUTO_RECEIVE_BATCH));
        int confirmed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (UUID id : ids) {
            try {
                if (orderRepository.markReceived(id, OrderStatus.SHIPPED, OrderStatus.DELIVERED, now) == 0) {
                    continue;
                }
                // 条件更新清空了一级缓存，轨迹前要重新取受管引用
                Order order = orderRepository.findDetailById(id).orElse(null);
                if (order == null) {
                    continue;
                }
                trace(order, "delivered", "系统自动确认收货",
                        "发货满 " + days + " 天未确认，系统代为签收；花礼有问题仍可在详情页申请退款", "系统");
                confirmed++;
            } catch (BusinessException e) {
                log.debug("跳过自动签收订单 {}：{}", id, e.getMessage());
            }
        }
        if (confirmed > 0) {
            log.info("发货满 {} 天自动确认收货 {} 笔", days, confirmed);
        }
        return confirmed;
    }

    /* ---------- 退款申请与审核（C19/C20） ---------- */

    @Override
    public OrderView applyRefund(UUID orderId, UUID userId, OrderDtos.RefundRequest request) {
        Order order = requireOwned(orderId, userId);
        OrderStatus status = order.getStatus();
        if (!status.isRefundable()) {
            throw new BusinessException(409, status.refundDenial());
        }
        if (order.getRefundStatus() != null && order.getRefundStatus().isOpen()) {
            throw new BusinessException(409, "退款申请已在「" + order.getRefundStatus().getLabel()
                    + "」处理中，请勿重复提交");
        }
        String reason = OrderFlowPolicy.reasonText(OrderFlowPolicy.REFUND_REASONS,
                request == null ? null : request.reasonCode(),
                request == null ? null : request.reasonText(), null);
        if (reason == null) {
            throw new BusinessException(400, "请选择或填写退款原因，门店需要据此判定");
        }
        LocalDateTime now = LocalDateTime.now();
        // 金额一律取实付：页面与接口都不重新算，退款与收款永远对得上（口径来自建单时的 CheckoutPolicy）
        BigDecimal amount = order.getPayAmount();
        int updated = orderRepository.markRefundRequested(orderId, status, RefundStatus.PENDING,
                RefundStatus.REOPENABLE_LIST, amount, reason, now);
        if (updated == 0) {
            throw new BusinessException(409, statusChangedHint(orderId, status));
        }
        OrderRefund record = new OrderRefund();
        // 条件更新已清空缓存，关联只取受管代理，persist 时只写外键不初始化对象图
        record.setOrder(orderRepository.getReferenceById(orderId));
        record.setUserId(userId);
        record.setAmount(amount);
        record.setReason(reason);
        record.setDetail(trimToNull(request == null ? null : request.reasonText()));
        record.setStatus(RefundStatus.PENDING);
        orderRefundRepository.save(record);

        trace(order, "refund_apply", "提交退款申请", reason + " · 申请金额 ¥" + amount.toPlainString(), "用户");
        log.info("退款申请已提交 orderNo={} amount={} user={}", order.getOrderNo(), amount, userId);
        return view(orderRepository.findDetailById(orderId).orElseThrow(), reviewedItemIds(orderId), false, true,
                record);
    }

    @Override
    public OrderView revokeRefund(UUID orderId, UUID userId) {
        Order order = requireOwned(orderId, userId);
        RefundStatus mirror = order.getRefundStatus();
        if (mirror == null || !mirror.isRevokable()) {
            throw new BusinessException(409, RefundStatus.denial(mirror, "撤销"));
        }
        LocalDateTime now = LocalDateTime.now();
        List<OrderRefund> open = orderRefundRepository.findOpenOfOrder(orderId, RefundStatus.OPEN_LIST);
        for (OrderRefund record : open) {
            // 先对申请单做 CAS，再同步订单镜像列，两步都以影响行数裁决
            int rows = orderRefundRepository.transit(record.getId(), record.getStatus(), RefundStatus.REVOKED,
                    "用户", null, null, null, now);
            if (rows == 0) {
                throw new BusinessException(409, "退款申请刚被门店处理，请刷新后查看最新进度");
            }
        }
        if (orderRepository.transitRefundStatus(orderId, mirror, RefundStatus.REVOKED) == 0) {
            throw new BusinessException(409, "退款状态已变更，请刷新后查看最新进度");
        }
        trace(order, "refund_revoke", "撤销退款申请", "订单继续按原计划履约", "用户");
        return view(orderRepository.findDetailById(orderId).orElseThrow(), reviewedItemIds(orderId), false, true);
    }

    @Override
    public OrderView reviewRefund(UUID refundId, String action, String note, String operator) {
        OrderRefund record = orderRefundRepository.findById(refundId)
                .orElseThrow(() -> BusinessException.notFound("退款申请不存在"));
        // 之后的每条条件更新都会清空一级缓存，record 立刻变游离；订单 id 必须在读侧先取出来
        UUID orderId = record.getOrder().getId();
        RefundStatus from = record.getStatus();
        String refundReason = record.getReason();
        String trimmed = trimToNull(note);
        LocalDateTime now = LocalDateTime.now();
        String reviewer = operator == null || operator.isBlank() ? "门店" : operator;
        switch (action == null ? "" : action.toLowerCase()) {
            case "accept" -> {
                if (!from.canAccept()) {
                    throw new BusinessException(409, RefundStatus.denial(from, "受理"));
                }
                requireRows(orderRefundRepository.transit(refundId, from, RefundStatus.REVIEWING,
                        reviewer, trimmed, now, null, now), from, "受理");
                syncOrderRefundMirror(orderId, from, RefundStatus.REVIEWING);
                Order accepted = orderRepository.findDetailById(orderId)
                        .orElseThrow(() -> BusinessException.notFound("订单不存在"));
                trace(accepted, "refund_review", "门店已受理退款", "退款金额 ¥" + recordAmount(record)
                        + "，进入审核流程" + (trimmed == null ? "" : " · " + trimmed), reviewer);
            }
            case "reject" -> {
                if (!from.canReject()) {
                    throw new BusinessException(409, RefundStatus.denial(from, "驳回"));
                }
                if (trimmed == null) {
                    throw new BusinessException(400, "驳回退款申请需要写明原因，用户才能知道下一步做什么");
                }
                requireRows(orderRefundRepository.transit(refundId, from, RefundStatus.REJECTED,
                        reviewer, trimmed, null, null, now), from, "驳回");
                syncOrderRefundMirror(orderId, from, RefundStatus.REJECTED);
                Order rejected = orderRepository.findDetailById(orderId)
                        .orElseThrow(() -> BusinessException.notFound("订单不存在"));
                trace(rejected, "refund_review", "退款申请被驳回", trimmed + " · 订单继续按原计划履约", reviewer);
            }
            case "revoke" -> {
                if (!from.isRevokable()) {
                    throw new BusinessException(409, RefundStatus.denial(from, "撤销"));
                }
                requireRows(orderRefundRepository.transit(refundId, from, RefundStatus.REVOKED,
                        reviewer, trimmed, null, null, now), from, "撤销");
                syncOrderRefundMirror(orderId, from, RefundStatus.REVOKED);
            }
            case "settle" -> {
                if (!from.canSettle()) {
                    throw new BusinessException(409, RefundStatus.denial(from, "确认退款"));
                }
                Order order = orderRepository.findDetailById(orderId)
                        .orElseThrow(() -> BusinessException.notFound("订单不存在"));
                // 关单里包含状态 CAS、资源回退闸门与在途申请单收尾，退款到账只走这一条路
                return closeOrder(order, OrderStatus.REFUNDED,
                        refundReason == null ? "门店确认退款" : refundReason, reviewer);
            }
            default -> throw new BusinessException(400, "不支持的退款审核动作：" + action);
        }
        return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), true, true);
    }

    private static String recordAmount(OrderRefund record) {
        return record.getAmount() == null ? "-" : record.getAmount().toPlainString();
    }

    /** 申请单跃迁的行数裁决：0 行说明别人刚改过状态，直接把当前结论回给用户（C10） */
    private void requireRows(int rows, RefundStatus from, String action) {
        if (rows == 0) {
            throw new BusinessException(409, "退款申请已是「" + from.getLabel()
                    + "」之后的状态，本次" + action + "未生效，请刷新后查看");
        }
    }

    /** 订单镜像列跟着申请单走，列表页与详情徽标才不会出现「申请已撤销但还显示审核中」 */
    private void syncOrderRefundMirror(UUID orderId, RefundStatus from, RefundStatus to) {
        if (orderRepository.transitRefundStatus(orderId, from, to) == 0) {
            throw new BusinessException(409, "订单退款状态已变更，请刷新后重试");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView detailForUser(UUID orderId, UUID userId) {
        Order order = requireOwned(orderId, userId);
        // 详情页需要退款单原文（受理时间、审核意见），列表页不查这张表
        List<OrderRefund> open = order.getRefundStatus() == null
                ? List.of()
                : orderRefundRepository.findOpenOfOrder(orderId, RefundStatus.OPEN_LIST);
        OrderRefund latest = order.getRefundStatus() == null ? null
                : (open.isEmpty() ? lastOf(orderRefundRepository.findByOrderIdHistory(orderId)) : open.get(0));
        return view(order, reviewedItemIds(orderId), false, true, latest);
    }

    private static OrderRefund lastOf(List<OrderRefund> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView detailForAdmin(UUID orderId) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        return view(order, reviewedItemIds(orderId), true, true);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderView> listForUser(UUID userId, OrderStatus status) {
        List<Order> orders = orderRepository.findDetailByUserId(userId);
        Set<UUID> reviewed = reviewedItemIdsOfOrders(orders);
        return orders.stream()
                .filter(o -> status == null || o.getStatus() == status)
                .map(o -> view(o, reviewed, false))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderView> pageForUser(UUID userId, OrderDtos.UserOrderQuery query) {
        String rangeError = OrderFlowPolicy.rangeError(query.from(), query.to());
        if (rangeError != null) {
            throw new BusinessException(400, rangeError);
        }
        List<OrderStatus> statuses = OrderFlowPolicy.statusGroup(query.status());
        Pageable pageable = Pages.of(query.page(), query.size(), OrderFlowPolicy.sortOf(query.sort()));
        Page<Order> page = orderRepository.searchForUser(userId, statuses, query.from(),
                OrderFlowPolicy.endOfDay(query.to()), OrderFlowPolicy.keyword(query.keyword()), pageable);
        List<UUID> ids = page.getContent().stream().map(Order::getId).toList();
        if (ids.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, page.getTotalElements());
        }
        // 分页查出的实体没有带明细，按 id 批量补一次 fetch join，避免逐单查询打成 N+1
        Map<UUID, Order> loaded = new LinkedHashMap<>();
        for (Order detail : orderRepository.findDetailByIds(ids)) {
            loaded.put(detail.getId(), detail);
        }
        Set<UUID> reviewed = reviewedItemIdsOfOrders(new ArrayList<>(loaded.values()));
        // 排序条件由数据库裁决，这里按分页返回的 id 顺序还原，不能退回 created_at 默认排序
        List<OrderView> rows = ids.stream()
                .map(loaded::get)
                .filter(Objects::nonNull)
                .map(o -> view(o, reviewed, false))
                .toList();
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderView> listForAdmin(OrderStatus status, String keyword, Pageable pageable) {
        Page<Order> page = orderRepository.search(status, keyword == null ? "" : keyword.trim(), pageable);
        Set<UUID> reviewed = reviewedItemIdsOfOrders(page.getContent());
        return page.map(order -> view(order, reviewed, true));
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUserId(UUID userId) {
        return orderRepository.countByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countAll() {
        return orderRepository.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(OrderStatus status) {
        return orderRepository.countByStatus(status);
    }

    private Order requireOwned(UUID orderId, UUID userId) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        if (order.getUser() == null || !order.getUser().getId().equals(userId)) {
            throw BusinessException.forbidden("无权访问该订单");
        }
        return order;
    }

    /**
     * 收货信息解析（B18）：错误带上字段名，页面才能把焦点落到没填的那一格
     */
    private Receiver resolveReceiver(UUID userId, OrderDtos.CreateRequest request) {
        if (request.addressId() != null && !request.addressId().isBlank()) {
            UUID addressId;
            try {
                addressId = UUID.fromString(request.addressId());
            } catch (IllegalArgumentException e) {
                throw new CheckoutPolicy.CheckoutValidation("addressId", "收货地址不正确，请重新选择", List.of());
            }
            Address address = addressRepository.findById(addressId)
                    .orElseThrow(() -> BusinessException.notFound("收货地址不存在"));
            if (!address.getUser().getId().equals(userId)) {
                throw BusinessException.forbidden("无权使用该收货地址");
            }
            return new Receiver(address.getReceiverName(), address.getReceiverPhone(), address.fullAddress());
        }
        String name = trimToNull(request.receiverName());
        String phone = trimToNull(request.receiverPhone());
        String address = trimToNull(request.receiverAddress());
        if (name == null) {
            throw new CheckoutPolicy.CheckoutValidation("receiverName", "请填写收货人姓名", List.of());
        }
        if (phone == null) {
            throw new CheckoutPolicy.CheckoutValidation("receiverPhone", "请填写联系电话", List.of());
        }
        if (address == null) {
            throw new CheckoutPolicy.CheckoutValidation("receiverAddress", "请填写详细收货地址", List.of());
        }
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new CheckoutPolicy.CheckoutValidation("receiverPhone", "手机号格式不正确", List.of());
        }
        return new Receiver(name, phone, address);
    }

    private void clearCart(UUID userId) {
        cartRepository.findByUserId(userId).ifPresent(cart -> {
            cart.getItems().clear();
            cart.calculateTotal();
            cartRepository.save(cart);
        });
    }

    private boolean isPayTimeout(Order order, LocalDateTime now) {
        return order.getCreatedAt() != null
                && order.getCreatedAt().plusMinutes(payTimeoutMinutes).isBefore(now);
    }

    /** 后台流转的 CAS 裁决：0 行时回读真实状态给原因（C10），不说含糊的"刷新重试" */
    private void requireTransit(int updated, UUID orderId, OrderStatus expected) {
        if (updated == 0) {
            throw new BusinessException(409, statusChangedHint(orderId, expected));
        }
    }

    private Set<UUID> reviewedItemIds(UUID orderId) {
        return reviewRepository.findByOrderId(orderId).stream()
                .map(Review::getOrderItem)
                .filter(Objects::nonNull)
                .map(OrderItem::getId)
                .collect(Collectors.toSet());
    }

    private Set<UUID> reviewedItemIdsOfOrders(List<Order> orders) {
        if (orders.isEmpty()) {
            return Set.of();
        }
        Set<UUID> orderIds = orders.stream().map(Order::getId).collect(Collectors.toSet());
        return new HashSet<>(reviewRepository.findReviewedItemIds(orderIds));
    }

    private OrderView view(Order order, Set<UUID> reviewed, boolean withUser) {
        return OrderView.of(order, reviewed, withUser, List.of(), payTimeoutMinutes, null);
    }

    /**
     * 详情页视图：带轨迹时间轴，列表页仍走无轨迹版本避免逐单查询轨迹
     */
    private OrderView view(Order order, Set<UUID> reviewed, boolean withUser, boolean withTraces) {
        return view(order, reviewed, withUser, withTraces, null);
    }

    /** 带在途退款单原文的详情视图（C19）：审核意见与受理时间只有详情页需要 */
    private OrderView view(Order order, Set<UUID> reviewed, boolean withUser, boolean withTraces,
                           OrderRefund refundRecord) {
        if (!withTraces) {
            return OrderView.of(order, reviewed, withUser, List.of(), payTimeoutMinutes, refundRecord);
        }
        return OrderView.of(order, reviewed, withUser, orderTraceRepository.findByOrderId(order.getId()),
                payTimeoutMinutes, refundRecord);
    }

    /**
     * 轨迹写入统一出口：节点是履约留痕，写失败不该阻断主流程之外的动作，因此只降级为日志
     */
    private void trace(Order order, String code, String title, String description, String operator) {
        if (order == null || order.getId() == null) {
            return;
        }
        try {
            OrderTrace node = new OrderTrace();
            node.setOrder(order);
            node.setCode(code);
            node.setTitle(cut(title, 60));
            node.setDescription(cut(description, 255));
            node.setOperator(cut(operator == null || operator.isBlank() ? "系统" : operator, 60));
            orderTraceRepository.save(node);
        } catch (RuntimeException e) {
            log.warn("写入订单轨迹失败 orderNo={} code={} : {}", order.getOrderNo(), code, e.getMessage());
        }
    }

    private String deliveryPlanText(Order order) {
        ShippingPolicy.Method method = ShippingPolicy.find(order.getDeliveryMethod()).orElse(null);
        if (method == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(method.name());
        if (order.getExpectedArriveAt() != null) {
            boolean pickup = ShippingPolicy.SELF_PICKUP.code().equals(method.code());
            text.append(" · 预计 ").append(order.getExpectedArriveAt().format(TRACE_TIME))
                    .append(pickup ? " 可取货" : " 送达");
        }
        BigDecimal freight = order.getFreight();
        text.append(freight == null || freight.signum() <= 0 ? " · 免运费" : " · 运费 ¥" + freight.toPlainString());
        if (order.getDeliveryWeight() != null) {
            text.append("（按 ").append(order.getDeliveryWeight().toPlainString()).append("kg 计重）");
        }
        String preference = DeliveryPolicy.summary(order.getDeliveryPreference(), order.getContactPreference());
        if (preference != null) {
            text.append(" · 送达偏好 ").append(preference);
        }
        return text.toString();
    }

    private String payMethodText(String payMethod) {
        String code = blankToNull(payMethod);
        if (code == null) {
            return "支付成功";
        }
        return switch (code) {
            case "wechat" -> "微信支付成功";
            case "alipay" -> "支付宝支付成功";
            case "balance" -> "余额支付成功";
            case "offline" -> "线下支付已确认";
            default -> "支付成功";
        };
    }

    private String traceCodeOf(OrderStatus target) {
        return switch (target) {
            case PROCESSING -> "processing";
            case SHIPPED -> "shipped";
            case DELIVERED -> "delivered";
            case COMPLETED -> "completed";
            case PAID -> "paid";
            default -> "status";
        };
    }

    private String traceTitleOf(OrderStatus target) {
        return switch (target) {
            case PROCESSING -> "花艺师备花中";
            case SHIPPED -> "已出库发货";
            case DELIVERED -> "花礼已送达";
            case COMPLETED -> "交易完成";
            case PAID -> "支付完成";
            default -> "状态更新";
        };
    }

    private String traceDetailOf(OrderStatus target, Order order) {
        return switch (target) {
            case PROCESSING -> "已按订单挑选花材，正在修剪包扎"
                    + (order.getCardStyle() == null ? "" : "，贺卡同步书写");
            case DELIVERED -> "收花人「" + order.getReceiverName() + "」已签收";
            case COMPLETED -> "感谢选择花语轩，欢迎再次光临";
            default -> "门店已将订单状态更新为「" + target.getLabel() + "」";
        };
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String label(Order order) {
        return order.getStatus() == null ? "未知" : order.getStatus().getLabel();
    }

    /**
     * 时间戳 + 随机后缀，唯一约束兜底碰撞
     */
    private String generateOrderNo() {
        return "FY" + LocalDateTime.now().format(ORDER_NO_FORMAT)
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String blankToNull(String value) {
        return trimToNull(value);
    }

    private record Receiver(String name, String phone, String address) {
    }
}
