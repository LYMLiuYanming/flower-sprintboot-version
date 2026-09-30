package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeReadStat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NoticeService {

    Notice save(Notice notice);

    Optional<Notice> findById(UUID id);

    /** F11：启用 + 落在定时上下线时间窗内的公告，首页与公告位共用这一个口径 */
    List<Notice> findActiveNotices();

    Page<Notice> findAll(Pageable pageable);

    boolean updateStatus(UUID id, String status);

    boolean deleteById(UUID id);

    long count();

    /** 前台可见数量：后台列表页头部用它解释「为什么前台一条都没有」 */
    long countDisplayable();

    void incrementViewCount(UUID id);

    Page<Notice> searchByTitle(String title, Pageable pageable);

    /** 后台列表：状态与关键词组合筛选 */
    Page<Notice> searchAdmin(String status, String keyword, Pageable pageable);

    Notice createByForm(ContentDtos.NoticeForm form);

    Notice updateByForm(UUID id, ContentDtos.NoticeForm form);

    /** 前台详情：不可见（已下线/未上线/禁用）时返回 empty，由控制器给 404 语义 */
    Optional<Notice> findDisplayable(UUID id);

    /** F12 已读上报：登录用户去重计数 + 累计次数，返回该公告的最新统计 */
    NoticeReadStat markRead(UUID noticeId, UUID userId);

    /** F12 统计：浏览、已读人数、已读次数 */
    NoticeReadStat statOf(UUID noticeId);
}
