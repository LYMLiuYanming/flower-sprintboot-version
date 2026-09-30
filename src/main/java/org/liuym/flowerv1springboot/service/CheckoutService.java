package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.vo.CheckoutViews;

import java.util.UUID;

/**
 * 结算服务：把「服务端计价」这件事收敛成一个入口，试算与下单共用同一套策略。
 */
public interface CheckoutService {

    /** 结算页试算（B08 起）：userId 为空表示游客，只算金额不给券与积分 */
    CheckoutViews.Summary summary(UUID userId, OrderDtos.CheckoutRequest request);

    /** 再次购买（B24）：把历史订单的花礼一次性加回购物车，库存不足的行按上限钳制 */
    CheckoutViews.ReorderReport reorder(UUID userId, UUID orderId);
}
