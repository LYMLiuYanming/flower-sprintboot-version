package org.liuym.flowerv1springboot.task;

import org.liuym.flowerv1springboot.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 超时未付款订单自动关闭。多实例部署时该任务会重复触发，
 * 但 OrderService 走的是带状态条件的 CAS 更新，重复执行只会命中 0 行，不会重复回补库存
 */
@Component
public class OrderScheduledTasks {

    private static final Logger log = LoggerFactory.getLogger(OrderScheduledTasks.class);

    private final OrderService orderService;

    public OrderScheduledTasks(OrderService orderService) {
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${order.auto-cancel-sweep-interval-ms:60000}",
            initialDelayString = "${order.auto-cancel-initial-delay-ms:30000}")
    public void cancelPayTimeoutOrders() {
        try {
            int closed = orderService.cancelPayTimeoutOrders();
            if (closed > 0) {
                log.info("超时未付款订单自动关闭 {} 笔", closed);
            }
        } catch (Exception e) {
            log.error("超时订单扫描任务执行失败", e);
        }
    }

    /**
     * 发货满 N 天（默认 15 天）自动确认收货：鲜花签收后才有评价与售后窗口，
     * 用户不点确认会让订单一直挂在「配送中」。同样靠 CAS 兜重复执行，扫到已变更的单直接跳过
     */
    @Scheduled(fixedDelayString = "${order.auto-receive-sweep-interval-ms:1800000}",
            initialDelayString = "${order.auto-receive-initial-delay-ms:120000}")
    public void autoConfirmReceivedOrders() {
        try {
            int confirmed = orderService.autoConfirmReceivedOrders();
            if (confirmed > 0) {
                log.info("发货超期订单自动确认收货 {} 笔", confirmed);
            }
        } catch (Exception e) {
            log.error("自动确认收货任务执行失败", e);
        }
    }
}
