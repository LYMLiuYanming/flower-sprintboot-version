package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.NotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 订阅偏好（U08） */
public interface NotificationPrefRepository extends JpaRepository<NotificationPref, UUID> {

    List<NotificationPref> findByUserId(UUID userId);

    Optional<NotificationPref> findByUserIdAndCategoryAndChannel(UUID userId, String category, String channel);

    /**
     * 类别是否允许站内触达：没有行按「默认开启」处理，新注册用户不必先跑一遍偏好页。
     * 用 nativeQuery 是因为 bool_and 不是 JPQL 内置函数，写进 JPQL 会在启动期解析失败。
     */
    @Query(value = """
            SELECT COALESCE(BOOL_AND(p.enabled), true) FROM notification_pref p
             WHERE p.user_id = CAST(:userId AS uuid) AND p.category = :category AND p.channel = 'inbox'
            """, nativeQuery = true)
    Boolean inboxEnabledFor(@Param("userId") UUID userId, @Param("category") String category);

    /**
     * 偏好落库走 upsert：偏好页一次提交 12 个开关，先查再插会让并发打开两个标签页时写出重复行。
     * 唯一键 (user_id, category, channel) 才是判定依据。
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_pref (id, user_id, category, channel, enabled, created_at, updated_at)
            VALUES (CAST(:id AS uuid), CAST(:userId AS uuid), :category, :channel, :enabled, now(), now())
            ON CONFLICT (user_id, category, channel)
            DO UPDATE SET enabled = :enabled, updated_at = now()
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("userId") UUID userId,
               @Param("category") String category, @Param("channel") String channel,
               @Param("enabled") boolean enabled);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NotificationPref p SET p.enabled = :enabled, p.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE p.userId = :userId AND p.channel = 'inbox' AND p.enabled IS DISTINCT FROM :enabled")
    int setAllInbox(@Param("userId") UUID userId, @Param("enabled") boolean enabled);

    long countByUserIdAndChannel(UUID userId, String channel);
}
