package org.liuym.flowerv1springboot.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 后台跨表只读查询集中地：订单 / 券包 / 收藏 / 评价这些表不属于我的批次，
 * 这里只用 EntityManager 读，绝不持有可写的 Repository，避免并发批次互相覆盖。
 */
@Repository
@Transactional(readOnly = true)
public class AdminQueryRepository {

    /** 已成交口径：与 OrderStatus.DEAL_STATUSES 一致，消费额只算真买到的钱 */
    private static final List<OrderStatus> DEAL = OrderStatus.DEAL_STATUSES;

    /** 后台用户列表可排序字段的白名单：前端字符串永远不直接进 ORDER BY */
    private static final Map<String, String> USER_SORTS = Map.of(
            "createdAt", "createdAt",
            "username", "username",
            "points", "points",
            "lastLoginTime", "lastLoginTime");

    /** 处置留痕回扫窗口：超过这个时间的禁用原因不在列表上追，详情页仍可按账号查满 20 条 */
    private static final int DISPOSITION_LOOKBACK_DAYS = 120;
    private static final int DISPOSITION_SCAN_LIMIT = 800;

    @Autowired
    private EntityManager entityManager;

    /**
     * G18：用户消费概览——订单数、成交单数、消费额、最近下单时间。
     * 成交口径单独一条查询，不用 CASE WHEN 聚合：跨枚举参数的条件聚合在 Hibernate 里最容易出问题
     */
    public Map<String, Object> userOrderStats(UUID userId) {
        Object[] base = entityManager.createQuery("""
                        SELECT COUNT(o.id), MAX(o.createdAt)
                        FROM Order o WHERE o.user.id = :userId""", Object[].class)
                .setParameter("userId", userId)
                .getSingleResult();
        Object[] deal = entityManager.createQuery("""
                        SELECT COUNT(o.id), COALESCE(SUM(o.payAmount), 0)
                        FROM Order o WHERE o.user.id = :userId AND o.status IN :deal""", Object[].class)
                .setParameter("deal", DEAL)
                .setParameter("userId", userId)
                .getSingleResult();
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("orderCount", longOf(base[0]));
        stats.put("lastOrderAt", base[1]);
        stats.put("dealCount", longOf(deal[0]));
        stats.put("dealAmount", decimalOf(deal[1]));
        return stats;
    }

