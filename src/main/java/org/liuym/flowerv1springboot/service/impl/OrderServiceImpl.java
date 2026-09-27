package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.common.PointsPolicy;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.*;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.OrderService;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
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
    private static final BigDecimal MIN_PAY_AMOUNT = new BigDecimal("0.01");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AddressRepository addressRepository;
    private final ReviewRepository reviewRepository;
    private final CartRepository cartRepository;
    private final CouponService couponService;

    @Value("${order.pay-timeout-minutes:30}")
    private int payTimeoutMinutes;

    public OrderServiceImpl(OrderRepository orderRepository,
                            OrderItemRepository orderItemRepository,
                            ProductRepository productRepository,
                            UserRepository userRepository,
                            AddressRepository addressRepository,
                            ReviewRepository reviewRepository,
                            CartRepository cartRepository,
                            CouponService couponService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.addressRepository = addressRepository;
        this.reviewRepository = reviewRepository;
        this.cartRepository = cartRepository;
        this.couponService = couponService;
    }

    @Override
    public OrderView create(UUID userId, OrderDtos.CreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));

        Receiver receiver = resolveReceiver(userId, request);

        // 同商品多行合并，避免重复扣减同一行库存
        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (OrderDtos.ItemRequest item : request.items()) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
            UUID productId = entry.getKey();
            int quantity = entry.getValue();
            if (quantity <= 0) {
                throw new BusinessException("购买数量必须大于 0");
            }
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> BusinessException.notFound("商品不存在或已下架"));
            if (!Boolean.TRUE.equals(product.getIsActive())) {
                throw new BusinessException("商品「" + product.getName() + "」已下架");
            }
            // 条件更新扣库存：stock >= quantity 才生效，行锁保证不会超卖
            if (productRepository.reduceStock(productId, quantity) == 0) {
                throw new BusinessException("商品「" + product.getName() + "」库存不足，当前剩余 " + product.getStock() + " 件");
            }
            productRepository.increaseSalesCount(productId, quantity);

            OrderItem item = new OrderItem();
            item.setProduct(product);
            item.setProductName(product.getName());
            item.setProductImage(product.getMainImage());
            item.setPrice(product.getPrice());
            item.setQuantity(quantity);
            item.calculateSubtotal();
            orderItems.add(item);
            totalAmount = totalAmount.add(item.getSubtotal());
        }

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUser(user);
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        order.setFreight(BigDecimal.ZERO);
        order.setReceiverName(receiver.name());
        order.setReceiverPhone(receiver.phone());
        order.setReceiverAddress(receiver.address());
        order.setRemark(trimToNull(request.remark()));
        order.setPayMethod(blankToNull(request.payMethod()));

        BigDecimal discount = BigDecimal.ZERO;
        if (user.isVip()) {
            discount = discount.add(PointsPolicy.vipDiscount(order.getTotalAmount()));
        }
        // 优惠券先于积分：积分抵扣上限按券后金额算，避免券 + 积分把订单抵穿
        BigDecimal couponDiscount = BigDecimal.ZERO;
        if (request.userCouponId() != null) {
            couponDiscount = couponService.requireUsableForOrder(userId, request.userCouponId(), linesOf(orderItems));
            order.setCouponAmount(couponDiscount);
            order.setUserCouponId(request.userCouponId());
            discount = discount.add(couponDiscount);
        }
        BigDecimal pointsDeduction = applyPointsDeduction(user, order, request.pointsDeduction(), discount);
        discount = discount.add(pointsDeduction);

        order.setDiscountAmount(discount.setScale(2, RoundingMode.HALF_UP));
        order.calculatePayAmount();
        if (order.getPayAmount().compareTo(MIN_PAY_AMOUNT) < 0) {
            order.setPayAmount(MIN_PAY_AMOUNT);
        }
        order.setPointsUsed(PointsPolicy.pointsFor(pointsDeduction));

        Order saved = orderRepository.save(order);
        orderItems.forEach(item -> {
            item.setOrder(saved);
            orderItemRepository.save(item);
        });
        if (request.userCouponId() != null) {
            // 条件核销：并发下同一张券只能被一单用掉，失败则整单回滚（库存改动一并撤销）
            if (!couponService.consume(request.userCouponId(), userId, saved.getId())) {
                throw new BusinessException("该优惠券已被使用");
            }
        }

        if (Boolean.TRUE.equals(request.clearCart())) {
            clearCart(userId);
        }

        log.info("订单创建成功 orderNo={} user={} amount={} pointsUsed={}",
                saved.getOrderNo(), userId, saved.getPayAmount(), saved.getPointsUsed());
        return view(saved, Set.of(), false);
    }

    /**
     * 订单行 → 优惠券计价明细；分类为空的行不参与分类专享券
     */
    private List<CouponPolicy.Line> linesOf(List<OrderItem> items) {
        List<CouponPolicy.Line> lines = new ArrayList<>();
        for (OrderItem item : items) {
            Product product = item.getProduct();
            UUID categoryId = product == null || product.getCategory() == null
                    ? null : product.getCategory().getId();
            lines.add(new CouponPolicy.Line(categoryId, item.getSubtotal()));
        }
        return lines;
    }

    /**
     * 积分抵扣：先按上限收敛，再用条件更新扣减，余额被并发消耗时直接失败
     */
    private BigDecimal applyPointsDeduction(User user, Order order, BigDecimal requested, BigDecimal discountSoFar) {
        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amountAfterVip = order.getTotalAmount().subtract(discountSoFar);
        BigDecimal max = PointsPolicy.maxDeduction(amountAfterVip);
        BigDecimal available = PointsPolicy.yuanFor(user.getPoints() == null ? 0 : user.getPoints());
        BigDecimal deduction = requested.min(max).min(available).setScale(2, RoundingMode.DOWN);
        if (deduction.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        int points = PointsPolicy.pointsFor(deduction);
        if (userRepository.addPoints(user.getId(), -points) == 0) {
            throw new BusinessException("可用积分不足");
        }
        return BigDecimal.valueOf(points).divide(BigDecimal.valueOf(PointsPolicy.POINTS_PER_YUAN), 2, RoundingMode.DOWN);
    }

    @Override
    public OrderView pay(UUID orderId, UUID userId, String payMethod) {
        Order order = requireOwned(orderId, userId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BusinessException("订单当前状态为「" + label(order) + "」，无法支付");
        }
        LocalDateTime now = LocalDateTime.now();
        if (order.getPayTime() == null && isPayTimeout(order, now)) {
            throw new BusinessException(409, "订单已超过支付时限，请重新下单");
        }
        int updated = orderRepository.markPaid(orderId, OrderStatus.PENDING, OrderStatus.PAID, now);
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        int earned = PointsPolicy.earnedPoints(order.getPayAmount());
        orderRepository.updateAfterPaid(orderId, blankToNull(payMethod), earned);
        if (earned > 0) {
            userRepository.addPoints(userId, earned);
        }
        upgradeMemberLevelIfNeeded(userId);
        return view(orderRepository.findDetailById(orderId).orElseThrow(), reviewedItemIds(orderId), false);
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
    public OrderView cancelByUser(UUID orderId, UUID userId, String reason) {
        Order order = requireOwned(orderId, userId);
        return closeOrder(order, OrderStatus.CANCELLED, reason == null ? "用户取消" : reason);
    }

    @Override
    public OrderView confirmReceipt(UUID orderId, UUID userId) {
        Order order = requireOwned(orderId, userId);
        if (order.getStatus() != OrderStatus.SHIPPED) {
            throw new BusinessException("订单尚未发货，无需确认收货");
        }
        int updated = orderRepository.transitStatus(orderId, OrderStatus.SHIPPED, OrderStatus.DELIVERED);
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), false);
    }

    @Override
    public OrderView transitByAdmin(UUID orderId, OrderStatus target, OrderDtos.ShipRequest ship, String reason) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        OrderStatus current = order.getStatus();
        if (!current.canTransitTo(target)) {
            throw new BusinessException("不允许从「" + current.getLabel() + "」变更为「" + target.getLabel() + "」");
        }

        if (target == OrderStatus.SHIPPED) {
            if (ship == null || ship.expressCompany() == null || ship.expressNo() == null) {
                throw new BusinessException("发货需填写物流公司与单号");
            }
            int updated = orderRepository.markShipped(orderId, current, OrderStatus.SHIPPED,
                    ship.expressCompany().trim(), ship.expressNo().trim(), LocalDateTime.now());
            requireTransit(updated);
            return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), true);
        }

        if (target == OrderStatus.CANCELLED || target == OrderStatus.REFUNDED) {
            return closeOrder(order, target, reason == null ? "管理员操作" : reason);
        }

        if (target == OrderStatus.PAID) {
            int updated = orderRepository.markPaid(orderId, current, OrderStatus.PAID, LocalDateTime.now());
            requireTransit(updated);
            int earned = PointsPolicy.earnedPoints(order.getPayAmount());
            orderRepository.updatePointsEarned(orderId, earned);
            userRepository.addPoints(order.getUser().getId(), earned);
        } else {
            requireTransit(orderRepository.transitStatus(orderId, current, target));
        }
        return view(orderRepository.findDetailById(orderId).orElseThrow(), Set.of(), true);
    }

    /**
     * 关单（取消/退款）统一出口：条件更新保证只有一方成功，随后回补库存、销量与已用积分
     */
    private OrderView closeOrder(Order order, OrderStatus target, String reason) {
        OrderStatus current = order.getStatus();
        if (!current.canTransitTo(target)) {
            throw new BusinessException("不允许从「" + current.getLabel() + "」变更为「" + target.getLabel() + "」");
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = orderRepository.markClosed(order.getId(), current, target, trimToNull(reason), now);
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
        }
        if (current.releasesStockOnLeave()) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() == null) {
                    continue;
                }
                productRepository.increaseStock(item.getProduct().getId(), item.getQuantity());
                productRepository.decreaseSalesCount(item.getProduct().getId(), item.getQuantity());
            }
        }
        int used = order.getPointsUsed() == null ? 0 : order.getPointsUsed();
        int earned = order.getPointsEarned() == null ? 0 : order.getPointsEarned();
        UUID buyerId = order.getUser().getId();
        // 已发放的获客积分要收回，否则"下单-支付-取消"可以无限刷积分
        if (earned > 0) {
            userRepository.addPoints(buyerId, -earned);
        }
        if (used > 0) {
            userRepository.addPoints(buyerId, used);
        }
        if (earned > 0 || used > 0) {
            orderRepository.updatePointsEarned(order.getId(), 0);
        }
        couponService.releaseByOrder(order.getId());
        log.info("订单关闭 orderNo={} {} -> {} reason={}", order.getOrderNo(), current.getCode(), target.getCode(), reason);
        return view(orderRepository.findDetailById(order.getId()).orElseThrow(), Set.of(), false);
    }

    @Override
    public int cancelPayTimeoutOrders() {
        LocalDateTime deadline = LocalDateTime.now().minusMinutes(payTimeoutMinutes);
        List<Order> timeout = orderRepository.findPayTimeout(OrderStatus.PENDING, deadline);
        int cancelled = 0;
        for (Order order : timeout) {
            try {
                closeOrder(order, OrderStatus.CANCELLED, "超时未支付，系统自动取消");
                cancelled++;
            } catch (BusinessException e) {
                log.debug("跳过订单 {}：{}", order.getOrderNo(), e.getMessage());
            }
        }
        if (cancelled > 0) {
            log.info("超时未付款订单自动取消 {} 笔", cancelled);
        }
        return cancelled;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView detailForUser(UUID orderId, UUID userId) {
        Order order = requireOwned(orderId, userId);
        return view(order, reviewedItemIds(orderId), false);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderView detailForAdmin(UUID orderId) {
        Order order = orderRepository.findDetailById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        return view(order, reviewedItemIds(orderId), true);
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

    private Receiver resolveReceiver(UUID userId, OrderDtos.CreateRequest request) {
        if (request.addressId() != null && !request.addressId().isBlank()) {
            Address address = addressRepository.findById(UUID.fromString(request.addressId()))
                    .orElseThrow(() -> BusinessException.notFound("收货地址不存在"));
            if (!address.getUser().getId().equals(userId)) {
                throw BusinessException.forbidden("无权使用该收货地址");
            }
            return new Receiver(address.getReceiverName(), address.getReceiverPhone(), address.fullAddress());
        }
        String name = trimToNull(request.receiverName());
        String phone = trimToNull(request.receiverPhone());
        String address = trimToNull(request.receiverAddress());
        if (name == null || phone == null || address == null) {
            throw new BusinessException("请完整填写收货人、手机号与收货地址");
        }
        if (!phone.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException("手机号格式不正确");
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

    private void requireTransit(int updated) {
        if (updated == 0) {
            throw new BusinessException(409, "订单状态已变更，请刷新后重试");
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
        return OrderView.of(order, reviewed, withUser);
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
