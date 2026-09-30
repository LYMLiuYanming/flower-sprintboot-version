package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.IdempotencyToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyTokenRepository extends JpaRepository<IdempotencyToken, UUID> {

    Optional<IdempotencyToken> findByToken(String token);

    /** 抢占凭证：只有未被抢占过一次才生效，并发下重复提交只有一笔能拿到 */
    @Modifying
    @Query("UPDATE IdempotencyToken t SET t.claimedAt = :now WHERE t.token = :token AND t.claimedAt IS NULL")
    int claim(@Param("token") String token, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE IdempotencyToken t SET t.orderId = :orderId WHERE t.token = :token")
    int attachOrder(@Param("token") String token, @Param("orderId") UUID orderId);

    @Modifying
    @Query("DELETE FROM IdempotencyToken t WHERE t.createdAt < :before")
    int deleteExpired(@Param("before") LocalDateTime before);
}
