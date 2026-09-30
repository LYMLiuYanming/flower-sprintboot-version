package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.Order;

import java.util.UUID;

/**
 * 下单幂等：结算页领一张一次性凭证，提交时凭证只允许换出一张订单。
 *
 * <p>不依赖 Redis——抢占与回吐都落在同一条 idempotency_token 行上，由数据库行锁串行化。
 */
public interface IdempotencyService {

    /** 签发新凭证，顺带清掉过期行 */
    String issue(UUID userId);

    /**
     * 抢占凭证。校验失败/已被抢占直接抛业务异常；重放（凭证已成单）时回吐首次那张订单。
     *
     * @return 空表示可以继续建单；非空表示这是一次重放
     */
    Order redeem(UUID userId, String token);

    /** 建单成功后把订单挂回凭证，之后同一凭证的提交都会回吐这张单 */
    void attach(UUID orderId, String token);
}
