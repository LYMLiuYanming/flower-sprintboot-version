package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.HtmlSanitizer;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.config.CacheConfig;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.repository.NoticeRepository;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeReadStat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /**
     * 首页公告位与公告条的数据源：定时上下线在查询侧按时间窗判定，所以这里必须带 now()。
     * 结果缓存 1 分钟（CacheConfig.NOTICES），到点与到时间的误差在一个刷新周期内。
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.NOTICES)
    public List<Notice> findActiveNotices() {
        return noticeRepository.findDisplayable(Notice.STATUS_ACTIVE, LocalDateTime.now());
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
            // notice_read 有 ON DELETE CASCADE，删除公告不会留下孤儿已读记录
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
    public long countDisplayable() {
        return noticeRepository.countDisplayable(Notice.STATUS_ACTIVE, LocalDateTime.now());
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
    public Page<Notice> searchAdmin(String status, String keyword, Pageable pageable) {
        return noticeRepository.searchAdmin(status == null ? "" : status,
                keyword == null ? "" : keyword.trim(), pageable);
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public Notice createByForm(ContentDtos.NoticeForm form) {
        Notice notice = new Notice();
        applyForm(notice, form);
        notice.setViewCount(0);
        notice.setReadUserCount(0);
        notice.setReadTimes(0);
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

    @Override
    @Transactional(readOnly = true)
    public Optional<Notice> findDisplayable(UUID id) {
        return noticeRepository.findById(id).filter(n -> n.displayableAt(LocalDateTime.now()));
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.NOTICES, allEntries = true)
    public NoticeReadStat markRead(UUID noticeId, UUID userId) {
        noticeRepository.findById(noticeId).orElseThrow(() -> BusinessException.notFound("公告不存在"));
        noticeRepository.upsertRead(UUID.randomUUID(), noticeId, userId);
        noticeRepository.refreshReadStats(noticeId);
        // 聚合列是 @Modifying 写进去的，一级缓存里还是旧实体，重查一次再回给前端
        Notice fresh = noticeRepository.findById(noticeId)
                .orElseThrow(() -> BusinessException.notFound("公告不存在"));
        return new NoticeReadStat(noticeId, fresh.getReadUserCount(), fresh.getReadTimes(), fresh.getViewCount());
    }

    @Override
    @Transactional(readOnly = true)
    public NoticeReadStat statOf(UUID noticeId) {
        Notice notice = noticeRepository.findById(noticeId)
                .orElseThrow(() -> BusinessException.notFound("公告不存在"));
        return new NoticeReadStat(noticeId, notice.getReadUserCount(), notice.getReadTimes(), notice.getViewCount());
    }

    /**
     * 公告正文为后台富文本，入库前按白名单清洗（{@link HtmlSanitizer}），只留排版标签，避免存储型 XSS。
     * F11：上线时间为空按「保存即上线」处理；下线时间早于上线时间直接拒绝，避免写出永远不可见的公告。
     */
    private void applyForm(Notice notice, ContentDtos.NoticeForm form) {
        String content = HtmlSanitizer.clean(form.content());
        if (HtmlSanitizer.text(content).isBlank()) {
            throw new BusinessException("公告内容不能为空");
        }
        if (form.publishAt() != null && form.offlineAt() != null && !form.offlineAt().isAfter(form.publishAt())) {
            throw new BusinessException("下线时间必须晚于上线时间");
        }
        notice.setTitle(form.title().trim());
        notice.setContent(content);
        notice.setNoticeType(form.noticeType() == null || form.noticeType().isBlank()
                ? Notice.TYPE_GENERAL : form.noticeType());
        notice.setIsTop(Boolean.TRUE.equals(form.isTop()));
        notice.setStatus(form.status() == null ? Notice.STATUS_ACTIVE : form.status());
        notice.setPublishAt(form.publishAt());
        notice.setOfflineAt(form.offlineAt());
        notice.setCoverImage(safeCover(form.coverImage()));
    }

    /** 地址不合法直接按业务错误返回，让后台看到「封面地址需为站内路径或 http(s) 链接」而不是 500 */
    private static String safeCover(String coverImage) {
        if (coverImage == null || coverImage.isBlank()) {
            return null;
        }
        try {
            return SafeUrl.requireSafe(coverImage, "公告封面");
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }
    }
}
