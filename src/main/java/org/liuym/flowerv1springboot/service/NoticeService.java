package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoticeService {

    Notice save(Notice notice);

    Optional<Notice> findById(UUID id);

    List<Notice> findActiveNotices();

    Page<Notice> findAll(Pageable pageable);

    boolean updateStatus(UUID id, String status);

    boolean deleteById(UUID id);

    long count();

    void incrementViewCount(UUID id);

    Page<Notice> searchByTitle(String title, Pageable pageable);

    Notice createByForm(ContentDtos.NoticeForm form);

    Notice updateByForm(UUID id, ContentDtos.NoticeForm form);
}
