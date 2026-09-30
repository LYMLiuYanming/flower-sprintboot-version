package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.MarketingDtos;
import org.liuym.flowerv1springboot.model.FullReduction;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.PromotionSlot;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.VipMembership;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.FullReductionRepository;
import org.liuym.flowerv1springboot.repository.PromotionSlotRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.repository.VipMembershipRepository;
import org.liuym.flowerv1springboot.service.PromotionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PromotionServiceImpl implements PromotionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionServiceImpl.class);

    /** E18：一次最多续费 3 年，积分扣掉不可逆，别让误操作一次砸进去太多 */
    private static final int MAX_MEMBERSHIP_YEARS = 3;

    @Autowired
    private FullReductionRepository fullReductionRepository;

    @Autowired
    private PromotionSlotRepository promotionSlotRepository;

    @Autowired
    private VipMembershipRepository vipMembershipRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private UserRepository userRepository;

    /* ---------- E14 满减活动 ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<FullReduction> findAllReductions() {
        return fullReductionRepository.findAllByOrderByPriorityDescCreatedAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FullReduction> findActiveReductions() {
        return fullReductionRepository.findActive(LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public FullReduction findReduction(UUID id) {
        return fullReductionRepository.findById(id).orElseThrow(() -> new BusinessException("满减活动不存在"));
    }

    @Override
    public FullReduction saveReduction(UUID id, MarketingDtos.ReductionForm form) {
        validateReduction(form);
        FullReduction entity = id == null ? new FullReduction() : findReduction(id);
        entity.setName(form.name().trim());
        entity.setScope(form.scope());
        entity.setCategoryIds(FullReduction.SCOPE_CATEGORY.equals(form.scope())
                ? CouponPolicy.joinIds(distinct(form.categoryIds())) : null);
        entity.setLadderRule(CampaignPolicy.formatLadder(CampaignPolicy.parseLadder(form.ladderRule())));
        entity.setStackWithCoupon(Boolean.TRUE.equals(form.stackWithCoupon()));
        entity.setPriority(form.priority() == null || form.priority() < 0 ? 0 : form.priority());
        entity.setStartTime(form.startTime());
        entity.setEndTime(form.endTime());
        entity.setStatus(FullReduction.STATUS_INACTIVE.equals(form.status())
                ? FullReduction.STATUS_INACTIVE : FullReduction.STATUS_ACTIVE);
        return fullReductionRepository.save(entity);
    }

    @Override
    public boolean updateReductionStatus(UUID id, String status) {
        String target = FullReduction.STATUS_ACTIVE.equals(status)
                ? FullReduction.STATUS_ACTIVE : FullReduction.STATUS_INACTIVE;
        findReduction(id);
        // 条件更新：状态本来一致时返回 0，重复点开关不会互相覆盖
        return fullReductionRepository.updateStatus(id, target) > 0;
    }

    @Override
    public boolean removeReduction(UUID id) {
        if (!fullReductionRepository.existsById(id)) {
            return false;
        }
        fullReductionRepository.deleteById(id);
        return true;
    }

    private void validateReduction(MarketingDtos.ReductionForm form) {
        if (CampaignPolicy.parseLadder(form.ladderRule()).isEmpty()) {
            throw new BusinessException("满减阶梯格式不正确，应形如 199:20,399:60");
        }
        if (CampaignPolicy.formatLadder(CampaignPolicy.parseLadder(form.ladderRule())).isBlank()) {
            throw new BusinessException("满减阶梯至少要有有效的一档");
        }
        List<UUID> ids = distinct(form.categoryIds());
        if (FullReduction.SCOPE_CATEGORY.equals(form.scope()) && ids.isEmpty()) {
            throw new BusinessException("指定分类的满减活动必须选择分类");
        }
        if (!ids.isEmpty() && categoryRepository.findAllById(ids).size() != ids.size()) {
            throw new BusinessException("所选分类不存在");
        }
        if (form.endTime() != null && form.startTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("结束时间不能早于开始时间");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignPolicy.Tier hitReduction(FullReduction reduction, List<CouponPolicy.Line> lines) {
        if (reduction == null) {
            return null;
        }
        return CampaignPolicy.bestTier(reduction.getLadderRule(), reductionBase(reduction, lines));
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal reductionBase(FullReduction reduction, List<CouponPolicy.Line> lines) {
        if (lines == null || lines.isEmpty() || reduction == null) {
            return BigDecimal.ZERO;
        }
        List<UUID> bound = CouponPolicy.parseIds(reduction.getCategoryIds());
        if (!FullReduction.SCOPE_CATEGORY.equals(reduction.getScope()) || bound.isEmpty()) {
            return lines.stream().map(CouponPolicy.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        }
        Set<UUID> scopeIds = new LinkedHashSet<>();
        for (UUID categoryId : bound) {
            scopeIds.addAll(categoryRepository.idsWithChildren(categoryId));
        }
        BigDecimal base = BigDecimal.ZERO;
        for (CouponPolicy.Line line : lines) {
            if (line.categoryId() != null && scopeIds.contains(line.categoryId())) {
                base = base.add(line.amount());
            }
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FullReduction> bestReduction(List<CouponPolicy.Line> lines) {
        FullReduction best = null;
        BigDecimal bestAmount = BigDecimal.ZERO;
        for (FullReduction reduction : findActiveReductions()) {
            CampaignPolicy.Tier tier = hitReduction(reduction, lines);
            if (tier == null) {
                continue;
            }
            if (best == null || tier.reduce().compareTo(bestAmount) > 0
                    || (tier.reduce().compareTo(bestAmount) == 0 && reduction.getPriority() > best.getPriority())) {
                best = reduction;
                bestAmount = tier.reduce();
            }
        }
        return Optional.ofNullable(best);
    }

    /* ---------- E19 促销位 ---------- */

    @Override
    @Transactional(readOnly = true)
    public List<PromotionSlot> findAllSlots() {
        return promotionSlotRepository.findAllByOrderByPositionAscSortOrderAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PromotionSlot> findVisibleSlots(String position) {
        return promotionSlotRepository.findVisible(position, LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionSlot findSlot(UUID id) {
        return promotionSlotRepository.findById(id).orElseThrow(() -> new BusinessException("促销位不存在"));
    }

    @Override
    public PromotionSlot saveSlot(UUID id, MarketingDtos.SlotForm form) {
        if (form.startTime() != null && form.endTime() != null && form.endTime().isBefore(form.startTime())) {
            throw new BusinessException("投放结束时间不能早于开始时间");
        }
        PromotionSlot slot = id == null ? new PromotionSlot() : findSlot(id);
        slot.setPosition(form.position());
        slot.setName(form.name().trim());
        slot.setCouponId(form.couponId());
        slot.setTitle(form.title().trim());
        slot.setSubtitle(blankToNull(form.subtitle()));
        slot.setLinkUrl(blankToNull(form.linkUrl()));
        slot.setImageUrl(blankToNull(form.imageUrl()));
        slot.setSortOrder(form.sortOrder() == null || form.sortOrder() < 0 ? 0 : form.sortOrder());
        slot.setStatus(PromotionSlot.STATUS_INACTIVE.equals(form.status())
                ? PromotionSlot.STATUS_INACTIVE : PromotionSlot.STATUS_ACTIVE);
        slot.setStartTime(form.startTime());
        slot.setEndTime(form.endTime());
        return promotionSlotRepository.save(slot);
    }

    @Override
    public boolean updateSlotStatus(UUID id, String status) {
        String target = PromotionSlot.STATUS_ACTIVE.equals(status)
                ? PromotionSlot.STATUS_ACTIVE : PromotionSlot.STATUS_INACTIVE;
        findSlot(id);
        return promotionSlotRepository.updateStatus(id, target) > 0;
    }

    @Override
    public void resortSlots(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        Map<UUID, PromotionSlot> byId = new HashMap<>();
        promotionSlotRepository.findAllById(ids).forEach(slot -> byId.put(slot.getId(), slot));
        int order = 1;
        for (UUID id : ids) {
            if (byId.containsKey(id)) {
                promotionSlotRepository.updateSortOrder(id, order++);
            }
        }
    }

    @Override
    public boolean removeSlot(UUID id) {
        if (!promotionSlotRepository.existsById(id)) {
            return false;
        }
        promotionSlotRepository.deleteById(id);
        return true;
    }

    /* ---------- E17/E18 会员 ---------- */

    @Override
    public MemberState memberState(UUID userId) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录查看会员权益");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException(401, "请先登录"));
        LocalDateTime now = LocalDateTime.now();
        // 懒落状态：到期记录先置 expired，再决定展示哪个等级
        vipMembershipRepository.markExpired(user.getId(), now);
        VipMembership membership = vipMembershipRepository.findByUserId(user.getId()).orElse(null);
        BigDecimal paid = vipMembershipRepository.sumPaidAmount(user.getId(), OrderStatus.DEAL_STATUSES);
        String level = PromotionService.normalizeLevel(user.getMemberLevel());
        // 积分开通的到期后落回普通；消费自动升级的（本表无记录）一直是长期会员
        if (membership != null && !membership.validAt(now) && User.MEMBER_VIP.equals(user.getMemberLevel())) {
            userRepository.updateMemberLevel(user.getId(), User.MEMBER_ORDINARY);
            level = CampaignPolicy.LEVEL_ORDINARY;
        }
        BigDecimal threshold = CampaignPolicy.upgradeThreshold(level);
        BigDecimal gap = threshold == null ? BigDecimal.ZERO
                : threshold.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        return new MemberState(level, PromotionService.levelName(level),
                CampaignPolicy.discountText(level), CampaignPolicy.memberRate(level),
                paid.setScale(2, RoundingMode.HALF_UP), threshold, gap,
                membership == null ? null : membership.getExpireAt(),
                membership == null ? 0 : membership.remainingDays(now),
                membership == null ? 0 : membership.getRenewCount(),
                Objects.requireNonNullElse(user.getPoints(), 0),
                CampaignPolicy.vipCostPoints(BigDecimal.ONE),
                CampaignPolicy.memberBenefits(level));
    }

    /**
     * 积分开通/续费：先条件扣积分，再以「读到的到期时间」为条件 CAS 延长。
     * 到期时间被另一笔请求改过就返回 0，整笔回滚，积分不会被白扣。
     */
    @Override
    public VipMembership openMembership(UUID userId, int years) {
        if (userId == null) {
            throw new BusinessException(401, "请先登录");
        }
        if (years < 1 || years > MAX_MEMBERSHIP_YEARS) {
            throw new BusinessException("一次只能开通 1-" + MAX_MEMBERSHIP_YEARS + " 年");
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new BusinessException("用户不存在"));
        int cost = CampaignPolicy.vipCostPoints(BigDecimal.valueOf(years));
        if (userRepository.addPoints(userId, -cost) == 0) {
            throw new BusinessException("积分不足，开通 " + years + " 年需要 " + cost + " 积分");
        }
        LocalDateTime now = LocalDateTime.now();
        VipMembership membership = vipMembershipRepository.findByUserId(userId).orElse(null);
        if (membership == null) {
            try {
                vipMembershipRepository.saveAndFlush(newMembership(userId, years, cost, now));
            } catch (DataIntegrityViolationException e) {
                // 另一笔并发请求已经建好记录：把积分还回去，让用户刷新后走续费
                userRepository.addPoints(userId, cost);
                throw new BusinessException(409, "会员状态刚被更新，请刷新后重试");
            }
        } else {
            LocalDateTime expected = membership.getExpireAt();
            LocalDateTime base = expected != null && expected.isAfter(now) ? expected : now;
            if (vipMembershipRepository.renew(userId, expected, base.plusYears(years), cost) == 0) {
                userRepository.addPoints(userId, cost);
                throw new BusinessException(409, "会员状态刚被更新，请刷新后重试");
            }
        }
        // 只保证「至少是 vip」：钻石等级不能被续费动作降下来
        if (!CampaignPolicy.LEVEL_SVIP.equalsIgnoreCase(user.getMemberLevel())) {
            userRepository.updateMemberLevel(userId, User.MEMBER_VIP);
        }
        log.info("会员开通/续费 user={} years={} 花费积分={}", userId, years, cost);
        return vipMembershipRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("会员记录写入失败"));
    }

    private VipMembership newMembership(UUID userId, int years, int cost, LocalDateTime now) {
        VipMembership created = new VipMembership();
        created.setUserId(userId);
        created.setLevel(User.MEMBER_VIP);
        created.setSource("points");
        created.setStatus(VipMembership.STATUS_ACTIVE);
        created.setStartAt(now);
        created.setExpireAt(now.plusYears(years));
        created.setRenewCount(0);
        created.setPointsCost(cost);
        return created;
    }

    private List<UUID> distinct(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<UUID> merged = new LinkedHashSet<>();
        ids.stream().filter(Objects::nonNull).forEach(merged::add);
        return List.copyOf(merged);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
