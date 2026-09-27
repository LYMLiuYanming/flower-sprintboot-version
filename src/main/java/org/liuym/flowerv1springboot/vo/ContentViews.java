package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Notice;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 内容管理视图（分类 / 轮播图 / 公告）：字段名与实体 JSON 保持一致，
 * 仅剔除 updatedAt 等纯内部列，前端无需改造即可平滑切换
 */
public final class ContentViews {

    private ContentViews() {
    }

    public record CategoryView(
            UUID id,
            String name,
            String description,
            String icon,
            Integer sortOrder,
            UUID parentId,
            Boolean isActive,
            LocalDateTime createdAt) {

        public static CategoryView from(Category c) {
            return new CategoryView(c.getId(), c.getName(), c.getDescription(), c.getIcon(),
                    c.getSortOrder(), c.getParentId(), c.getIsActive(), c.getCreatedAt());
        }

        public static List<CategoryView> from(List<Category> list) {
            return list.stream().map(CategoryView::from).toList();
        }
    }

    public record BannerView(
            UUID id,
            String title,
            String imageUrl,
            String linkUrl,
            String description,
            Integer sortOrder,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            LocalDateTime createdAt) {

        public static BannerView from(Banner b) {
            return new BannerView(b.getId(), b.getTitle(), b.getImageUrl(), b.getLinkUrl(), b.getDescription(),
                    b.getSortOrder(), b.getStatus(), b.getStartTime(), b.getEndTime(), b.getCreatedAt());
        }

        public static List<BannerView> from(List<Banner> list) {
            return list.stream().map(BannerView::from).toList();
        }
    }

    public record NoticeView(
            UUID id,
            String title,
            String content,
            String noticeType,
            Boolean isTop,
            Integer viewCount,
            String status,
            LocalDateTime createdAt) {

        public static NoticeView from(Notice n) {
            return new NoticeView(n.getId(), n.getTitle(), n.getContent(), n.getNoticeType(),
                    n.getIsTop(), n.getViewCount(), n.getStatus(), n.getCreatedAt());
        }

        public static List<NoticeView> from(List<Notice> list) {
            return list.stream().map(NoticeView::from).toList();
        }
    }
}