    /**
     * G18：券包按状态分桶，另给一个「其中已过期」——未使用的券里混着过期的，运营要分开看
     */
    public Map<String, Object> userCouponStats(UUID userId) {
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT uc.status, COUNT(uc.id)
                        FROM UserCoupon uc WHERE uc.userId = :userId GROUP BY uc.status""", Object[].class)
                .setParameter("userId", userId)
                .getResultList();
        Map<String, Object> stats = new LinkedHashMap<>();
        long total = 0;
        for (String key : List.of("unused", "used", "expired")) {
            stats.put(key, 0L);
        }
        for (Object[] row : rows) {
            String status = row[0] == null ? "unused" : String.valueOf(row[0]);
            long count = longOf(row[1]);
            total += count;
            stats.put(status, count);
        }
        // 库里 status 还是 unused 但有效期已过的券，单独亮出来提醒运营去催用/作废
        stats.put("unusedExpired", entityManager.createQuery("""
                        SELECT COUNT(uc.id) FROM UserCoupon uc
                        WHERE uc.userId = :userId AND uc.status = 'unused' AND uc.expireAt < :now""", Long.class)
                .setParameter("userId", userId)
                .setParameter("now", LocalDateTime.now())
                .getSingleResult());
        stats.put("total", total);
        return stats;
    }

    /** G18：积分单独取一次，详情页的积分卡片与账号字段分开来源 */
    public long userPointsOf(UUID userId) {
        Integer points = entityManager.createQuery("SELECT u.points FROM User u WHERE u.id = :id", Integer.class)
                .setParameter("id", userId)
                .getResultStream().findFirst().orElse(null);
        return points == null ? 0L : points;
    }

    /**
     * G12：商品被订单引用的明细计数。返回 productId → [订单数, 成交订单数, 累计销量]
     */
    public Map<UUID, long[]> orderReferences(Collection<UUID> productIds) {
        Map<UUID, long[]> refs = new LinkedHashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return refs;
        }
        List<Object[]> rows = entityManager.createQuery("""
                        SELECT i.product.id,
                               COUNT(DISTINCT i.order.id),
                               COUNT(DISTINCT CASE WHEN o.status IN :deal THEN o.id END),
                               COALESCE(SUM(i.quantity), 0)
                        FROM OrderItem i JOIN i.order o
                        WHERE i.product.id IN :ids
                        GROUP BY i.product.id""", Object[].class)
                .setParameter("deal", DEAL)
                .setParameter("ids", new ArrayList<>(productIds))
                .getResultList();
        for (Object[] row : rows) {
            refs.put((UUID) row[0], new long[]{longOf(row[1]), longOf(row[2]), longOf(row[3])});
        }
        return refs;
    }

    /** 收藏引用数：删商品前先清收藏，或据此判定「有人在等这个款，别硬删」 */
    public Map<UUID, Long> favoriteReferences(Collection<UUID> productIds) {
        return countByProduct("Favorite", "product", productIds);
    }

    /** 评价引用数：评价表有 product_id 外键，有评价的商品物理删除会撞约束 */
    public Map<UUID, Long> reviewReferences(Collection<UUID> productIds) {
        return countByProduct("Review", "product", productIds);
    }

    /**
     * G15：后台用户列表多条件分页（关键词 / 身份 / 等级 / 状态 / 注册时间区间）。
     * UserService 的 search 只覆盖前三个条件，注册时间与等级筛选在这里补齐。
     */
    public Page<User> searchUsers(String keyword, String userType, String memberLevel, String status,
                                  LocalDateTime registeredFrom, LocalDateTime registeredTo, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<User> query = cb.createQuery(User.class);
        Root<User> root = query.from(User.class);
        query.select(root).where(userPredicates(cb, root, keyword, userType, memberLevel, status,
                registeredFrom, registeredTo));
        query.orderBy(userOrder(cb, root, pageable.getSort()));

        TypedQuery<User> typed = entityManager.createQuery(query);
        int size = Math.max(pageable.getPageSize(), 1);
        typed.setMaxResults(size).setFirstResult((int) Math.max(pageable.getOffset(), 0));

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<User> countRoot = countQuery.from(User.class);
        countQuery.select(cb.count(countRoot)).where(userPredicates(cb, countRoot, keyword, userType,
                memberLevel, status, registeredFrom, registeredTo));
        long total = entityManager.createQuery(countQuery).getSingleResult();
        return new PageImpl<>(typed.getResultList(), pageable, total);
    }

    /**
     * G28：订单号直达。order_no 有唯一索引，关键词完整匹配时只回一条；
     * 只给片段时按前缀模糊找，最多 5 条足够定位。
     */
    public List<Object[]> searchOrders(String keyword, int limit) {
        return entityManager.createQuery("""
                        SELECT o.id, o.orderNo, o.status, o.payAmount, o.createdAt, o.receiverName
                        FROM Order o
                        WHERE LOWER(o.orderNo) LIKE :like
                           OR LOWER(o.receiverName) LIKE :like
                           OR LOWER(o.receiverPhone) LIKE :like
                        ORDER BY o.createdAt DESC""", Object[].class)
                .setParameter("like", likeOf(keyword))
                .setMaxResults(clampLimit(limit))
                .getResultList();
    }

    /** G28：用户名直达（不含口令字段，返回值只给展示用的四列） */
    public List<Object[]> searchUsersByName(String keyword, int limit) {
        return entityManager.createQuery("""
                        SELECT u.id, u.username, u.fullName, u.status, u.memberLevel
                        FROM User u
                        WHERE LOWER(u.username) LIKE :like OR LOWER(u.fullName) LIKE :like
                           OR LOWER(COALESCE(u.email, '')) LIKE :like
                        ORDER BY u.createdAt DESC""", Object[].class)
                .setParameter("like", likeOf(keyword))
                .setMaxResults(clampLimit(limit))
                .getResultList();
    }

    /**
     * G17：某账号最近的处置留痕（禁用/启用/重置密码），把原因从审计表回读给详情页。
     * uri 形如 /api/admin/users/{id}/status，用 like 命中即可，不建新外键。
     */
    public List<Object[]> userDispositionLogs(UUID userId, int limit) {
        String pattern = "/api/admin/users/" + userId + "/%";
        return entityManager.createQuery("""
                        SELECT a.createdAt, a.action, a.operatorName, a.resultCode, a.resultMsg, a.detail
                        FROM AdminAuditLog a
                        WHERE a.uri LIKE :pattern AND a.module = '用户'
                        ORDER BY a.createdAt DESC""", Object[].class)
                .setParameter("pattern", pattern)
                .setMaxResults(clampLimit(limit))
                .getResultList();
    }

    /**
     * G17：列表页一次取回「每个账号最近一次禁/启用」的留痕，避免逐行查审计表。
     * uri 形如 /api/admin/users/{id}/status，JPQL 没法按 id 集合拼多个 LIKE，
     * 所以按「用户 + 启停 + 时间倒序」扫最近若干条，由调用方用 id 前缀认领第一条（即最新一次）。
     * 返回列：uri, createdAt, operatorName, resultMsg, detail
     */
    public List<Object[]> recentDispositions() {
        return entityManager.createQuery("""
                        SELECT a.uri, a.createdAt, a.operatorName, a.resultMsg, a.detail
                        FROM AdminAuditLog a
                        WHERE a.module = '用户' AND a.action = '启停'
                          AND a.uri LIKE '/api/admin/users/%/status%'
                          AND a.createdAt >= :since
                        ORDER BY a.createdAt DESC""", Object[].class)
                .setParameter("since", LocalDateTime.now().minusDays(DISPOSITION_LOOKBACK_DAYS))
                .setMaxResults(DISPOSITION_SCAN_LIMIT)
                .getResultList();
    }

    private Predicate[] userPredicates(CriteriaBuilder cb, Root<User> root, String keyword, String userType,
                                       String memberLevel, String status,
                                       LocalDateTime registeredFrom, LocalDateTime registeredTo) {
        List<Predicate> ps = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            String like = likeOf(keyword);
            ps.add(cb.or(
                    cb.like(cb.lower(root.get("username")), like),
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("phone")), like),
                    cb.like(cb.lower(cb.coalesce(root.get("email"), "")), like)));
        }
        equalsIfPresent(cb, ps, root.get("userType"), userType);
        equalsIfPresent(cb, ps, root.get("memberLevel"), memberLevel);
        equalsIfPresent(cb, ps, root.get("status"), status);
        if (registeredFrom != null) {
            ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), registeredFrom));
        }
        if (registeredTo != null) {
            ps.add(cb.lessThanOrEqualTo(root.get("createdAt"), registeredTo));
        }
        return ps.toArray(new Predicate[0]);
    }

    private static void equalsIfPresent(CriteriaBuilder cb, List<Predicate> ps, Path<String> path, String value) {
        if (value != null && !value.isBlank()) {
            ps.add(cb.equal(path, value));
        }
    }

    /**
     * 排序只认白名单属性：Pageable 里的字符串先映射成实体属性名，映射不到就回落到注册时间倒序
     */
    private jakarta.persistence.criteria.Order userOrder(CriteriaBuilder cb, Root<User> root, Sort sort) {
        String property = sort.isSorted() ? firstSortProperty(sort) : "createdAt";
        String attribute = USER_SORTS.get(property);
        if (attribute == null) {
            return cb.desc(root.get("createdAt"));
        }
        Sort.Order order = sort.getOrderFor(property);
        return order != null && order.isAscending()
                ? cb.asc(root.get(attribute)) : cb.desc(root.get(attribute));
    }

    private static String firstSortProperty(Sort sort) {
        for (Sort.Order order : sort) {
            return order.getProperty();
        }
        return "createdAt";
    }

    private Map<UUID, Long> countByProduct(String entity, String field, Collection<UUID> productIds) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        if (productIds == null || productIds.isEmpty()) {
            return counts;
        }
        // 表名与列名都取自本类内部的固定字面量，不接受外部入参拼接
        String jpql = "SELECT x." + field + ".id, COUNT(x.id) FROM " + entity + " x WHERE x." + field
                + ".id IN :ids GROUP BY x." + field + ".id";
        for (Object[] row : entityManager.createQuery(jpql, Object[].class)
                .setParameter("ids", new ArrayList<>(productIds)).getResultList()) {
            counts.put((UUID) row[0], longOf(row[1]));
        }
        return counts;
    }

    private static String likeOf(String keyword) {
        return "%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%";
    }

    /** 与商品检索同一套 LIKE 转义，否则 % 会被当通配符用 */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 20);
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 聚合列的类型跟着数据库驱动走（BigDecimal / Long 都可能），统一按字符串转一次最稳 */
    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(value.toString());
    }
}
