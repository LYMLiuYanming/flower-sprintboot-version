package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.HtmlSanitizer;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.*;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.repository.ReviewGuardQueryRepository.Guard;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewCard;
import org.liuym.flowerv1springboot.vo.ContentViews.ReviewSummary;
import org.liuym.flowerv1springboot.vo.ContentViews.StarBucket;
import org.liuym.flowerv1springboot.vo.ContentViews.TagStat;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ReviewGuardQueryRepository guardRepository;

    public ReviewServiceImpl(ReviewRepository reviewRepository,
                             OrderRepository orderRepository,
                             OrderItemRepository orderItemRepository,
                             ProductRepository productRepository,
                             UserRepository userRepository,
                             ReviewGuardQueryRepository guardRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.guardRepository = guardRepository;
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    public ReviewView submit(UUID userId, ContentDtos.ReviewForm form) {
        List<String> images = form.images() == null || form.images().isBlank()
                ? List.of() : List.of(form.images().split(","));
        ReviewCard card = submitCard(userId, new ContentDtos.ReviewSubmit(
                form.orderId(), form.orderItemId(), form.rating(), form.content(), images, List.of(), false));
        // 旧调用方（订单详情页）只认这 8 个字段，卡片对象在评分重算之前已取好，不会踩到会话被清空的问题
        return new ReviewView(card.id(), card.productId(), card.productName(), card.productImage(),
                card.userName(), card.rating(), card.content(), card.createdAt());
    }

    @Override
    public ReviewCard submitCard(UUID userId, ContentDtos.ReviewSubmit form) {
        Guard guard = checkGuard(userId, form.orderId(), form.orderItemId());
        Product product = productRepository.findById(guard.productId())
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));

        Review review = new Review();
        review.setOrder(orderRepository.findById(guard.orderId())
                .orElseThrow(() -> BusinessException.notFound("订单不存在")));
        review.setOrderItem(orderItemRepository.findById(guard.orderItemId())
                .orElseThrow(() -> BusinessException.notFound("订单明细不存在")));
        review.setProduct(product);
        review.setUser(userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在")));
        review.setRating(form.rating());
        review.setContent(plainText(form.content()));
        review.setImages(joinUrls(form.images(), MAX_IMAGES));
        review.setTags(joinTags(form.tags()));
        review.setAnonymous(Boolean.TRUE.equals(form.anonymous()));

        Review saved = reviewRepository.save(review);
        // 先出卡片再重算：重算用的是 @Modifying(clearAutomatically)，会话被清空后懒加载就取不到了
        ReviewCard card = ReviewCard.from(saved);
        refreshProductRating(guard.productId());
        return card;
    }

    @Override
    public ReviewCard append(UUID userId, UUID reviewId, ContentDtos.ReviewAppendForm form) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评价不存在"));
        if (review.getUser() == null || !review.getUser().getId().equals(userId)) {
            throw BusinessException.forbidden("只能追评自己写过的评价");
        }
        if (review.getAppendContent() != null && !review.getAppendContent().isBlank()) {
            throw new BusinessException("每条评价只能追加一次");
        }
        String content = plainText(form.content());
        if (content.isEmpty()) {
            throw new BusinessException("追评内容不能为空");
        }
        review.setAppendContent(content);
        review.setAppendImages(joinUrls(form.images(), MAX_APPEND_IMAGES));
        review.setAppendAt(LocalDateTime.now());
        Review saved = reviewRepository.save(review);
        return ReviewCard.from(saved);
    }

    @Override
    public ReviewCard reply(UUID reviewId, ContentDtos.ReviewReplyForm form, User operator) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评价不存在"));
        String content = plainText(form.content());
        if (content.isEmpty()) {
            throw new BusinessException("回复内容不能为空");
        }
        review.setReply(content);
        review.setReplyAt(LocalDateTime.now());
        review.setReplyBy(operator == null ? null : operator.getId());
        review.setReplyByName(operator == null ? "花语轩花艺师" : operator.getUsername());
        Review saved = reviewRepository.save(review);
        return ReviewCard.from(saved);
    }

    @Override
    public ModerationResult moderate(UUID reviewId, ContentDtos.ReviewModerationForm form, User operator) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评价不存在"));
        // 快照必须在任何 @Modifying 之前取：重算语句会清空会话，之后再也读不到懒加载字段
        UUID productId = reviewProductId(review);
        String productName = reviewProductName(review);
        boolean before = !Boolean.FALSE.equals(review.getVisible());
        boolean after = Boolean.TRUE.equals(form.visible());
        String reason = normalizeReason(form.reason(), after);
        String excerpt = excerpt(review.getContent());
        Integer rating = review.getRating();

        review.setVisible(after);
        review.setHiddenReason(after ? null : reason);
        reviewRepository.save(review);
        refreshProductRating(productId);
        return new ModerationResult(reviewId, productId, productName, rating, excerpt, before, after, reason,
                after ? "恢复展示" : "隐藏评价");
    }

    @Override
    public ModerationResult deleteReview(UUID reviewId, String reason, User operator) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> BusinessException.notFound("评价不存在"));
        UUID productId = reviewProductId(review);
        ModerationResult snapshot = new ModerationResult(reviewId, productId, reviewProductName(review),
                review.getRating(), excerpt(review.getContent()),
                !Boolean.FALSE.equals(review.getVisible()), false, normalizeReason(reason, false), "删除评价");
        reviewRepository.delete(review);
        refreshProductRating(productId);
        return snapshot;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewView> listByProduct(UUID productId, Pageable pageable) {
        return reviewRepository.findVisibleByProductId(productId, pageable).map(ReviewView::from);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewGuardQueryRepository.ReviewableItem> reviewableOf(UUID userId, UUID productId, int limit) {
        return guardRepository.findReviewable(userId, productId, limit);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewCard> queryByProduct(Filter filter, Pageable pageable) {
        if (filter.productId() == null) {
            throw new BusinessException("缺少商品编号");
        }
        return reviewRepository.searchVisible(filter.productId(), null,
                        filter.minRating(), filter.maxRating(), filter.hasImage(), tagPattern(filter.tag()),
                        withCreatedSort(pageable, filter.sort()))
                .map(ReviewCard::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewCard> queryShowcase(Filter filter, Pageable pageable) {
        return reviewRepository.searchVisible(null, filter.categoryId(),
                        filter.minRating(), filter.maxRating(), filter.hasImage(), tagPattern(filter.tag()),
                        withCreatedSort(pageable, filter.sort()))
                .map(ReviewCard::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewCard> queryMine(UUID userId, Pageable pageable) {
        return reviewRepository.searchByUser(userId, withCreatedSort(pageable, null)).map(ReviewCard::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewCard> queryAdmin(UUID productId, Boolean visible, Integer minRating, String keyword,
                                       Pageable pageable) {
        return reviewRepository.searchAdmin(productId, visible, minRating,
                        keyword == null ? "" : keyword.trim(), withCreatedSort(pageable, null))
                .map(ReviewCard::from);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ReviewCard> cardById(UUID reviewId) {
        return reviewRepository.findById(reviewId).map(ReviewCard::from);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationStats moderationStats() {
        long total = reviewRepository.count();
        long hidden = reviewRepository.countHidden();
        return new ModerationStats(total, total - hidden, reviewRepository.countUnreplied(), hidden);
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewSummary summaryOf(UUID productId) {
        List<Object[]> stars = reviewRepository.starDistribution(productId);
        Map<Integer, Long> starCounts = new LinkedHashMap<>();
        long total = 0;
        double sum = 0;
        for (Object[] row : stars) {
            int rating = ((Number) row[0]).intValue();
            long count = ((Number) row[1]).longValue();
            starCounts.put(rating, count);
            total += count;
            sum += (double) rating * count;
        }
        // 1..5 补齐空档，前端画星级条形图不用再判空
        List<StarBucket> buckets = new ArrayList<>();
        for (int i = 5; i >= 1; i--) {
            buckets.add(new StarBucket(i, starCounts.getOrDefault(i, 0L)));
        }

        List<Object[]> media = reviewRepository.mediaSummary(productId);
        long withImage = media.isEmpty() ? 0 : ((Number) media.get(0)[0]).longValue();
        long withAppend = media.isEmpty() ? 0 : ((Number) media.get(0)[1]).longValue();

        List<TagStat> tagStats = new ArrayList<>();
        if (total > 0) {
            Map<String, Long> raw = new LinkedHashMap<>();
            for (Object[] row : reviewRepository.tagDistribution(productId)) {
                String code = String.valueOf(row[0]).trim();
                raw.merge(code, ((Number) row[1]).longValue(), Long::sum);
            }
            for (ReviewTag tag : TAGS) {
                Long count = raw.get(tag.code());
                if (count == null || count == 0) {
                    continue;
                }
                tagStats.add(new TagStat(tag.code(), tag.label(), count,
                        BigDecimal.valueOf(count * 100.0 / total).setScale(1, RoundingMode.HALF_UP).doubleValue()));
            }
            // 字典外的历史编码也要露出来，否则后台看不到脏数据的规模
            // total 是循环里累加进来的，不是 effectively final，lambda 只能用这份快照
            final long shareBase = total;
            raw.entrySet().stream()
                    .filter(e -> TAGS.stream().noneMatch(t -> t.code().equals(e.getKey())))
                    .forEach(e -> tagStats.add(new TagStat(e.getKey(), ReviewService.tagLabel(e.getKey()), e.getValue(),
                            BigDecimal.valueOf(e.getValue() * 100.0 / shareBase).setScale(1, RoundingMode.HALF_UP).doubleValue())));
        }

        double avg = total == 0 ? 5.0 : BigDecimal.valueOf(sum / total).setScale(1, RoundingMode.HALF_UP).doubleValue();
        return new ReviewSummary(productId, avg, total, withImage, withAppend, buckets, tagStats);
    }

    @Override
    public int recalcRatings(List<UUID> productIds) {
        List<UUID> targets = productIds == null || productIds.isEmpty()
                ? reviewRepository.productsWithStaleCount() : productIds;
        Set<UUID> distinct = new LinkedHashSet<>(targets);
        distinct.remove(null);
        distinct.forEach(this::refreshProductRating);
        return distinct.size();
    }

    // ------------------------------------------------------------------
    // 校验与清洗
    // ------------------------------------------------------------------

    /**
     * F07 服务端四重校验：归属 → 订单状态 → 重复 → 频率。
     * 任何一条都由后端说了算，前端传什么 orderId/orderItemId 都不作数。
     */
    private Guard checkGuard(UUID userId, UUID orderId, UUID orderItemId) {
        Guard guard = guardRepository.guardOf(orderItemId)
                .orElseThrow(() -> BusinessException.notFound("订单明细不存在"));
        if (!Objects.equals(guard.orderId(), orderId)) {
            throw new BusinessException("订单与明细不匹配");
        }
        if (!Objects.equals(guard.userId(), userId)) {
            throw BusinessException.forbidden("无权评价该订单");
        }
        if (!guard.reviewable()) {
            throw new BusinessException("订单签收后才可以评价");
        }
        if (Boolean.FALSE.equals(guard.productActive())) {
            throw new BusinessException("评价商品已下架，无法评价");
        }
        if (guardRepository.alreadyReviewed(orderItemId)) {
            throw new BusinessException("该商品已评价");
        }
        long recent = reviewRepository.countSince(userId, LocalDateTime.now().minusSeconds(RATE_WINDOW_SECONDS));
        if (recent >= RATE_WINDOW_LIMIT) {
            throw new BusinessException("评价太频繁了，休息一下再写吧");
        }
        return guard;
    }

    /**
     * 评价正文是用户自由文本，一律按纯文本入库（见 {@link ReviewService#plainText}）。
     */
    private static String plainText(String raw) {
        return ReviewService.plainText(raw);
    }

    private static String joinUrls(List<String> images, int max) {
        return ReviewService.normalizeImages(images, max);
    }

    private static String joinTags(List<String> tags) {
        return ReviewService.normalizeTags(tags);
    }

    /** 标签筛选串，字典外编码按「不限」处理 */
    private static String tagPattern(String tag) {
        return ReviewService.tagPattern(tag);
    }

    private static String normalizeReason(String reason, boolean visible) {
        if (visible) {
            return reason == null || reason.isBlank() ? "人工复核后恢复展示" : plainText(reason);
        }
        String text = plainText(reason);
        return text.isEmpty() ? "后台审核隐藏" : text;
    }

    private static String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "（无文字）";
        }
        return content.length() <= 80 ? content : content.substring(0, 79) + "…";
    }

    private static Sort sortOf(String sort) {
        return switch (sort == null ? "" : sort) {
            case "rating-desc" -> Sort.by(Sort.Direction.DESC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            case "rating-asc" -> Sort.by(Sort.Direction.ASC, "rating").and(Sort.by(Sort.Direction.DESC, "createdAt"));
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
    }

    /**
     * 排序键只认白名单，其余一律按最新优先；页码/页长沿用调用方（Pages 已做过越界收敛）的值。
     */
    private static Pageable withCreatedSort(Pageable pageable, String sort) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sortOf(sort));
    }

    /**
     * 评价入库/审核后重算商品评分与评价数，替换静态种子值（F09）
     */
    private void refreshProductRating(UUID productId) {
        if (productId == null) {
            return;
        }
        List<Object[]> summary = reviewRepository.ratingSummary(productId);
        BigDecimal avg = BigDecimal.valueOf(5.0);
        long count = 0;
        if (!summary.isEmpty() && summary.get(0)[0] != null) {
            avg = new BigDecimal(summary.get(0)[0].toString()).setScale(1, RoundingMode.HALF_UP);
            count = ((Number) summary.get(0)[1]).longValue();
        }
        productRepository.updateRatingStats(productId, avg, count);
    }

    private static UUID reviewProductId(Review review) {
        return review.getProduct() == null ? null : review.getProduct().getId();
    }

    private static String reviewProductName(Review review) {
        return review.getProduct() == null ? "（商品已删除）" : review.getProduct().getName();
    }
}
