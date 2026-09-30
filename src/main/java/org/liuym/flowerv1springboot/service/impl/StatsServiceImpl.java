package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.config.CacheConfig;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.repository.*;
import org.liuym.flowerv1springboot.service.StatsService;
import org.liuym.flowerv1springboot.vo.StatsViews;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class StatsServiceImpl implements StatsService {

    /** 成交口径：与看板/报表完全一致，取消与退款不算成交 */
    private static final List<OrderStatus> DEAL_STATUSES = OrderStatus.DEAL_STATUSES;

    /** status 列由 OrderStatusConverter 以小写 code 存储，原生 SQL 聚合需按同样的字面量比对 */
    private static final List<String> DEAL_STATUS_CODES = DEAL_STATUSES.stream().map(OrderStatus::getCode).toList();

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final BannerRepository bannerRepository;
    private final NoticeRepository noticeRepository;

    public StatsServiceImpl(OrderRepository orderRepository,
                            ProductRepository productRepository,
                            CategoryRepository categoryRepository,
                            UserRepository userRepository,
                            BannerRepository bannerRepository,
                            NoticeRepository noticeRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
        this.bannerRepository = bannerRepository;
        this.noticeRepository = noticeRepository;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'legacy:overview'")
    public Map<String, Object> overview() {
        BigDecimal paidAmount = orderRepository.sumPayAmount(DEAL_STATUSES);
        long paidCount = orderRepository.countByStatusIn(DEAL_STATUSES);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productCount", productRepository.count());
        data.put("activeProductCount", productRepository.countByIsActiveTrue());
        data.put("categoryCount", categoryRepository.count());
        data.put("userCount", userRepository.count());
        data.put("orderCount", orderRepository.count());
        data.put("pendingOrderCount", orderRepository.countByStatus(OrderStatus.PENDING));
        data.put("paidOrderCount", paidCount);
        data.put("paidAmount", paidAmount);
        data.put("avgOrderAmount", StatsViews.ratio(paidAmount, paidCount));
        data.put("refundOrderCount", orderRepository.countByStatus(OrderStatus.REFUNDED));
        data.put("cancelOrderCount", orderRepository.countByStatus(OrderStatus.CANCELLED));
        data.put("bannerCount", bannerRepository.count());
        data.put("noticeCount", noticeRepository.count());
        return data;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'legacy:trend:' + #p0")
    public List<Map<String, Object>> salesTrend(int days) {
        int span = Math.max(1, Math.min(days, 90));
        LocalDate from = LocalDate.now().minusDays(span - 1L);
        List<Object[]> rows = orderRepository.sumDailyPaid(from.atStartOfDay(), DEAL_STATUS_CODES);

        Map<String, Object[]> indexed = new HashMap<>();
        for (Object[] row : rows) {
            indexed.put(String.valueOf(row[0]), row);
        }

        List<Map<String, Object>> trend = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            String day = from.plusDays(i).toString();
            Object[] row = indexed.get(day);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", day);
            point.put("amount", row == null ? BigDecimal.ZERO : row[1]);
            point.put("orders", row == null ? 0 : row[2]);
            trend.add(point);
        }
        return trend;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'legacy:top-products:' + #p0")
    public List<Map<String, Object>> topProducts(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : orderRepository.topProducts(DEAL_STATUSES, PageRequest.of(0, clamp(limit)))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", row[0]);
            item.put("productName", row[1]);
            item.put("quantity", row[2]);
            item.put("amount", row[3]);
            list.add(item);
        }
        return list;
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.STATS, key = "'legacy:top-spenders:' + #p0")
    public List<Map<String, Object>> topSpenders(int limit) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object[] row : orderRepository.topSpenders(DEAL_STATUSES, PageRequest.of(0, clamp(limit)))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", row[0]);
            item.put("username", row[1]);
            item.put("fullName", row[2]);
            item.put("orders", row[3]);
            item.put("amount", row[4]);
            list.add(item);
        }
        return list;
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 50));
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.STATS, allEntries = true)
    @Transactional(readOnly = false)
    public void clearCache() {
        // 缓存清理由注解完成，方法体留空
    }
}
