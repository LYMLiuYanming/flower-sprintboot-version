package org.liuym.flowerv1springboot.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderItem;
import org.liuym.flowerv1springboot.model.OrderRefund;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.RefundStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.IdempotencyService;
import org.liuym.flowerv1springboot.service.SlotQuotaService;
import org.liuym.flowerv1springboot.service.impl.OrderServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 超时关单与资源回退的回归用例（C09/C20）。
 *
 * <p>这里把仓储层做成「带状态条件的 UPDATE」的语义桩：影响行数只在实际状态匹配时为 1，
 * 因此能真实复现并发重复执行——关单必须释放库存、销量、券与预约运力，且第二次执行一律不重复释放。
 */
class OrderCloseRollbackTest {

    private static final int TIMEOUT_MINUTES = 30;

    private OrderRepository orderRepository;
    private ProductRepository productRepository;
    private UserRepository userRepository;
    private CouponService couponService;
    private SlotQuotaService slotQuotaService;
    private OrderTraceRepository orderTraceRepository;
    private OrderRefundRepository orderRefundRepository;
    private OrderServiceImpl service;

    private Order order;
    private UUID productId;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        productRepository = mock(ProductRepository.class);
        userRepository = mock(UserRepository.class);
        couponService = mock(CouponService.class);
        slotQuotaService = mock(SlotQuotaService.class);
        orderTraceRepository = mock(OrderTraceRepository.class);
        orderRefundRepository = mock(OrderRefundRepository.class);

        service = new OrderServiceImpl(orderRepository, mock(OrderItemRepository.class), orderTraceRepository,
                productRepository, userRepository, mock(AddressRepository.class), mock(ReviewRepository.class),
                mock(CartRepository.class), mock(CheckoutQueryRepository.class), orderRefundRepository,
                couponService, slotQuotaService, mock(IdempotencyService.class));
        ReflectionTestUtils.setField(service, "payTimeoutMinutes", TIMEOUT_MINUTES);
        ReflectionTestUtils.setField(service, "autoReceiveDays", 15);

