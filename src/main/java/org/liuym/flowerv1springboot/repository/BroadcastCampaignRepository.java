package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.BroadcastCampaign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

/** 群发台账（U23） */
public interface BroadcastCampaignRepository extends JpaRepository<BroadcastCampaign, UUID> {

    Page<BroadcastCampaign> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 逐人结果累加：一条 UPDATE 改一组计数，避免「读改写」在并发群发下丢计数。
     * 状态条件带上 running，保证已结束的活动不会再被写进账。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BroadcastCampaign c SET "
            + "c.sentCount = c.sentCount + :sent, "
            + "c.skippedPref = c.skippedPref + :skippedPref, "
            + "c.skippedDnd = c.skippedDnd + :skippedDnd, "
            + "c.skippedCap = c.skippedCap + :skippedCap, "
            + "c.skippedWeekend = c.skippedWeekend + :skippedWeekend, "
            + "c.failedCount = c.failedCount + :failed, "
            + "c.updatedAt = :now "
            + "WHERE c.id = :id AND c.status = 'running'")
    int accumulate(@Param("id") UUID id, @Param("sent") int sent, @Param("skippedPref") int skippedPref,
                   @Param("skippedDnd") int skippedDnd, @Param("skippedCap") int skippedCap,
                   @Param("skippedWeekend") int skippedWeekend,
                   @Param("failed") int failed, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BroadcastCampaign c SET c.status = :status, c.finishedAt = :now, "
            + "c.targetCount = :target, c.errorNote = :errorNote, c.updatedAt = :now "
            + "WHERE c.id = :id")
    int finish(@Param("id") UUID id, @Param("status") String status, @Param("target") int target,
               @Param("errorNote") String errorNote, @Param("now") LocalDateTime now);

    @Query("SELECT COUNT(c) FROM BroadcastCampaign c WHERE c.status = 'running'")
    long countRunning();
}
