package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {

    /** 有效会话（未撤销）按最近活动时间倒序，供设备列表展示 */
    @Query("SELECT s FROM UserSession s WHERE s.userId = :userId AND s.revoked = false "
            + "ORDER BY s.lastAccessTime DESC")
    List<UserSession> findActiveByUserId(@Param("userId") UUID userId);

    List<UserSession> findByUserIdAndToken(UUID userId, UUID token);

    /**
     * 退出其他设备：条件 UPDATE 把除当前 token 外的会话置 revoked。
     * 用 token <> :keepToken 兜住「当前会话绝不被撤销」这条红线，即便调用方误传也伤不到自己。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revoked = true, s.lastAccessTime = :now "
            + "WHERE s.userId = :userId AND s.revoked = false AND s.token <> :keepToken")
    int revokeOthers(@Param("userId") UUID userId, @Param("keepToken") UUID keepToken,
                     @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.revoked = true, s.lastAccessTime = :now "
            + "WHERE s.userId = :userId AND s.revoked = false")
    int revokeAll(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE UserSession s SET s.lastAccessTime = :now WHERE s.token = :token")
    int touch(@Param("token") UUID token, @Param("now") LocalDateTime now);

    long countByUserIdAndRevokedFalse(UUID userId);

    /** 清理长期未活动的过期记录，避免台账无限膨胀（保留最近 maxAge 天） */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM UserSession s WHERE s.lastAccessTime < :before")
    int deleteStale(@Param("before") LocalDateTime before);
}
