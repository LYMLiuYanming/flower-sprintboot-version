package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.Notice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoticeRepository extends JpaRepository<Notice, UUID> {

    List<Notice> findByStatusOrderByIsTopDescCreatedAtDesc(String status);

    Page<Notice> findByTitleContaining(String title, Pageable pageable);

    Optional<Notice> findByTitle(String title);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notice n SET n.status = :status, n.updatedAt = CURRENT_TIMESTAMP WHERE n.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notice n SET n.viewCount = n.viewCount + 1 WHERE n.id = :id")
    int incrementViewCount(@Param("id") UUID id);

    long countByStatus(String status);
}
