package org.liuym.flowerv1springboot.content;

import org.junit.jupiter.api.Test;
import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.vo.ContentViews.BannerView;
import org.liuym.flowerv1springboot.vo.ContentViews.NoticeView;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公告定时上下线（F11）、已读统计口径（F12）与轮播投放窗口/跳转语义（F13/F14）的判定测试。
 * 这些判定都是「查询侧按 now() 算」，不依赖定时任务，所以时间边界必须在纯逻辑里就能验。
 */
class ContentScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    @Test
    void 未到上线时间的公告不可见且状态是待上线() {
        Notice notice = notice(NOW.plusMinutes(1), null);
        assertFalse(notice.displayableAt(NOW));
        assertEquals(Notice.STATE_SCHEDULED, notice.displayState(NOW));
    }

    @Test
    void 过了下线时间的公告不可见且状态是已到期() {
        Notice notice = notice(NOW.minusDays(3), NOW.minusHours(1));
        assertFalse(notice.displayableAt(NOW));
        assertEquals(Notice.STATE_EXPIRED, notice.displayState(NOW));
    }

    @Test
    void 时间窗内且启用的公告可见() {
        Notice notice = notice(NOW.minusHours(1), NOW.plusDays(1));
        assertTrue(notice.displayableAt(NOW));
        assertEquals(Notice.STATE_LIVE, notice.displayState(NOW));
    }

    /** 两个时间都留空 = 立即上线 + 长期有效，这是历史数据的默认形态，不能被当成「已过期」 */
    @Test
    void 不填时间窗等价于长期投放() {
        Notice notice = notice(null, null);
        assertTrue(notice.displayableAt(NOW));
        assertEquals(Notice.STATE_LIVE, notice.displayState(NOW));
    }

    /** 手动停用优先于时间窗：运营点停用要立刻生效，不用等下线时间 */
    @Test
    void 停用状态压过时间窗() {
        Notice notice = notice(NOW.minusHours(1), null);
        notice.setStatus(Notice.STATUS_INACTIVE);
        assertFalse(notice.displayableAt(NOW));
        assertEquals(Notice.STATE_DISABLED, notice.displayState(NOW));
    }

    @Test
    void 边界时刻按下线时间判定为仍可见() {
        assertTrue(notice(NOW, NOW).displayableAt(NOW), "上下线同刻的极端配置不应让公告凭空消失");
        assertFalse(notice(NOW, NOW).displayableAt(NOW.plusSeconds(1)));
    }

    @Test
    void 公告视图带出已读两口径与摘要() {
        Notice notice = notice(null, null);
        notice.setContent("<p>配送范围调整，<strong>主城区</strong>当日达&nbsp;不受影响</p>");
        notice.setViewCount(20);
        notice.setReadUserCount(7);
        notice.setReadTimes(12);
        NoticeView view = NoticeView.from(notice);
        assertEquals(7, view.readUserCount(), "已读人数是登录用户去重");
        assertEquals(12, view.readTimes(), "已读次数含重复阅读");
        assertEquals(20, view.viewCount(), "浏览数含匿名访客，与已读是两个口径");
        assertFalse(view.summary().contains("<"), "摘要必须剥掉标签：" + view.summary());
        assertTrue(view.summary().length() <= 60, view.summary());
    }

    @Test
    void 轮播按投放窗口给出四种状态() {
        assertEquals(Banner.STATE_LIVE, banner(NOW.minusDays(1), NOW.plusDays(1)).displayState(NOW));
        assertEquals(Banner.STATE_SCHEDULED, banner(NOW.plusDays(1), null).displayState(NOW));
        assertEquals(Banner.STATE_EXPIRED, banner(null, NOW.minusDays(1)).displayState(NOW));
        Banner off = banner(null, null);
        off.setStatus(Banner.STATUS_INACTIVE);
        assertEquals(Banner.STATE_DISABLED, off.displayState(NOW));
    }

    /** F13：跳转类型决定 link_url 的形态，旧页面只读 link_url 也能跳对 */
    @Test
    void 轮播视图透出跳转类型并保留可用链接() {
        Banner banner = banner(null, null);
        banner.setLinkType(Banner.LINK_PRODUCT);
        banner.setLinkTarget("b0000000-0000-0000-0000-000000000001");
        banner.setLinkUrl("/product/b0000000-0000-0000-0000-000000000001");
        banner.setThumbUrl("/img/photos/cat-tulip.jpg");
        BannerView view = BannerView.from(banner);
        assertEquals(Banner.LINK_PRODUCT, view.linkType());
        assertEquals(banner.getLinkUrl(), view.linkUrl());
        assertEquals("/img/photos/cat-tulip.jpg", view.thumbUrl());
        assertEquals("投放中", view.stateLabel());
    }

    private static Notice notice(LocalDateTime publishAt, LocalDateTime offlineAt) {
        Notice notice = new Notice();
        notice.setId(UUID.randomUUID());
        notice.setTitle("配送调整通知");
        notice.setContent("正文");
        notice.setStatus(Notice.STATUS_ACTIVE);
        notice.setIsTop(false);
        notice.setPublishAt(publishAt);
        notice.setOfflineAt(offlineAt);
        notice.setCreatedAt(NOW.minusDays(5));
        return notice;
    }

    private static Banner banner(LocalDateTime start, LocalDateTime end) {
        Banner banner = new Banner();
        banner.setId(UUID.randomUUID());
        banner.setTitle("情人节特惠");
        banner.setImageUrl("/img/photos/hero-rose.jpg");
        banner.setStatus(Banner.STATUS_ACTIVE);
        banner.setSortOrder(10);
        banner.setLinkType(Banner.LINK_NONE);
        banner.setStartTime(start);
        banner.setEndTime(end);
        return banner;
    }
}
