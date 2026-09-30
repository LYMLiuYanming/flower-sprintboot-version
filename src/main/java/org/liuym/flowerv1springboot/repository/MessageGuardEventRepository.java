package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.MessageGuardEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 跳转地址越权留痕（U05） */
public interface MessageGuardEventRepository extends JpaRepository<MessageGuardEvent, UUID> {

    @Query("SELECT g.reason, COUNT(g) FROM MessageGuardEvent g WHERE g.createdAt >= :from GROUP BY g.reason")
    List<Object[]> countByReasonSince(@Param("from") LocalDateTime from);

    long countByCreatedAtAfter(LocalDateTime from);

    Page<MessageGuardEvent> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
