package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.CouponDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.CouponRepository;
import org.liuym.flowerv1springboot.repository.UserCouponRepository;
import org.liuym.flowerv1springboot.service.CouponService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CouponServiceImpl implements CouponService {

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserCouponRepository userCouponRepository;

    @Autowired
    private CategoryRepository categoryRepository;

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
    public Coupon createByForm(CouponDtos.Form form) {
        validate(form);
        if (couponRepository.findByCode(form.code().trim().toUpperCase()).isPresent()) {
            throw new BusinessException("券码已存在");
        }
        Coupon coupon = new Coupon();
        applyForm(coupon, form);
        coupon.setIssued(0);
        return couponRepository.save(coupon);
    }

    @Override
    public Coupon updateByForm(UUID id, CouponDtos.Form form) {
        validate(form);
        Coupon coupon = findById(id);
        String newCode = form.code().trim().toUpperCase();
        couponRepository.findByCode(newCode)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new BusinessException("券码已存在");
                });
        applyForm(coupon, form);
        return couponRepository.save(coupon);
    }

    @Override
    public boolean updateStatus(UUID id, String status) {
        Coupon coupon = findById(id);
        coupon.setStatus(status);
        couponRepository.save(coupon);
        return true;
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
        return grant(coupon, userId, LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Coupon> findReceivable(UUID userId) {
        LocalDateTime now = LocalDateTime.now();
        return couponRepository.findByStatusOrderByCreatedAtDesc(Coupon.STATUS_ACTIVE).stream()
                .filter(c -> c.getStartTime() == null || !c.getStartTime().isAfter(now))
                .filter(c -> c.getEndTime() == null || !c.getEndTime().isBefore(now))
                .filter(c -> c.getTotal() == 0 || c.getIssued() < c.getTotal())
                .filter(c -> userId == null || userCouponRepository.countByUserIdAndCouponId(userId, c.getId()) < c.getPerUserLimit())
                .toList();
    }

    @Override
    public UserCoupon claim(UUID userId, UUID couponId) {
        LocalDateTime now = LocalDateTime.now();
        Coupon coupon = findById(couponId);
        if (!Coupon.STATUS_ACTIVE.equals(coupon.getStatus())) {
            throw new BusinessException("该优惠券已下架");
        }
        if (coupon.getStartTime() != null && coupon.getStartTime().isAfter(now)) {
            throw new BusinessException("活动还未开始");
        }
        if (coupon.getEndTime() != null && coupon.getEndTime().isBefore(now)) {
            throw new BusinessException("活动已结束");
        }
        int limit = coupon.getPerUserLimit() == null ? 1 : coupon.getPerUserLimit();
        if (userCouponRepository.countByUserIdAndCouponId(userId, couponId) >= limit) {
            throw new BusinessException("已达每人限领数量");
        }
        return grant(coupon, userId, now);
    }

    /**
     * 发券：先抢库存（条件更新，抢不到即领完），再落持券快照；
     * 快照失败则回退库存，发行数不会虚高
     */
    private UserCoupon grant(Coupon coupon, UUID userId, LocalDateTime now) {
        if (couponRepository.reserveOne(coupon.getId(), now) == 0) {
            throw new BusinessException("优惠券已领完");
        }
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
        held.setStatus(UserCoupon.STATUS_UNUSED);
        held.setExpireAt(coupon.expireAt(now));
        held.setReceivedAt(now);
        try {
            return userCouponRepository.save(held);
        } catch (RuntimeException e) {
            couponRepository.releaseOne(coupon.getId());
            throw e;
        }
    }

    @Override
    public List<UserCoupon> findMine(UUID userId) {
        userCouponRepository.markExpired(userId, LocalDateTime.now());
        return userCouponRepository.findByUserIdOrderByExpireAtAsc(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserCoupon> findUsable(UUID userId) {
        return userCouponRepository.findUsable(userId, LocalDateTime.now());
    }

    @Override
    public BigDecimal discountOf(UserCoupon coupon, List<CouponPolicy.Line> lines) {
        if (coupon == null || coupon.isExpiredAt(LocalDateTime.now())) {
            return null;
        }
        return CouponPolicy.discountOf(coupon, CouponPolicy.baseAmount(coupon, lines));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal requireUsableForOrder(UUID userId, UUID userCouponId, List<CouponPolicy.Line> lines) {
        UserCoupon held = userCouponRepository.findByIdAndUserId(userCouponId, userId)
                .orElseThrow(() -> new BusinessException("优惠券不存在"));
        if (!UserCoupon.STATUS_UNUSED.equals(held.getStatus())) {
            throw new BusinessException("该优惠券已使用");
        }
        if (held.isExpiredAt(LocalDateTime.now())) {
            throw new BusinessException("该优惠券已过期");
        }
        BigDecimal base = CouponPolicy.baseAmount(held, lines);
        if (!CouponPolicy.meetsThreshold(held, base)) {
            throw new BusinessException("未满「" + held.getName() + "」的使用门槛");
        }
        BigDecimal discount = CouponPolicy.discountOf(held, base);
        if (discount == null) {
            throw new BusinessException("该优惠券暂不可用");
        }
        return discount;
    }

    @Override
    public boolean consume(UUID userCouponId, UUID userId, UUID orderId) {
        return userCouponRepository.consume(userCouponId, userId, orderId, LocalDateTime.now()) > 0;
    }

    @Override
    public void releaseByOrder(UUID orderId) {
        userCouponRepository.releaseByOrder(orderId);
    }

    private void validate(CouponDtos.Form form) {
        if (Coupon.TYPE_CASH.equals(form.type())
                && (form.amount() == null || form.amount().compareTo(BigDecimal.ZERO) <= 0)) {
            throw new BusinessException("满减券必须填写满减金额");
        }
        if (Coupon.TYPE_DISCOUNT.equals(form.type()) && form.discountRate() == null) {
            throw new BusinessException("折扣券必须填写折扣率");
        }
        if (Coupon.SCOPE_CATEGORY.equals(form.scope())) {
            if (form.categoryId() == null) {
                throw new BusinessException("指定分类券必须选择分类");
            }
            categoryRepository.findById(form.categoryId())
                    .orElseThrow(() -> new BusinessException("所选分类不存在"));
        }
        if (form.endTime() != null && form.startTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("领取截止不能早于开始时间");
        }
        if (form.validDays() != null && form.validDays() > 0 && form.validEndTime() != null) {
            throw new BusinessException("有效天数与固定到期时间只能填一个");
        }
    }

    private void applyForm(Coupon coupon, CouponDtos.Form form) {
        coupon.setCode(form.code().trim().toUpperCase());
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
        coupon.setCategoryId(Coupon.SCOPE_CATEGORY.equals(form.scope()) ? form.categoryId() : null);
        coupon.setStatus(form.status() == null || form.status().isBlank() ? Coupon.STATUS_ACTIVE : form.status());
    }
}
