package org.liuym.flowerv1springboot.service.impl;

import cn.hutool.http.HtmlUtil;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.dto.ContentDtos;
import org.liuym.flowerv1springboot.model.*;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.service.ReviewService;
import org.liuym.flowerv1springboot.vo.SupportViews.ReviewView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public ReviewServiceImpl(ReviewRepository reviewRepository,
                             OrderRepository orderRepository,
                             OrderItemRepository orderItemRepository,
                             ProductRepository productRepository,
                             UserRepository userRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Override
    public ReviewView submit(UUID userId, ContentDtos.ReviewForm form) {
        Order order = orderRepository.findDetailById(form.orderId())
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        if (order.getUser() == null || !order.getUser().getId().equals(userId)) {
            throw BusinessException.forbidden("无权评价该订单");
        }
        if (order.getStatus() != OrderStatus.DELIVERED && order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException("订单签收后才可以评价");
        }
        OrderItem orderItem = orderItemRepository.findById(form.orderItemId())
                .filter(i -> i.getOrder() != null && i.getOrder().getId().equals(order.getId()))
                .orElseThrow(() -> BusinessException.notFound("订单明细不存在"));
        // 取真实实体而非懒加载代理：refreshProductRating 的批量更新会 clearAutomatically 清空会话
        Product product = orderItem.getProduct() == null ? null
                : productRepository.findById(orderItem.getProduct().getId()).orElse(null);
        if (product == null) {
            throw new BusinessException("评价商品已下架，无法评价");
        }
        if (reviewRepository.existsByOrderItemIdAndProductId(orderItem.getId(), product.getId())) {
            throw new BusinessException("该商品已评价");
        }

        Review review = new Review();
        review.setOrder(order);
        review.setOrderItem(orderItem);
        review.setProduct(product);
        review.setUser(userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("用户不存在")));
        review.setRating(form.rating());
        review.setContent(HtmlUtil.filter(form.content() == null ? null : form.content().trim()));
        review.setImages(safeImages(form.images()));

        Review saved = reviewRepository.save(review);
        ReviewView view = ReviewView.from(saved);
        refreshProductRating(product.getId());
        return view;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewView> listByProduct(UUID productId, Pageable pageable) {
        return reviewRepository.findVisibleByProductId(productId, pageable).map(ReviewView::from);
    }

    /**
     * 评价入库后重算商品评分与评价数，替换静态种子值
     */
    private void refreshProductRating(UUID productId) {
        List<Object[]> summary = reviewRepository.ratingSummary(productId);
        BigDecimal avg = BigDecimal.valueOf(5.0);
        long count = 0;
        if (!summary.isEmpty() && summary.get(0)[0] != null) {
            avg = new BigDecimal(summary.get(0)[0].toString()).setScale(1, RoundingMode.HALF_UP);
            count = ((Number) summary.get(0)[1]).longValue();
        }
        productRepository.updateRatingStats(productId, avg, count);
    }

    private String safeImages(String images) {
        if (images == null || images.isBlank()) {
            return null;
        }
        return java.util.Arrays.stream(images.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> SafeUrl.requireSafe(s, "评价图片"))
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }
}
