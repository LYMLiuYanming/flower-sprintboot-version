package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Article;
import org.liuym.flowerv1springboot.model.Banner;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.Review;
import org.liuym.flowerv1springboot.service.ReviewService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 内容管理视图（分类 / 轮播图 / 公告 / 评价 / 知识库文章）：字段名与实体 JSON 保持一致，
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

    /**
     * F13/F14：新增 linkType/linkTarget/thumbUrl 与派生的 state（live/scheduled/expired/disabled），
     * 老字段一个不动，首页与后台列表可以照常消费。
     */
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
            LocalDateTime createdAt,
            String linkType,
            String linkTarget,
            String thumbUrl,
            String state,
            String stateLabel) {

        private static final String[] STATE_LABELS = {
                "投放中", "未开始", "已结束", "已停用"};

        public static BannerView from(Banner b) {
            LocalDateTime now = LocalDateTime.now();
            String state = b.displayState(now);
            return new BannerView(b.getId(), b.getTitle(), b.getImageUrl(), b.getLinkUrl(), b.getDescription(),
                    b.getSortOrder(), b.getStatus(), b.getStartTime(), b.getEndTime(), b.getCreatedAt(),
                    b.getLinkType(), b.getLinkTarget(), b.getThumbUrl(), state, stateLabel(state));
        }

        public static List<BannerView> from(List<Banner> list) {
            return list.stream().map(BannerView::from).toList();
        }

        private static String stateLabel(String state) {
            return switch (state) {
                case Banner.STATE_LIVE -> STATE_LABELS[0];
                case Banner.STATE_SCHEDULED -> STATE_LABELS[1];
                case Banner.STATE_EXPIRED -> STATE_LABELS[2];
                default -> STATE_LABELS[3];
            };
        }
    }

    /** F11/F12：时间窗与已读统计随列表一起下发，后台无需二次请求就能看到「为什么前台没显示」 */
    public record NoticeView(
            UUID id,
            String title,
            String content,
            String noticeType,
            Boolean isTop,
            Integer viewCount,
            String status,
            LocalDateTime createdAt,
            Integer readUserCount,
            Integer readTimes,
            LocalDateTime publishAt,
            LocalDateTime offlineAt,
            String coverImage,
            String state,
            String summary) {

        public static NoticeView from(Notice n) {
            String state = n.displayState(LocalDateTime.now());
            return new NoticeView(n.getId(), n.getTitle(), n.getContent(), n.getNoticeType(),
                    n.getIsTop(), n.getViewCount(), n.getStatus(), n.getCreatedAt(),
                    n.getReadUserCount(), n.getReadTimes(), n.getPublishAt(), n.getOfflineAt(),
                    n.getCoverImage(), state, summary(n.getContent()));
        }

        public static List<NoticeView> from(List<Notice> list) {
            return list.stream().map(NoticeView::from).toList();
        }

        /** 列表页摘要：剥掉富文本标签后截断，避免把整段 HTML 塞进表格 */
        private static String summary(String html) {
            String text = org.liuym.flowerv1springboot.common.HtmlSanitizer.text(html);
            return text.length() <= 60 ? text : text.substring(0, 59) + "…";
        }
    }

    public record NoticeReadStat(UUID noticeId, Integer readUserCount, Integer readTimes, Integer viewCount) {
    }

    /**
     * 评价卡片（F01/F02/F03/F04/F05）：图片、标签、追评与商家回复一次给全，
     * 时间线由前端按 createdAt → appendAt → replyAt 顺序渲染。
     */
    public record ReviewCard(
            UUID id,
            UUID productId,
            String productName,
            String productImage,
            UUID categoryId,
            String categoryName,
            String userName,
            Boolean anonymous,
            Integer rating,
            String content,
            List<String> images,
            List<String> tags,
            List<String> tagLabels,
            String appendContent,
            List<String> appendImages,
            LocalDateTime appendAt,
            String reply,
            LocalDateTime replyAt,
            String replyByName,
            Boolean visible,
            String hiddenReason,
            LocalDateTime createdAt) {

        public static ReviewCard from(Review r) {
            return new ReviewCard(r.getId(),
                    r.getProduct() == null ? null : r.getProduct().getId(),
                    r.getProduct() == null ? null : r.getProduct().getName(),
                    // 晒单广场与详情页评价都要带商品图，漏传会让实参比 record 少一位
                    r.getProduct() == null ? null : r.getProduct().getMainImage(),
                    r.getProduct() == null || r.getProduct().getCategory() == null ? null
                            : r.getProduct().getCategory().getId(),
                    r.getProduct() == null || r.getProduct().getCategory() == null ? null
                            : r.getProduct().getCategory().getName(),
                    displayName(r),
                    Boolean.TRUE.equals(r.getAnonymous()),
                    r.getRating(), r.getContent(),
                    splitList(r.getImages()),
                    splitList(r.getTags()),
                    ReviewService.tagLabels(splitList(r.getTags())),
                    r.getAppendContent(),
                    splitList(r.getAppendImages()),
                    r.getAppendAt(),
                    r.getReply(), r.getReplyAt(), r.getReplyByName(),
                    r.getVisible(), r.getHiddenReason(), r.getCreatedAt());
        }

        public static List<ReviewCard> from(List<Review> list) {
            return list.stream().map(ReviewCard::from).toList();
        }

        /** F02：匿名评价不输出任何可回溯到账号的信息，包括昵称首字 */
        private static String displayName(Review r) {
            if (Boolean.TRUE.equals(r.getAnonymous())) {
                return "匿名顾客";
            }
            String name = r.getUser() == null ? null : r.getUser().getFullName();
            if (name == null || name.isBlank()) {
                name = r.getUser() == null ? null : r.getUser().getUsername();
            }
            if (name == null || name.isBlank()) {
                return "花语轩顾客";
            }
            if (name.length() == 1) {
                return name + "**";
            }
            return name.charAt(0) + "**" + name.charAt(name.length() - 1);
        }
    }

    /** F06 星级分布的一档 */
    public record StarBucket(Integer rating, Long count) {
    }

    /** F05 标签占比：ratio 为「选该标签的评价数 / 有效评价数」的百分数 */
    public record TagStat(String code, String label, Long count, Double ratio) {
    }

    /** F05/F06/F09 商品评价摘要 */
    public record ReviewSummary(
            UUID productId,
            Double avgRating,
            Long total,
            Long withImageCount,
            Long appendCount,
            List<StarBucket> stars,
            List<TagStat> tags) {
    }

    /** F15/F16 知识库文章：list 形态不带正文，detail 形态带正文 */
    public record ArticleView(
            UUID id,
            String title,
            String summary,
            String coverImage,
            String category,
            String categoryLabel,
            List<String> tags,
            List<String> materials,
            List<UUID> relatedProductIds,
            String status,
            String state,
            Integer viewCount,
            Boolean isTop,
            Integer sortOrder,
            String authorName,
            LocalDateTime publishAt,
            LocalDateTime offlineAt,
            LocalDateTime createdAt,
            String content) {

        public static ArticleView from(Article a, boolean withContent) {
            return new ArticleView(a.getId(), a.getTitle(), a.getSummary(), a.getCoverImage(),
                    a.getCategory(), categoryLabel(a.getCategory()),
                    splitList(a.getTags()), splitList(a.getMaterials()),
                    splitIds(a.getRelatedProductIds()),
                    a.getStatus(), state(a), a.getViewCount(), a.getIsTop(), a.getSortOrder(),
                    a.getAuthorName(), a.getPublishAt(), a.getOfflineAt(), a.getCreatedAt(),
                    withContent ? a.getContent() : null);
        }

        public static List<ArticleView> from(List<Article> list) {
            return list.stream().map(a -> from(a, false)).toList();
        }

        private static String state(Article a) {
            if (!Article.STATUS_PUBLISHED.equals(a.getStatus())) {
                return Article.STATUS_DRAFT.equals(a.getStatus()) ? "draft" : "offline";
            }
            return a.displayableAt(LocalDateTime.now()) ? "live" : "scheduled";
        }

        private static String categoryLabel(String category) {
            return switch (category == null ? "" : category) {
                case Article.CATEGORY_CARE -> "养护指南";
                case Article.CATEGORY_LANGUAGE -> "花语故事";
                case Article.CATEGORY_STORY -> "产地与花艺";
                case Article.CATEGORY_GUIDE -> "送礼手册";
                case Article.CATEGORY_FESTIVAL -> "节日特辑";
                default -> "知识库";
            };
        }
    }

    /** 知识库分类计数（列表页分类瓦片） */
    public record ArticleCategory(String code, String label, Long count) {
    }

    /** 后台「关联商品」选择器的选项 */
    public record ProductOption(UUID id, String name, String mainImage, BigDecimal price, Boolean isActive) {

        public static ProductOption from(Product p) {
            return new ProductOption(p.getId(), p.getName(), p.getMainImage(), p.getPrice(), p.getIsActive());
        }
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : Arrays.asList(raw.split("[,，]"))) {
            String value = part.trim();
            if (!value.isEmpty() && !out.contains(value)) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<UUID> splitIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<UUID> out = new ArrayList<>();
        for (String part : raw.split("[,，]")) {
            try {
                if (!part.isBlank()) {
                    out.add(UUID.fromString(part.trim()));
                }
            } catch (IllegalArgumentException ignored) {
                // 单个脏 UUID 不该让整篇文章打不开
            }
        }
        return out;
    }
}
