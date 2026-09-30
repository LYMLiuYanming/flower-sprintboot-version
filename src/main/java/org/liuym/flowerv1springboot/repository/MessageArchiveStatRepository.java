package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.MessageArchiveStat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

/** 清理归档计数（U10） */
public interface MessageArchiveStatRepository extends JpaRepository<MessageArchiveStat, UUID> {

    @Query("SELECT s.bucketMonth, SUM(s.messageCount), COUNT(DISTINCT s.userId) FROM MessageArchiveStat s "
            + "GROUP BY s.bucketMonth ORDER BY s.bucketMonth DESC")
    java.util.List<Object[]> monthlySummary();

    Page<MessageArchiveStat> findByArchivedAtAfter(LocalDateTime from, Pageable pageable);
}
