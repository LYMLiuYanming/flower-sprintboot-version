package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.TicketAgent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** 客服账号（U26） */
public interface TicketAgentRepository extends JpaRepository<TicketAgent, UUID> {

    List<TicketAgent> findAllByOrderBySupervisorDescDisplayNameAsc();

    @Query("SELECT a FROM TicketAgent a WHERE a.enabled = true ORDER BY a.supervisor DESC, a.displayName ASC")
    List<TicketAgent> findEnabled();

    /** 可选处理人：后台派单下拉只列启用中的账号 */
    @Query("SELECT a FROM TicketAgent a WHERE a.enabled = true")
    List<TicketAgent> findAssignable();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE TicketAgent a SET a.supervisor = :supervisor, a.enabled = :enabled, "
            + "a.displayName = :displayName, a.updatedAt = CURRENT_TIMESTAMP WHERE a.userId = :userId")
    int updateSettings(@Param("userId") UUID userId,
                       @Param("displayName") String displayName,
                       @Param("supervisor") boolean supervisor,
                       @Param("enabled") boolean enabled);

    long countBySupervisorTrue();
}