        order = pendingTimeoutOrder();
        productId = order.getItems().get(0).getProduct().getId();

        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findDetailById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.findStatusById(order.getId())).thenAnswer(i -> order.getStatus());
        when(couponService.releaseByOrder(order.getId())).thenReturn(true);

        // findPayTimeoutIds：只捞仍处于「待付款」且已过点的单，模拟数据库侧的真实筛选
        when(orderRepository.findPayTimeoutIds(eq(OrderStatus.PENDING), any())).thenAnswer(inv -> {
            LocalDateTime deadline = inv.getArgument(1);
            return order.getStatus() == OrderStatus.PENDING && order.getCreatedAt().isBefore(deadline)
                    ? List.of(order.getId()) : List.of();
        });

        // markClosed：CAS，只有当前状态等于期望值才写入并返回 1
        when(orderRepository.markClosed(eq(order.getId()), any(OrderStatus.class), any(OrderStatus.class),
                anyString(), any(LocalDateTime.class))).thenAnswer(inv -> {
            OrderStatus expected = inv.getArgument(1);
            if (order.getStatus() != expected) {
                return 0;
            }
            order.setStatus(inv.getArgument(2));
            order.setCancelReason(inv.getArgument(3));
            order.setFinishTime(inv.getArgument(4));
            return 1;
        });

        // markRefunded：退款到账的 CAS，同时把退款镜像列写成已退款
        when(orderRepository.markRefunded(eq(order.getId()), any(OrderStatus.class), any(OrderStatus.class),
                any(RefundStatus.class), any(BigDecimal.class), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    OrderStatus expected = inv.getArgument(1);
                    if (order.getStatus() != expected) {
                        return 0;
                    }
                    order.setStatus(inv.getArgument(2));
                    order.setRefundStatus(inv.getArgument(3));
                    order.setRefundedAt(inv.getArgument(6));
                    return 1;
                });

        // markRollbackGate：把 rollback_at 从 null 写成当前时间，第二次必定 0 行
        when(orderRepository.markRollbackGate(eq(order.getId()), any(LocalDateTime.class))).thenAnswer(inv -> {
            if (order.getRollbackAt() != null) {
                return 0;
            }
            order.setRollbackAt(inv.getArgument(1));
            return 1;
        });
    }

    @Test
    void payTimeoutCloseReleasesStockSalesCouponAndSlotOnce() {
        int closed = service.cancelPayTimeoutOrders();

        assertEquals(1, closed);
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertTrue(order.getRollbackAt() != null);
        // 库存与销量各回补一次，数量取订单一行 2 枝
        verify(productRepository, times(1)).increaseStock(productId, 2);
        verify(productRepository, times(1)).decreaseSalesCount(productId, 2);
        verify(couponService, times(1)).releaseByOrder(order.getId());
        verify(slotQuotaService, times(1)).release(ShippingPolicy.parseSlot(order.getDeliverySlot()));
        // 抵扣出去的积分返还、本单发放的积分收回
        verify(userRepository, times(1)).addPoints(order.getUser().getId(), 200);
        verify(userRepository, times(1)).addPoints(order.getUser().getId(), -50);
    }

    @Test
    void secondSweepDoesNotTouchAnythingAgain() {
        assertEquals(1, service.cancelPayTimeoutOrders());
        // 第二趟扫描：状态已经不是待付款，一条都不会再处理
        assertEquals(0, service.cancelPayTimeoutOrders());
        verify(productRepository, times(1)).increaseStock(eq(productId), anyInt());
        verify(couponService, times(1)).releaseByOrder(any());
        verify(slotQuotaService, times(1)).release(any());
    }

    @Test
    void rollbackGateBlocksSecondReleaseEvenWhenStatusCasPassesAgain() {
        assertEquals(1, service.cancelPayTimeoutOrders());
        // 模拟另一条路径（后台退款/重复任务）带着旧实体再次进来：状态 CAS 仍可能命中，闸门必须拦住
        order.setStatus(OrderStatus.PENDING);
        when(orderRepository.findPayTimeoutIds(eq(OrderStatus.PENDING), any()))
                .thenReturn(List.of(order.getId()));

        assertEquals(1, service.cancelPayTimeoutOrders());

        verify(productRepository, times(1)).increaseStock(eq(productId), anyInt());
        verify(productRepository, times(1)).decreaseSalesCount(eq(productId), anyInt());
        verify(couponService, times(1)).releaseByOrder(any());
        verify(slotQuotaService, times(1)).release(any());
        verify(userRepository, times(2)).addPoints(eq(order.getUser().getId()), anyInt());
        verify(orderRepository, times(1)).updatePointsEarned(eq(order.getId()), eq(0));
    }

    @Test
    void refundSettleReleasesResourcesOnceAndReplayIsRejected() {
        order.setStatus(OrderStatus.PAID);
        order.setPayTime(LocalDateTime.now().minusDays(1));
        OrderRefund record = reviewingRefund();
        when(orderRefundRepository.findById(record.getId())).thenReturn(Optional.of(record));
        when(orderRefundRepository.findOpenOfOrder(eq(order.getId()), eq(RefundStatus.OPEN_LIST)))
                .thenAnswer(inv -> record.getStatus().isOpen() ? List.of(record) : List.of());
        when(orderRefundRepository.transit(eq(record.getId()), any(RefundStatus.class), any(RefundStatus.class),
                any(), any(), any(), any(), any())).thenAnswer(inv -> {
            RefundStatus expected = inv.getArgument(1);
            if (record.getStatus() != expected) {
                return 0;
            }
            record.setStatus(inv.getArgument(2));
            if (inv.getArgument(6) != null) {
                record.setSettledAt(inv.getArgument(6));
            }
            return 1;
        });
        when(orderRepository.transitRefundStatus(eq(order.getId()), any(RefundStatus.class),
                any(RefundStatus.class))).thenReturn(1);

        service.reviewRefund(record.getId(), "settle", null, "门店审核员");

        assertEquals(OrderStatus.REFUNDED, order.getStatus());
        assertEquals(RefundStatus.REFUNDED, record.getStatus());
        // 已付款单仍占着库存与销量，退款到账必须一并回补
        verify(productRepository, times(1)).increaseStock(productId, 2);
        verify(productRepository, times(1)).decreaseSalesCount(productId, 2);
        verify(couponService, times(1)).releaseByOrder(order.getId());
        verify(slotQuotaService, times(1)).release(ShippingPolicy.parseSlot(order.getDeliverySlot()));

        // 重复审核：状态机先拒（已经是已退款），资源不会第二次被回退
        BusinessException denial = assertThrows(BusinessException.class,
                () -> service.reviewRefund(record.getId(), "settle", null, "门店审核员"));
        assertTrue(denial.getMessage().contains("退款已到账"), denial.getMessage());
        verify(productRepository, times(1)).increaseStock(eq(productId), anyInt());
        verify(couponService, times(1)).releaseByOrder(any());
    }

    @Test
    void autoConfirmReceivesOnlyShippedOrdersAfterDays() {
        order.setStatus(OrderStatus.SHIPPED);
        order.setShipTime(LocalDateTime.now().minusDays(16));
        when(orderRepository.findAutoReceiveIds(eq(OrderStatus.SHIPPED), any(), any()))
                .thenReturn(List.of(order.getId()));
        when(orderRepository.markReceived(eq(order.getId()), eq(OrderStatus.SHIPPED),
                eq(OrderStatus.DELIVERED), any(LocalDateTime.class))).thenAnswer(inv -> {
            if (order.getStatus() != inv.getArgument(1)) {
                return 0;
            }
            order.setStatus(OrderStatus.DELIVERED);
            order.setDeliverTime(inv.getArgument(3));
            return 1;
        });

        assertEquals(1, service.autoConfirmReceivedOrders());
        assertEquals(OrderStatus.DELIVERED, order.getStatus());
        assertNotNull(order.getDeliverTime());
        // 自动签收不动库存也不退款：只推进状态并留痕
        verify(orderRepository, never()).markClosed(any(), any(), any(), any(), any());
        verify(productRepository, never()).increaseStock(any(), anyInt());

        when(orderRepository.findAutoReceiveIds(eq(OrderStatus.SHIPPED), any(), any())).thenReturn(List.of());
        assertEquals(0, service.autoConfirmReceivedOrders());
    }

    @Test
    void cancellingAWaitForPaymentOrderStoresTheChosenReason() {
        // C06/C07：取消原因必须落库，且能区分预设与用户自填
        order.setStatus(OrderStatus.PENDING);
        service.cancelByUser(order.getId(), order.getUser().getId(),
                new org.liuym.flowerv1springboot.dto.OrderDtos.CancelRequest("too_late", "婚礼改期"));
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertNotNull(order.getCancelReason());
        assertTrue(order.getCancelReason().contains("送达时间赶不上使用场景"), order.getCancelReason());
        assertTrue(order.getCancelReason().contains("婚礼改期"), order.getCancelReason());
    }

    @Test
    void cancelWithoutReasonIsRejected() {
        order.setStatus(OrderStatus.PENDING);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.cancelByUser(order.getId(), order.getUser().getId(),
                        new org.liuym.flowerv1springboot.dto.OrderDtos.CancelRequest("", "  ")));
        assertTrue(e.getMessage().contains("取消原因"), e.getMessage());
        assertEquals(OrderStatus.PENDING, order.getStatus());
    }

    /** 一张处于「审核中」的退款申请单，金额等于订单实付 */
    private OrderRefund reviewingRefund() {
        OrderRefund record = new OrderRefund();
        record.setId(UUID.randomUUID());
        record.setOrder(order);
        record.setUserId(order.getUser().getId());
        record.setAmount(order.getPayAmount());
        record.setReason("花材不新鲜或有损伤");
        record.setStatus(RefundStatus.REVIEWING);
        record.setAcceptedAt(LocalDateTime.now().minusHours(2));
        return record;
    }

    private static Order pendingTimeoutOrder() {
        UUID id = UUID.randomUUID();
        User buyer = new User();
        buyer.setId(UUID.randomUUID());
        buyer.setUsername("flower-tester");

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("晨露玫瑰");
        product.setPrice(new BigDecimal("99.00"));
        product.setStock(10);

        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setProduct(product);
        item.setProductName(product.getName());
        item.setPrice(product.getPrice());
        item.setQuantity(2);
        item.calculateSubtotal();

        Order o = new Order();
        o.setId(id);
        o.setOrderNo("FY202609301200000001");
        o.setUser(buyer);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalAmount(new BigDecimal("198.00"));
        o.setDiscountAmount(new BigDecimal("98.00"));
        o.setFreight(BigDecimal.ZERO);
        o.setPayAmount(new BigDecimal("100.00"));
        o.setPointsUsed(200);
        o.setPointsEarned(50);
        o.setCreatedAt(LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES + 5));
        o.setDeliverySlot("2026-10-06T15:00");
        o.setReceiverName("李雷");
        o.setReceiverPhone("13800001111");
        o.setReceiverAddress("上海市徐汇区田林路 1 号");
        o.addItem(item);
        return o;
    }
}
