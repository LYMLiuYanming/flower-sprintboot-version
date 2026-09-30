package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.PlotExchange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PlotExchangeRepository extends JpaRepository<PlotExchange, UUID> {

    List<PlotExchange> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<PlotExchange> findFirstByPlotIdAndState(UUID plotId, String state);

    long countByUserIdAndState(UUID userId, String state);

    long countByUserIdAndType(UUID userId, String type);

    /** 待我确认的转赠：收礼方视角，按时间倒序 */
    List<PlotExchange> findByReceiverIdAndStateOrderByCreatedAtDesc(UUID receiverId, String state);

    /**
     * 转赠确认的状态流转：只有还停在 fromState 时才改得动，返回 0 表示已被处理过，
     * 上层的重复点击因此不会二次发券。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PlotExchange e SET e.state = :toState, e.updatedAt = :now "
            + "WHERE e.id = :id AND e.state = :fromState")
    int transition(@Param("id") UUID id, @Param("fromState") String fromState,
                   @Param("toState") String toState, @Param("now") LocalDateTime now);

    /** 券发完之后把快照补回去 */
    @Modifying
    @Query("UPDATE PlotExchange e SET e.userCouponId = :userCouponId, e.couponCode = :couponCode, "
            + "e.couponName = :couponName, e.amount = :amount, e.threshold = :threshold, "
            + "e.validDays = :validDays, e.state = :state, e.updatedAt = :now WHERE e.id = :id")
    int attachCoupon(@Param("id") UUID id, @Param("userCouponId") UUID userCouponId,
                     @Param("couponCode") String couponCode, @Param("couponName") String couponName,
                     @Param("amount") java.math.BigDecimal amount, @Param("threshold") java.math.BigDecimal threshold,
                     @Param("validDays") Integer validDays, @Param("state") String state,
                     @Param("now") LocalDateTime now);
}
