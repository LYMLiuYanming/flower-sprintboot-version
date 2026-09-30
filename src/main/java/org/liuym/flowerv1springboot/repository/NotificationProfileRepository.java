package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.NotificationProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/** 免打扰档案（U09/U23） */
public interface NotificationProfileRepository extends JpaRepository<NotificationProfile, UUID> {

    /**
     * 取或建：老用户在 V47 里已补齐，注册接口在 M/X 组手上，这里兜住「没档案就按默认开一条」，
     * 否则免打扰判定会因为查不到行而走到默认分支，用户在页面上改了却不生效。
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_profile (user_id, dnd_enabled, dnd_start, dnd_end, marketing_paused,
                                             created_at, updated_at)
            VALUES (CAST(:userId AS uuid), true, '22:00:00', '08:00:00', false, now(), now())
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int ensureExists(@Param("userId") UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE NotificationProfile p SET p.dndEnabled = :dndEnabled, p.dndStart = :start, p.dndEnd = :end, "
            + "p.marketingPaused = :marketingPaused, p.updatedAt = :now WHERE p.userId = :userId")
    int updateSettings(@Param("userId") UUID userId,
                       @Param("dndEnabled") boolean dndEnabled,
                       @Param("start") LocalTime start,
                       @Param("end") LocalTime end,
                       @Param("marketingPaused") boolean marketingPaused,
                       @Param("now") LocalDateTime now);
}
