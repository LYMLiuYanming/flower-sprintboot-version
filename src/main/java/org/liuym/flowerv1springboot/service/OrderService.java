package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.vo.OrderView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * 订单域服务：所有金额/库存/积分均由服务端裁决，状态变更必须通过本服务，
 * 控制器不得再直接写 status 字段
 */
public interface OrderService {

    /** 下单：校验库存并原子扣减，失败整单回滚 */
    OrderView create(UUID userId, OrderDtos.CreateRequest request);

    /** 支付（沙箱/模拟回调）：待付款 → 已付款，发放积分 */
    OrderView pay(UUID orderId, UUID userId, String payMethod);

    /** 用户取消：仅待付款可取消，回补库存/销量并退还积分 */
    OrderView cancelByUser(UUID orderId, UUID userId, String reason);

    /** 用户确认收货：已发货 → 已签收 */
    OrderView confirmReceipt(UUID orderId, UUID userId);

    /** 后台状态流转（含发货、退款、完成等），非法跃迁直接拒绝 */
    OrderView transitByAdmin(UUID orderId, OrderStatus target, OrderDtos.ShipRequest ship, String reason);

    OrderView detailForUser(UUID orderId, UUID userId);

    OrderView detailForAdmin(UUID orderId);

    List<OrderView> listForUser(UUID userId, OrderStatus status);

    Page<OrderView> listForAdmin(OrderStatus status, String keyword, Pageable pageable);

    /** 定时任务入口：取消超时未付款订单，返回处理条数 */
    int cancelPayTimeoutOrders();

    long countByUserId(UUID userId);

    long countAll();

    long countByStatus(OrderStatus status);
}
