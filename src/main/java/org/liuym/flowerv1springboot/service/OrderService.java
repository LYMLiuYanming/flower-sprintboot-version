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

    /** 用户取消（C06/C07）：仅待付款可取消，原因必填并落 order.cancel_reason */
    OrderView cancelByUser(UUID orderId, UUID userId, OrderDtos.CancelRequest request);

    /** 用户确认收货：已发货 → 已签收，同时落签收时间供 C26 使用 */
    OrderView confirmReceipt(UUID orderId, UUID userId);

    /** 提交退款申请（C19）：核定金额=实付，状态 pending，退款单与订单镜像同步落库 */
    OrderView applyRefund(UUID orderId, UUID userId, OrderDtos.RefundRequest request);

    /** 用户撤销在途退款申请（C19）：pending/reviewing 才可撤，撤销后订单继续原计划履约 */
    OrderView revokeRefund(UUID orderId, UUID userId);

    /**
     * 退款审核（C19/C20）：accept 受理、reject 驳回、settle 确认打款并回退资源、revoke 撤销。
     * 由后台售后页调用；跃迁全部走条件更新，重复审核只有第一条生效。
     *
     * @param operator 审核人署名，写进轨迹节点
     */
    OrderView reviewRefund(UUID refundId, String action, String note, String operator);

    /** 后台状态流转（含发货、退款、完成等），非法跃迁直接拒绝并给出原因；operator 用于轨迹节点署名 */
    OrderView transitByAdmin(UUID orderId, OrderStatus target, OrderDtos.ShipRequest ship, String reason,
                             String operator);

    /** 后台补记自定义轨迹节点：不改变订单状态，只留痕给收花人看 */
    OrderView addAdminTrace(UUID orderId, String title, String description, String operator);

    OrderView detailForUser(UUID orderId, UUID userId);

    OrderView detailForAdmin(UUID orderId);

    List<OrderView> listForUser(UUID userId, OrderStatus status);

    /** 我的订单（C01/C02/C03）：状态分组 + 下单时间范围 + 关键词 + 排序一次查完 */
    Page<OrderView> pageForUser(UUID userId, OrderDtos.UserOrderQuery query);

    Page<OrderView> listForAdmin(OrderStatus status, String keyword, Pageable pageable);

    /** 定时任务入口：取消超时未付款订单，返回处理条数 */
    int cancelPayTimeoutOrders();

    /** 定时任务入口（C18）：发货满 N 天自动确认收货，返回处理条数 */
    int autoConfirmReceivedOrders();

    long countByUserId(UUID userId);

    long countAll();

    long countByStatus(OrderStatus status);
}
