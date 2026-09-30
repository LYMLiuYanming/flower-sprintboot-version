package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.CouponRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.UserCouponRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.vo.CouponViews;
import org.liuym.flowerv1springboot.vo.CouponViews.CouponStatView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
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
public class CouponServiceImpl implements CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponServiceImpl.class);

    /** E07：转赠挂起超过这个时长就退回原主，避免券一直被锁着 */
    private static final int TRANSFER_HOLD_HOURS = 48;

    /** 转赠凭证字符集：去掉容易混淆的 0/O/1/I */
    private static final char[] TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    /* ---------- 后台 ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findAllForAdmin() {
        return couponRepository.findAllByOrderByCreatedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public Coupon findById(UUID id) {
        return couponRepository.findById(id).orElseThrow(() -> new BusinessException("优惠券不存在"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findByIds(List<UUID> ids) {
        return ids == null || ids.isEmpty() ? List.of() : couponRepository.findByIdIn(ids);
    }

    @Override
    public Coupon createByForm(CouponDtos.Form form) {
        validate(form);
        String code = normalizeCode(form.code());
        if (couponRepository.countByCode(code) > 0) {
            throw new BusinessException("券码已存在");
        }
        Coupon coupon = new Coupon();
        applyForm(coupon, form, code);
        coupon.setIssued(0);
        return couponRepository.save(coupon);
    }

    @Override
    public Coupon updateByForm(UUID id, CouponDtos.Form form) {
        validate(form);
        Coupon coupon = findById(id);
        String newCode = normalizeCode(form.code());
        if (!newCode.equals(coupon.getCode()) && couponRepository.countByCode(newCode) > 0) {
            throw new BusinessException("券码已存在");
        }
        applyForm(coupon, form, newCode);
        return couponRepository.save(coupon);
    }

    /** E11：复制新建。条款整份抄过来、发行量归零，券码自动接 -C2/-C3 直到不重码 */
    @Override
    public Coupon copy(UUID id) {
        Coupon source = findById(id);
        Coupon copy = new Coupon();
        copy.setCode(nextCopyCode(source.getCode()));
        copy.setName(copyName(source.getName()));
        copy.setType(source.getType());
        copy.setThreshold(source.getThreshold());
        copy.setAmount(source.getAmount());
        copy.setDiscountRate(source.getDiscountRate());
        copy.setMaxDiscount(source.getMaxDiscount());
        copy.setTotal(source.getTotal());
        copy.setPerUserLimit(source.getPerUserLimit());
        copy.setStartTime(source.getStartTime());
        copy.setEndTime(source.getEndTime());
        copy.setValidDays(source.getValidDays());
        copy.setValidEndTime(source.getValidEndTime());
        copy.setScope(source.getScope());
        copy.setCategoryId(source.getCategoryId());
        copy.setCategoryIds(source.getCategoryIds());
        copy.setProductIds(source.getProductIds());
        copy.setLadderRule(source.getLadderRule());
        copy.setPerUserDailyLimit(source.getPerUserDailyLimit());
        copy.setNewUserOnly(source.getNewUserOnly());
        copy.setAllowTransfer(source.getAllowTransfer());
        copy.setMemberOnly(source.getMemberOnly());
        copy.setDisablePolicy(source.getDisablePolicy());
        copy.setTriggerScene(source.getTriggerScene());
        copy.setGrantMinAmount(source.getGrantMinAmount());
        copy.setIssued(0);
        // 副本一律先停用：直接上架会和原券同时在领券中心出现，条款没改完就发出去了
        copy.setStatus(Coupon.STATUS_INACTIVE);
        Coupon saved = couponRepository.save(copy);
        log.info("券模板复制 {} -> {}（副本默认停用）", source.getCode(), saved.getCode());
        return saved;
    }

    @Override
    public boolean updateStatus(UUID id, String status) {
        return updateStatus(id, status, null) >= 0;
    }

    /**
     * E12：上下架同时决定已领券的去留。policy=void 时用条件 UPDATE 把未使用的券批量落 expired，
     * 已核销的券不动（它们是历史成交凭证）。
     */
    @Override
    public int updateStatus(UUID id, String status, String disablePolicy) {
        Coupon coupon = findById(id);
        String target = Coupon.STATUS_ACTIVE.equals(status) ? Coupon.STATUS_ACTIVE : Coupon.STATUS_INACTIVE;
        String policy = Coupon.DISABLE_VOID.equals(disablePolicy) ? Coupon.DISABLE_VOID
                : (disablePolicy == null || disablePolicy.isBlank()
                ? coupon.getDisablePolicy() : Coupon.DISABLE_KEEP);
        coupon.setStatus(target);
        coupon.setDisablePolicy(policy);
        couponRepository.save(coupon);
        if (!Coupon.STATUS_ACTIVE.equals(target) && Coupon.DISABLE_VOID.equals(policy)) {
            int voided = userCouponRepository.voidByCoupon(id);
            log.info("券模板 {} 停用并作废未使用券 {} 张", coupon.getCode(), voided);
            return voided;
        }
        return 0;
    }

    @Override
    public void delete(UUID id) {
        couponRepository.deleteById(id);
    }

    @Override
    public UserCoupon issue(UUID couponId, UUID userId) {
        Coupon coupon = findById(couponId);
        if (userId == null) {
            throw new BusinessException("请选择用户");
        }
        return grant(coupon, userId, LocalDateTime.now(), UserCoupon.SOURCE_ISSUE, null, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CouponStatView> statsForAdmin() {
        Map<UUID, long[]> counts = new LinkedHashMap<>();
        Map<UUID, BigDecimal> amounts = new LinkedHashMap<>();
        for (Object[] row : userCouponRepository.aggregateByCoupon()) {
            UUID couponId = (UUID) row[0];
            counts.put(couponId, new long[]{((Number) row[1]).longValue(), ((Number) row[2]).longValue()});
            amounts.put(couponId, row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString()));
        }
        List<CouponStatView> views = new ArrayList<>();
        for (Coupon coupon : couponRepository.findAllByOrderByCreatedAtDesc()) {
            long[] pair = counts.getOrDefault(coupon.getId(), new long[]{0, 0});
            views.add(new CouponStatView(coupon.getId(), coupon.getCode(), coupon.getName(),
                    coupon.getTotal(), coupon.getIssued(), pair[0], pair[1],
                    CouponPolicy.consumeRate(pair[0], pair[1]),
                    amounts.getOrDefault(coupon.getId(), BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)));
        }
        return views;
    }

    /* ---------- 前台 ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findReceivable(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.findClaimable(now).stream()
                .filter(c -> c.getTotal() == 0 || c.getIssued() < c.getTotal())
                .filter(c -> claimBlockReason(c, userId, now) == null)
                .toList();
    }

    /** E10：领券中心保留不可领的券，前端按 claimBlockReason 打「已领过 / 今日已领」标记 */
    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findCenter(UUID userId, UUID categoryId) {
        LocalDateTime now = LocalDateTime.now();
        List<Coupon> list = couponRepository.findClaimable(now);
        if (categoryId == null) {
            return list;
        }
        // 分类树只有两层：向下补一级让「绑在子类」的券出现在大类筛选里，向上补一级让「绑在大类」的券出现在子类里
        Set<UUID> match = new LinkedHashSet<>(categoryRepository.idsWithChildren(categoryId));
        categoryRepository.findById(categoryId)
                .map(Category::getParentId)
                .filter(Objects::nonNull)
                .ifPresent(match::add);
        return list.stream().filter(c -> coversCategory(c, match)).toList();
    }

    /**
     * E04/E05/E10/E18 的领取资格判定集中在这里：前台列表标记、后台预览与真正领取走同一份逻辑，
     * 不会出现页面说能领、点下去报错的两套口径
     */
    @Override
    @Transactional(readOnly = true)
    public String claimBlockReason(Coupon coupon, UUID userId, LocalDateTime now) {
        if (!Coupon.STATUS_ACTIVE.equals(coupon.getStatus())) {
            return "已下架";
        }
        if (coupon.getStartTime() != null && coupon.getStartTime().isAfter(now)) {
            return "未开始";
        }
        if (coupon.getEndTime() != null && coupon.getEndTime().isBefore(now)) {
            return "活动已结束";
        }
        if (coupon.getTotal() != null && coupon.getTotal() > 0 && coupon.getIssued() >= coupon.getTotal()) {
            return "已领完";
        }
        if (userId == null) {
            return null;
        }
        int limit = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
        long owned = userCouponRepository.countByUserIdAndCouponId(userId, coupon.getId());
        if (owned >= limit) {
            return "已领取";
        }
        int daily = coupon.getPerUserDailyLimit() == null ? 0 : coupon.getPerUserDailyLimit();
        if (daily > 0 && userCouponRepository.countByUserIdAndCouponIdOnDay(
                userId, coupon.getId(), now.toLocalDate()) >= daily) {
            return "今日已领";
        }
        if (Boolean.TRUE.equals(coupon.getNewUserOnly()) && !isNewCustomer(userId)) {
            return "新客专享";
        }
        if (Boolean.TRUE.equals(coupon.getMemberOnly()) && !isMember(userId)) {
            return "会员专享";
        }
        return null;
    }

    @Override
    public UserCoupon claim(UUID userId, UUID couponId) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = findById(couponId);
        if (!Coupon.SCENE_CLAIM.equals(coupon.getTriggerScene())) {
            throw new BusinessException("该券不支持主动领取");
        }
        String blocked = claimBlockReason(coupon, userId, now);
        if (blocked != null) {
            throw new BusinessException(blockedMessage(blocked));
        }
        return grant(coupon, userId, now, UserCoupon.SOURCE_CLAIM, null, true);
    }

    private String blockedMessage(String blocked) {
        return switch (blocked) {
            case "已下架" -> "该优惠券已下架";
            case "未开始" -> "活动还未开始";
            case "活动已结束" -> "活动已结束";
            case "已领完" -> "优惠券已领完";
            case "已领取" -> "已达每人限领数量";
            case "今日已领" -> "今天已经领过，明天再来";
            case "新客专享" -> "该券仅限新客领取，你已有成交订单";
            case "会员专享" -> "该券仅限会员领取";
            default -> "该优惠券当前不可领";
        };
    }

    /**
     * 发券：先抢库存（条件更新，抢不到即领完），再落持券快照。
     *
     * <p>领取路径认领取窗口，场景发券只认「启用 + 有余量」；每日限领的券用 saveAndFlush
     * 让条件唯一索引当场报错，好翻译成「明天再来」而不是提交期异常。
     */
    private UserCoupon grant(Coupon coupon, UUID userId, LocalDateTime now, String source,
                             UUID sourceRef, boolean claimPath) {
        boolean dailyLimited = claimPath && coupon.getPerUserDailyLimit() != null
                && coupon.getPerUserDailyLimit() > 0;
        int reserved = claimPath
                ? couponRepository.reserveOne(coupon.getId(), now)
                : couponRepository.reserveForScene(coupon.getId());
        if (reserved == 0) {
            throw new BusinessException("优惠券已领完");
        }
        UserCoupon held = snapshot(coupon, userId, now, source, sourceRef, dailyLimited);
        try {
            return dailyLimited ? userCouponRepository.saveAndFlush(held) : userCouponRepository.save(held);
        } catch (DataIntegrityViolationException e) {
            couponRepository.releaseOne(coupon.getId());
            throw new BusinessException(dailyLimited
                    ? "今天已经领过，明天再来" : "该券已发放过，请勿重复领取");
        } catch (RuntimeException e) {
            couponRepository.releaseOne(coupon.getId());
            throw e;
        }
    }

    /** 条款快照：模板后续改动不回溯已发出的券 */
    private UserCoupon snapshot(Coupon coupon, UUID userId, LocalDateTime now, String source,
                                UUID sourceRef, boolean dailyLimited) {
        UserCoupon held = new UserCoupon();
        held.setUserId(userId);
        held.setCouponId(coupon.getId());
        held.setCode(coupon.getCode());
        held.setName(coupon.getName());
        held.setType(coupon.getType());
        held.setThreshold(coupon.getThreshold());
        held.setAmount(coupon.getAmount());
        held.setDiscountRate(coupon.getDiscountRate());
        held.setMaxDiscount(coupon.getMaxDiscount());
        held.setScope(coupon.getScope());
        held.setCategoryId(coupon.getCategoryId());
        held.setCategoryIds(coupon.getCategoryIds());
        held.setProductIds(coupon.getProductIds());
        held.setLadderRule(coupon.getLadderRule());
        held.setAllowTransfer(Boolean.TRUE.equals(coupon.getAllowTransfer()));
        held.setDailyLimited(dailyLimited);
        held.setClaimDate(dailyLimited ? now.toLocalDate() : null);
        held.setStatus(UserCoupon.STATUS_UNUSED);
        held.setSource(source);
        held.setSourceRef(sourceRef);
        held.setExpireAt(coupon.expireAt(now));
        held.setReceivedAt(now);
        return held;
    }

    @Override
    public List<UserCoupon> findMine(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        // 先回收超时未领的转赠，再落过期状态，两步都是条件 UPDATE，重复调用无副作用
        userCouponRepository.releaseStaleTransfers(now.minusHours(TRANSFER_HOLD_HOURS));
        userCouponRepository.markExpired(userId, now);
        return userCouponRepository.findByUserIdOrderByExpireAtAsc(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserCoupon> findUsable(UUID userId) {
        return userCouponRepository.findUsable(userId, LocalDateTime.now());
    }

    /**
     * E06：提醒窗口按自然日算，与 CouponPolicy.expireReminder 的「剩几天」同一口径，
     * 否则会出现提醒条里有券、卡片上却不写提醒的错位。
     * 这里不能标 readOnly：先要落一次懒过期，免得把已到期的券当成「即将到期」提醒。
     */
    @Override
    public List<UserCoupon> expiringSoon(UUID userId, int windowDays) {
        LocalDateTime now = LocalDateTime.now();
        int window = windowDays <= 0 ? CouponPolicy.REMIND_BEFORE_DAYS : Math.min(windowDays, 30);
        LocalDateTime until = now.toLocalDate().plusDays(window + 1L).atStartOfDay();
        userCouponRepository.markExpired(userId, now);
        return userCouponRepository.findExpiringSoon(userId, now, until);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal discountOf(UserCoupon coupon, List<CouponPolicy.Line> lines) {
        if (coupon == null || coupon.isExpiredAt(LocalDateTime.now())) {
            return null;
        }
        return CouponPolicy.discountOf(coupon, baseAmountOf(coupon, lines));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal baseAmountOf(UserCoupon coupon, List<CouponPolicy.Line> lines) {
        return CouponPolicy.baseAmount(coupon, scopeCategoryIds(coupon), lines);
    }

    /** 分类券覆盖每个绑定分类及其直接子分类（E02 多选时取并集） */
    private List<UUID> scopeCategoryIds(UserCoupon coupon) {
        List<UUID> bound = CouponPolicy.boundCategoryIds(coupon);
        if (bound.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (UUID categoryId : bound) {
            ids.addAll(categoryRepository.idsWithChildren(categoryId));
        }
        return List.copyOf(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal requireUsableForOrder(UUID userId, UUID userCouponId, List<CouponPolicy.Line> lines) {
        UserCoupon held = userCouponRepository.findByIdAndUserId(userCouponId, userId)
                .orElseThrow(() -> new BusinessException("优惠券不存在或不属于当前账号"));
        List<UUID> scopeIds = scopeCategoryIds(held);
        // 状态不对（已用/转赠中/过期）一律 409：这类失败重试或换券就能继续下单
        if (!UserCoupon.STATUS_UNUSED.equals(held.getStatus())) {
            throw new BusinessException(409, CouponPolicy.unavailableReason(held, scopeIds, lines));
        }
        BigDecimal discount = CouponPolicy.discountOf(held, CouponPolicy.baseAmount(held, scopeIds, lines));
        if (discount == null) {
            throw new BusinessException(CouponPolicy.unavailableReason(held, scopeIds, lines));
        }
        return discount;
    }

    @Override
    public boolean consume(UUID userCouponId, UUID userId, UUID orderId) {
        return userCouponRepository.consume(userCouponId, userId, orderId, LocalDateTime.now()) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public String consumeFailureReason(UUID userCouponId, UUID userId) {
        UserCoupon held = userCouponRepository.findById(userCouponId).orElse(null);
        if (held == null) {
            return "优惠券不存在";
        }
        if (!held.getUserId().equals(userId)) {
            return "该优惠券不属于当前账号";
        }
        if (UserCoupon.STATUS_USED.equals(held.getStatus())) {
            return "该优惠券已被核销"
                    + (held.getOrderId() == null ? "" : "（订单 " + held.getOrderId() + "）")
                    + "，刷新后可换一张券重新提交";
        }
        if (UserCoupon.STATUS_GIFTING.equals(held.getStatus())) {
            return "该优惠券正在转赠中，撤销转赠后可继续使用";
        }
        if (UserCoupon.STATUS_EXPIRED.equals(held.getStatus()) || held.isExpiredAt(LocalDateTime.now())) {
            return "该优惠券已过有效期";
        }
        return "该优惠券状态已变更，刷新券包后可重试";
    }

    @Override
    public boolean releaseByOrder(UUID orderId) {
        return userCouponRepository.releaseByOrder(orderId) > 0;
    }

    /* ---------- E20 适用范围文案 ---------- */

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> scopeTexts(List<Coupon> coupons) {
        if (coupons == null || coupons.isEmpty()) {
            return Map.of();
        }
        Set<UUID> categoryIds = new LinkedHashSet<>();
        Set<UUID> productIds = new LinkedHashSet<>();
        for (Coupon coupon : coupons) {
            categoryIds.addAll(CouponPolicy.parseIds(coupon.getCategoryIds()));
            if (coupon.getCategoryId() != null) {
                categoryIds.add(coupon.getCategoryId());
            }
            productIds.addAll(CouponPolicy.parseIds(coupon.getProductIds()));
        }
        Map<UUID, String> categoryNames = categoryNameMap(categoryIds);
        Map<UUID, String> productNames = productNameMap(productIds);
        Map<UUID, String> texts = new LinkedHashMap<>();
        for (Coupon coupon : coupons) {
            List<UUID> bound = CouponPolicy.parseIds(coupon.getCategoryIds());
            if (bound.isEmpty() && coupon.getCategoryId() != null) {
                bound = List.of(coupon.getCategoryId());
            }
            texts.put(coupon.getId(), CouponPolicy.scopeText(bound,
                    aliveCount(CouponPolicy.parseIds(coupon.getProductIds()), productNames),
                    names(bound, categoryNames),
                    Boolean.TRUE.equals(coupon.getNewUserOnly()), Boolean.TRUE.equals(coupon.getMemberOnly())));
        }
        return texts;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> heldScopeTexts(List<UserCoupon> helds) {
        if (helds == null || helds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> categoryIds = new LinkedHashSet<>();
        Set<UUID> productIds = new LinkedHashSet<>();
        for (UserCoupon held : helds) {
            categoryIds.addAll(CouponPolicy.boundCategoryIds(held));
            productIds.addAll(CouponPolicy.boundProductIds(held));
        }
        Map<UUID, String> categoryNames = categoryNameMap(categoryIds);
        Map<UUID, String> productNames = productNameMap(productIds);
        Map<UUID, String> texts = new LinkedHashMap<>();
        for (UserCoupon held : helds) {
            List<UUID> bound = CouponPolicy.boundCategoryIds(held);
            texts.put(held.getId(), CouponPolicy.scopeText(bound,
                    aliveCount(CouponPolicy.boundProductIds(held), productNames),
                    names(bound, categoryNames), false, false));
        }
        return texts;
    }

    private Map<UUID, String> categoryNameMap(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        categoryRepository.findAllById(ids).forEach(c -> names.put(c.getId(), c.getName()));
        return names;
    }

    private Map<UUID, String> productNameMap(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new LinkedHashMap<>();
        productRepository.findAllById(ids)
                .forEach(p -> names.put(p.getId(), p.getName()));
        return names;
    }

    /** 白名单里已下架的商品不算进文案条数，免得写着 5 款实际只剩 3 款可买 */
    private int aliveCount(List<UUID> ids, Map<UUID, String> dictionary) {
        return (int) ids.stream().filter(dictionary::containsKey).count();
    }

    private List<String> names(List<UUID> ids, Map<UUID, String> dictionary) {
        List<String> names = new ArrayList<>();
        for (UUID id : ids) {
            String name = dictionary.get(id);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /* ---------- E07 转赠 ---------- */

    @Override
    public UserCoupon startTransfer(UUID userId, UUID userCouponId, String receiver) {
        UUID receiverId = null;
        if (receiver != null && !receiver.isBlank()) {
            User target = resolveUser(receiver.trim());
            if (target == null) {
                throw new BusinessException("没有找到这位收礼人，请确认用户名或手机号");
            }
            if (target.getId().equals(userId)) {
                throw new BusinessException("不能把券转赠给自己");
            }
            receiverId = target.getId();
        }
        UserCoupon held = requireOwned(userId, userCouponId);
        if (!Boolean.TRUE.equals(held.getAllowTransfer())) {
            throw new BusinessException("该优惠券不支持转赠");
        }
        String token = newToken();
        int locked = userCouponRepository.lockForTransfer(userCouponId, userId, receiverId, token, LocalDateTime.now());
        if (locked == 0) {
            throw new BusinessException(409, "这张券当前不能转赠（已使用、已过期或正在转赠中），刷新后可重试");
        }
        return requireOwned(userId, userCouponId);
    }

    @Override
    public UserCoupon cancelTransfer(UUID userId, UUID userCouponId) {
        if (userCouponRepository.cancelTransfer(userCouponId, userId) == 0) {
            throw new BusinessException("没有正在转赠的券，无需撤销");
        }
        return requireOwned(userId, userCouponId);
    }

    /**
     * 凭码领取：条件更新一次改完「归属 + 状态」，两个人抢同一个码只有一行会被更新，
     * 另一个人拿到 0 行直接被告知「手慢了」
     */
    @Override
    public UserCoupon acceptTransfer(UUID userId, String token) {
        String normalized = token == null ? "" : token.trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new BusinessException("请填写转赠码");
        }
        UserCoupon pending = userCouponRepository.findPendingTransfer(normalized)
                .orElseThrow(() -> new BusinessException("转赠码无效或已被领取"));
        if (pending.getUserId().equals(userId)) {
            throw new BusinessException("这是你自己转出的券，撤销转赠后即可继续使用");
        }
        if (pending.getTransferToUserId() != null && !pending.getTransferToUserId().equals(userId)) {
            throw new BusinessException("这份转赠是指定送给别人的");
        }
        int taken = userCouponRepository.acceptTransfer(normalized, userId, LocalDateTime.now());
        if (taken == 0) {
            throw new BusinessException(409, "手慢了，这份转赠刚被领取");
        }
        log.info("券转赠完成 token={} from={} to={}", normalized, pending.getUserId(), userId);
        return userCouponRepository.findById(pending.getId())
                .orElseThrow(() -> new BusinessException("转赠的券已不存在"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserCoupon> incomingTransfers(UUID userId) {
        return userCouponRepository.findIncomingTransfers(userId);
    }

    private UserCoupon requireOwned(UUID userId, UUID userCouponId) {
        return userCouponRepository.findByIdAndUserId(userCouponId, userId)
                .orElseThrow(() -> new BusinessException("优惠券不存在或不属于当前账号"));
    }

    private User resolveUser(String keyword) {
        return userRepository.findByUsername(keyword)
                .orElseGet(() -> userRepository.findByPhone(keyword).orElse(null));
    }

    private String newToken() {
        StringBuilder builder = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            builder.append(TOKEN_ALPHABET[RANDOM.nextInt(TOKEN_ALPHABET.length)]);
        }
        return builder.toString();
    }

    /* ---------- E09 组合建议 ---------- */

    /**
     * 券与积分并不硬性互斥，但积分上限按「券抵扣后的金额」算，
     * 所以三种方案都要算一遍才能告诉用户哪种更划算
     */
    @Override
    @Transactional(readOnly = true)
    public CouponViews.ComboPlan comboPlan(UUID userId, List<CouponPolicy.Line> lines, UUID userCouponId) {
        BigDecimal base = lines == null ? BigDecimal.ZERO : lines.stream()
                .map(CouponPolicy.Line::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        List<UserCoupon> usable = findUsable(userId);
        UserCoupon chosen = null;
        BigDecimal chosenDiscount = null;
        if (userCouponId != null) {
            chosen = usable.stream().filter(c -> userCouponId.equals(c.getId())).findFirst().orElse(null);
            chosenDiscount = chosen == null ? null : CouponPolicy.discountOf(chosen, baseAmountOf(chosen, lines));
        }
        if (chosen == null || chosenDiscount == null) {
            for (UserCoupon candidate : usable) {
                BigDecimal discount = CouponPolicy.discountOf(candidate, baseAmountOf(candidate, lines));
                if (discount != null && (chosenDiscount == null || discount.compareTo(chosenDiscount) > 0)) {
                    chosen = candidate;
                    chosenDiscount = discount;
                }
            }
        }
        int points = userRepository.findById(userId)
                .map(user -> user.getPoints() == null ? 0 : user.getPoints()).orElse(0);
        List<CampaignPolicy.ComboOption> options = CampaignPolicy.comboOptions(base, chosenDiscount, points);
        return new CouponViews.ComboPlan(base, chosenDiscount, chosen == null ? null : chosen.getName(),
                points, CampaignPolicy.comboSummary(options), CouponViews.ComboView.of(options));
    }

    /* ---------- 场景发券 ---------- */

    /**
     * E15：返券走独立事务。返券失败（库存不足、并发重复）绝不能把已经成功的支付回滚掉，
     * 因此这里只吞掉业务异常并记日志。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rebateAfterPaid(UUID orderId, UUID userId, BigDecimal payAmount) {
        if (orderId == null || userId == null) {
            return 0;
        }
        BigDecimal paid = payAmount == null ? BigDecimal.ZERO : payAmount;
        int granted = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Coupon coupon : couponRepository.findByScene(Coupon.SCENE_AFTER_PAY)) {
            BigDecimal min = coupon.getGrantMinAmount() == null ? BigDecimal.ZERO : coupon.getGrantMinAmount();
            if (paid.compareTo(min) < 0) {
                continue;
            }
            if (userCouponRepository.existsByCouponIdAndSourceRef(coupon.getId(), orderId)) {
                continue;
            }
            try {
                if (grant(coupon, userId, now, UserCoupon.SOURCE_REBATE, orderId, false) != null) {
                    granted++;
                }
            } catch (RuntimeException e) {
                log.warn("订单返券未发放 order={} coupon={} : {}", orderId, coupon.getCode(), e.getMessage());
            }
        }
        if (granted > 0) {
            log.info("下单返券 order={} user={} 发出 {} 张", orderId, userId, granted);
        }
        return granted;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UserCoupon grantByScene(UUID userId, UUID couponId, String source, UUID sourceRef) {
        if (userId == null || couponId == null) {
            return null;
        }
        Coupon coupon = couponRepository.findById(couponId).orElse(null);
        if (coupon == null || !Coupon.STATUS_ACTIVE.equals(coupon.getStatus())) {
            log.warn("场景发券跳过：模板 {} 不存在或已下架", couponId);
            return null;
        }
        if (sourceRef != null && userCouponRepository.existsByCouponIdAndSourceRef(couponId, sourceRef)) {
            return null;
        }
        try {
            return grant(coupon, userId, LocalDateTime.now(), source, sourceRef, false);
        } catch (RuntimeException e) {
            log.warn("场景发券失败 user={} coupon={} : {}", userId, coupon.getCode(), e.getMessage());
            return null;
        }
    }

    /* ---------- 判定辅助 ---------- */

    @Override
    @Transactional(readOnly = true)
    public boolean isNewCustomer(UUID userId) {
        if (userId == null) {
            return true;
        }
        return userCouponRepository.countDealOrders(userId, OrderStatus.DEAL_STATUSES) == 0;
    }

    /** 分类券是否命中领券中心的筛选（含上下级）；未绑分类的分类券按「不限」处理 */
    private boolean coversCategory(Coupon coupon, Set<UUID> matchCategories) {
        if (!Coupon.SCOPE_CATEGORY.equals(coupon.getScope())) {
            return true;
        }
        List<UUID> bound = CouponPolicy.parseIds(coupon.getCategoryIds());
        if (bound.isEmpty() && coupon.getCategoryId() != null) {
            bound = List.of(coupon.getCategoryId());
        }
        if (bound.isEmpty()) {
            return true;
        }
        return bound.stream().anyMatch(matchCategories::contains);
    }

    /** E18：会员专享判定按策略类的折扣率走，vip/svip 都算会员 */
    private boolean isMember(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        return user != null && CampaignPolicy.memberRate(user.getMemberLevel()).compareTo(BigDecimal.ONE) < 0;
    }

    /* ---------- 校验与写入 ---------- */

    private void validate(CouponDtos.Form form) {
        boolean hasLadder = form.ladderRule() != null && !form.ladderRule().isBlank();
        if (hasLadder && CampaignPolicy.parseLadder(form.ladderRule()).isEmpty()) {
            throw new BusinessException("满减阶梯格式不正确，应形如 199:20,399:60");
        }
        if (!hasLadder && Coupon.TYPE_CASH.equals(form.type())
                && (form.amount() == null || form.amount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("满减券必须填写满减金额");
        }
        if (Coupon.TYPE_DISCOUNT.equals(form.type()) && form.discountRate() == null) {
            throw new BusinessException("折扣券必须填写折扣率");
        }
        List<UUID> categoryIds = distinct(form.categoryIds(), form.categoryId());
        if (Coupon.SCOPE_CATEGORY.equals(form.scope()) && categoryIds.isEmpty()) {
            throw new BusinessException("指定分类券必须选择分类");
        }
        if (!categoryIds.isEmpty() && categoryRepository.findAllById(categoryIds).size() != categoryIds.size()) {
            throw new BusinessException("所选分类不存在");
        }
        List<UUID> productIds = distinct(form.productIds(), null);
        if (!productIds.isEmpty() && productRepository.findAllById(productIds).size() != productIds.size()) {
            throw new BusinessException("商品白名单里存在不存在的商品");
        }
        if (form.endTime() != null && form.startTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("领取截止不能早于开始时间");
        }
        if (form.validDays() != null && form.validDays() > 0 && form.validEndTime() != null) {
            throw new BusinessException("有效天数与固定到期时间只能填一个");
        }
    }

    private void applyForm(Coupon coupon, CouponDtos.Form form, String code) {
        List<UUID> categoryIds = distinct(form.categoryIds(), form.categoryId());
        List<UUID> productIds = distinct(form.productIds(), null);
        boolean categoryScope = Coupon.SCOPE_CATEGORY.equals(form.scope());
        coupon.setCode(code);
        coupon.setName(form.name().trim());
        coupon.setType(form.type());
        coupon.setThreshold(form.threshold());
        coupon.setAmount(Coupon.TYPE_CASH.equals(form.type()) ? form.amount() : null);
        coupon.setDiscountRate(Coupon.TYPE_DISCOUNT.equals(form.type()) ? form.discountRate() : null);
        coupon.setMaxDiscount(Coupon.TYPE_DISCOUNT.equals(form.type()) ? form.maxDiscount() : null);
        coupon.setTotal(form.total());
        coupon.setPerUserLimit(form.perUserLimit());
        coupon.setStartTime(form.startTime());
        coupon.setEndTime(form.endTime());
        coupon.setValidDays(form.validDays());
        coupon.setValidEndTime(form.validEndTime());
        coupon.setScope(form.scope());
        coupon.setCategoryId(categoryScope ? categoryIds.get(0) : null);
        coupon.setCategoryIds(categoryScope ? CouponPolicy.joinIds(categoryIds) : null);
        coupon.setProductIds(productIds.isEmpty() ? null : CouponPolicy.joinIds(productIds));
        coupon.setLadderRule(blankToNull(form.ladderRule()));
        coupon.setPerUserDailyLimit(form.perUserDailyLimit() == null || form.perUserDailyLimit() < 0
                ? 0 : form.perUserDailyLimit());
        coupon.setNewUserOnly(Boolean.TRUE.equals(form.newUserOnly()));
        coupon.setAllowTransfer(Boolean.TRUE.equals(form.allowTransfer()));
        coupon.setMemberOnly(Boolean.TRUE.equals(form.memberOnly()));
        coupon.setDisablePolicy(Coupon.DISABLE_VOID.equals(form.disablePolicy())
                ? Coupon.DISABLE_VOID : Coupon.DISABLE_KEEP);
        coupon.setTriggerScene(normalizeScene(form.triggerScene()));
        coupon.setGrantMinAmount(form.grantMinAmount() == null ? BigDecimal.ZERO : form.grantMinAmount());
        coupon.setStatus(form.status() == null || form.status().isBlank() ? Coupon.STATUS_ACTIVE : form.status());
        // 阶梯券以最低档作为门槛：后台只填阶梯却漏填 threshold 时不会退化成无门槛券
        if (coupon.getLadderRule() != null) {
            BigDecimal lowest = CampaignPolicy.lowestThreshold(coupon.getLadderRule());
            BigDecimal current = coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
            if (lowest.signum() > 0 && current.compareTo(lowest) < 0) {
                coupon.setThreshold(lowest);
            }
        }
    }

    private String normalizeScene(String scene) {
        if (scene == null || scene.isBlank()) {
            return Coupon.SCENE_CLAIM;
        }
        return switch (scene) {
            case Coupon.SCENE_ADMIN, Coupon.SCENE_AFTER_PAY, Coupon.SCENE_INVITE, Coupon.SCENE_CLAIM -> scene;
            default -> Coupon.SCENE_CLAIM;
        };
    }

    /** 多选与单选合并去重，保持录入顺序：第一个分类即券的主分类 */
    private List<UUID> distinct(List<UUID> ids, UUID extra) {
        Set<UUID> merged = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream().filter(Objects::nonNull).forEach(merged::add);
        }
        if (extra != null) {
            merged.add(extra);
        }
        return List.copyOf(merged);
    }

    private String normalizeCode(String code) {
        return code.trim().toUpperCase();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 副本券码：主码不变，接 -C2/-C3…，受券码 30 位长度约束 */
    private String nextCopyCode(String sourceCode) {
        String base = sourceCode.replaceAll("-C\\d+$", "");
        for (int seq = 2; seq < 60; seq++) {
            String candidate = base + "-C" + seq;
            if (candidate.length() <= 30 && couponRepository.countByCode(candidate) == 0) {
                return candidate;
            }
        }
        throw new BusinessException("副本券码已用尽，请手动指定新券码");
    }

    private String copyName(String name) {
        String stripped = name.endsWith("（副本）") ? name.substring(0, name.length() - 4) : name;
        String candidate = stripped + "（副本）";
        return candidate.length() <= 50 ? candidate : candidate.substring(0, 50);
    }
}
