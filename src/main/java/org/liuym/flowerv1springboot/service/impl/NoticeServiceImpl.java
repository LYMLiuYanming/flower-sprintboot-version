package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.HtmlSanitizer;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.repository.NoticeRepository;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NoticeServiceImpl implements NoticeService {

    @Autowired
    private NoticeRepository noticeRepository;

    @Override
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public Notice save(Notice notice) {
        return noticeRepository.save(notice);
    }

    @Override
    public Optional<Notice> findById(UUID id) {
        return noticeRepository.findById(id);
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.NOTICES)
    public List<Notice> findActiveNotices() {
        return noticeRepository.findByStatusOrderByIsTopDescCreatedAtDesc(Notice.STATUS_ACTIVE);
    }

    @Override
    public Page<Notice> findAll(Pageable pageable) {
        return noticeRepository.findAll(pageable);
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public boolean updateStatus(UUID id, String status) {
        int affectedRows = noticeRepository.updateStatus(id, status);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public boolean deleteById(UUID id) {
        if (noticeRepository.existsById(id)) {
            noticeRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public long count() {
        return noticeRepository.count();
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public void incrementViewCount(UUID id) {
        noticeRepository.incrementViewCount(id);
    }

    @Override
    public Page<Notice> searchByTitle(String title, Pageable pageable) {
        return noticeRepository.findByTitleContaining(title, pageable);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public Notice createByForm(ContentDtos.NoticeForm form) {
        Notice notice = new Notice();
        applyForm(notice, form);
        notice.setViewCount(0);
        return noticeRepository.save(notice);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public Notice updateByForm(UUID id, ContentDtos.NoticeForm form) {
        Notice notice = noticeRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("公告不存在"));
        applyForm(notice, form);
        return noticeRepository.save(notice);
    }

    /**
     * 公告正文为后台富文本，入库前按白名单清洗（{@link HtmlSanitizer}），只留排版标签，避免存储型 XSS
     */
    private void applyForm(Notice notice, ContentDtos.NoticeForm form) {
        String content = HtmlSanitizer.clean(form.content());
        if (HtmlSanitizer.text(content).isBlank()) {
            throw new BusinessException("公告内容不能为空");
        }
        notice.setTitle(form.title().trim());
        notice.setContent(content);
        notice.setNoticeType(form.noticeType() == null || form.noticeType().isBlank()
                ? Notice.TYPE_GENERAL : form.noticeType());
        notice.setIsTop(Boolean.TRUE.equals(form.isTop()));
        notice.setStatus(form.status() == null ? Notice.STATUS_ACTIVE : form.status());
    }
}
