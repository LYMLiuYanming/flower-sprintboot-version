package org.liuym.flowerv1springboot.service.impl;

import jakarta.persistence.EntityManager;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.IdempotencyPolicy;
import org.liuym.flowerv1springboot.model.IdempotencyToken;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.repository.IdempotencyTokenRepository;
import org.liuym.flowerv1springboot.repository.OrderRepository;
import org.liuym.flowerv1springboot.service.IdempotencyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 凭证一行的三次变化（签发 → 抢占 → 挂单）都发生在同一次请求的同一事务里，
 * 因此任何建单失败（含参数校验）都会连抢占一起回滚，凭证回到可用状态。
 */
@Service
@Transactional
public class IdempotencyServiceImpl implements IdempotencyService {

    /** 缺凭证/凭证失效统一按 409 返回，与「重复提交」区分于普通参数错误 */
    private static final int CONFLICT = 409;

    private final IdempotencyTokenRepository tokenRepository;
    private final OrderRepository orderRepository;
    private final EntityManager entityManager;

    public IdempotencyServiceImpl(IdempotencyTokenRepository tokenRepository, OrderRepository orderRepository,
                                 EntityManager entityManager) {
        this.tokenRepository = tokenRepository;
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    @Override
    public String issue(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.deleteExpired(now.minusMinutes(IdempotencyPolicy.TTL_MINUTES));
        IdempotencyToken token = new IdempotencyToken();
        token.setToken(IdempotencyPolicy.newToken());
        token.setUserId(userId);
        tokenRepository.save(token);
        return token.getToken();
    }

    @Override
    public Order redeem(UUID userId, String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(CONFLICT, "缺少下单凭证，请刷新结算页后重新提交");
        }
        LocalDateTime now = LocalDateTime.now();
        UUID claimedOrderId = orderIdOf(token);
        if (claimedOrderId == null) {
            IdempotencyToken row = tokenRepository.findByToken(token.trim())
                    .orElseThrow(() -> new BusinessException(CONFLICT, "下单凭证已失效，请刷新结算页后重新提交"));
            if (!row.getUserId().equals(userId)) {
                throw new BusinessException(CONFLICT, "下单凭证不属于当前账号，请刷新结算页后重新提交");
            }
            if (IdempotencyPolicy.expired(row.getCreatedAt(), now)) {
                throw new BusinessException(CONFLICT, "下单凭证已超过 " + IdempotencyPolicy.TTL_MINUTES + " 分钟有效期，请刷新结算页后重新提交");
            }
            // 条件更新本身就是互斥点：并发下输的那个在这里会等到对方提交后再判定
            if (tokenRepository.claim(token.trim(), now) == 0) {
                UUID raced = orderIdOf(token);
                if (raced == null) {
                    throw new BusinessException(CONFLICT, "订单正在处理中，请勿重复提交");
                }
                claimedOrderId = raced;
            }
        }
        return claimedOrderId == null ? null : requireOrder(claimedOrderId);
    }

    @Override
    public void attach(UUID orderId, String token) {
        if (orderId != null && token != null && !token.isBlank()) {
            tokenRepository.attachOrder(token.trim(), orderId);
        }
    }

    /**
     * 只取 order_id 标量，绕开一级缓存：抢占语句会污染上下文，重放判定必须读库。
     * 未挂单的凭证该列为 NULL，而 Optional 不接受 null，因此必须走列表取值。
     */
    private UUID orderIdOf(String token) {
        List<UUID> orderIds = entityManager
                .createQuery("select t.orderId from IdempotencyToken t where t.token = :token", UUID.class)
                .setParameter("token", token.trim())
                .getResultList();
        return orderIds.isEmpty() ? null : orderIds.get(0);
    }

    private Order requireOrder(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(CONFLICT, "原订单已不存在，请重新下单"));
    }
}
