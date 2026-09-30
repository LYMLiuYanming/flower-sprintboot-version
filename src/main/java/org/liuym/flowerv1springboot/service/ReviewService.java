package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.Review;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.ReviewGuardQueryRepository;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewCard;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewSummary;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ReviewService {

    /** 单条评价最多 9 张图，追评 6 张：与上传接口和前端选择器用同一个上限，不各写一套 */
    int MAX_IMAGES = 9;
    int MAX_APPEND_IMAGES = 6;
    /** F07 防刷：同一用户在 60 秒内最多提交 3 条评价 */
    int RATE_WINDOW_SECONDS = 60;
    int RATE_WINDOW_LIMIT = 3;

    /** F05 评价标签字典：编码入库、中文名展示，占比统计与筛选都以编码为准 */
    record ReviewTag(String code, String label) {
    }

    List<ReviewTag> TAGS = List.of(
            new ReviewTag("fresh", "花材新鲜"),
            new ReviewTag("packaging", "包装精美"),
            new ReviewTag("ontime", "准时送达"),
            new ReviewTag("beauty", "花艺还原度高"),
            new ReviewTag("service", "客服贴心"),
            new ReviewTag("value", "性价比高"));

    static String tagLabel(String code) {
        return TAGS.stream().filter(t -> t.code().equals(code)).map(ReviewTag::label)
                .findFirst().orElse(code);
    }

    static List<String> tagLabels(List<String> codes) {
        return codes.stream().map(ReviewService::tagLabel).toList();
    }

    /**
     * 评价正文/追评/回复一律按纯文本入库：剥掉全部标签与实体。
     * 比富文本白名单更严——晒单广场是公开页面，不能被一句 <img onerror> 打穿。
     */
    static String plainText(String raw) {
        if (raw == null) {
            return "";
        }
        String text = org.liuym.flowerv1springboot.common.HtmlSanitizer.text(raw);
        return text.length() <= 500 ? text : text.substring(0, 500);
    }

    /**
     * 图片地址校验：只收站内路径（外链图床会失效，也等于替别人打广告），
     * 逐段过 {@link org.liuym.flowerv1springboot.common.SafeUrl}，超出上限的尾部直接丢弃。
     *
     * @return 逗号拼接后的地址串；没有有效图片时返回 null
     */
    static String normalizeImages(List<String> images, int max) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        java.util.Set<String> distinct = new java.util.LinkedHashSet<>();
        for (String image : images) {
            if (image == null || image.isBlank()) {
                continue;
            }
            String url;
            try {
                url = org.liuym.flowerv1springboot.common.SafeUrl.requireSafe(image.trim(), "评价图片");
            } catch (IllegalArgumentException e) {
                throw new org.liuym.flowerv1springboot.common.BusinessException("评价图片地址不合法：" + e.getMessage());
            }
            if (url.startsWith("http://") || url.startsWith("https://")) {
                throw new org.liuym.flowerv1springboot.common.BusinessException("评价图片请先上传，不要直接填外链地址");
            }
            distinct.add(url);
            if (distinct.size() >= max) {
                break;
            }
        }
        return distinct.isEmpty() ? null : String.join(",", distinct);
    }

    /** 标签只认字典里的编码（F05），脏编码直接拒绝而不是悄悄丢掉，否则运营看不出为什么没生效 */
    static String normalizeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String code = tag.trim().toLowerCase();
            if (TAGS.stream().noneMatch(t -> t.code().equals(code))) {
                throw new org.liuym.flowerv1springboot.common.BusinessException("评价标签不合法：" + tag);
            }
            codes.add(code);
            if (codes.size() == 6) {
                break;
            }
        }
        return codes.isEmpty() ? null : String.join(",", codes);
    }

    /** 标签筛选走 CONCAT(',', tags, ',') LIKE '%,code,%'，字典外的编码按「不限」处理 */
    static String tagPattern(String tag) {
        if (tag == null || tag.isBlank()) {
            return "";
        }
        String code = tag.trim().toLowerCase();
        return TAGS.stream().anyMatch(t -> t.code().equals(code)) ? "%," + code + ",%" : "";
    }

    /** 兼容旧调用方（订单详情页）：只带评分与文字的提交 */
    ReviewView submit(UUID userId, ContentDtos.ReviewForm form);

    Page<ReviewView> listByProduct(UUID productId, Pageable pageable);

    /** F01/F02/F05/F07：新版评价提交，服务端做归属、状态、重复与频率四重校验 */
    ReviewCard submitCard(UUID userId, ContentDtos.ReviewSubmit form);

    /** F03 买家追评：仅本人、仅一次 */
    ReviewCard append(UUID userId, UUID reviewId, ContentDtos.ReviewAppendForm form);

    /** F04 商家回复：后台写入，重复回复按覆盖处理并留下回复人与时间 */
    ReviewCard reply(UUID reviewId, ContentDtos.ReviewReplyForm form, User operator);

    /** F08 隐藏/恢复：返回结果摘要供控制器写审计 */
    ModerationResult moderate(UUID reviewId, ContentDtos.ReviewModerationForm form, User operator);

    /** F08 删除：物理删除前先取内容快照，删除后审计里仍能看清被删的是什么 */
    ModerationResult deleteReview(UUID reviewId, String reason, User operator);

    /** F07 当前用户对该商品可评价的订单明细（已签收/已完成且未评价） */
    List<ReviewGuardQueryRepository.ReviewableItem> reviewableOf(UUID userId, UUID productId, int limit);

    /** F06/F05/F09 商品评价分页：星级、有图、标签筛选 */
    Page<ReviewCard> queryByProduct(Filter filter, Pageable pageable);

    /** F10 晒单广场：全站可见评价流，可按分类筛选 */
    Page<ReviewCard> queryShowcase(Filter filter, Pageable pageable);

    /** 我的评价：含被隐藏的记录，前端据此提示「已提交，正在审核」 */
    Page<ReviewCard> queryMine(UUID userId, Pageable pageable);

    /** 后台审核列表（F08）：可以看到被隐藏的记录 */
    Page<ReviewCard> queryAdmin(UUID productId, Boolean visible, Integer minRating, String keyword, Pageable pageable);

    /** 单条评价（后台详情/前台锚点） */
    java.util.Optional<ReviewCard> cardById(UUID reviewId);

    /** 后台审核台概览：总数、可见数、待回复数、已隐藏数 */
    ModerationStats moderationStats();

    /** F05/F06/F09 商品评价摘要：均分、星级分布、标签占比、带图与追评数 */
    ReviewSummary summaryOf(UUID productId);

    /** F09 重算：返回被修正的商品数；productIds 为空时自动扫描计数不一致的商品 */
    int recalcRatings(List<UUID> productIds);

    /**
     * 筛选条件。sort 取值：newest（默认）/ rating-desc / rating-asc。
     * tag 为标签编码，非法编码不会报错而是按「不限」处理，避免脏参数把列表打成 500。
     */
    record Filter(UUID productId, UUID categoryId, Integer minRating, Integer maxRating,
                  Boolean hasImage, String tag, String sort) {

        public static Filter ofProduct(UUID productId) {
            return new Filter(productId, null, null, null, null, null, null);
        }
    }

    /** 审核结果快照：实体在事务提交后就是游离对象，需要留痕的字段在这里一次性取好 */
    record ModerationResult(UUID reviewId, UUID productId, String productName, Integer rating,
                            String contentExcerpt, boolean visibleBefore, boolean visibleAfter,
                            String reason, String action) {
    }

    /** 后台审核台概览 */
    record ModerationStats(long total, long visible, long unreplied, long hidden) {
    }
}
