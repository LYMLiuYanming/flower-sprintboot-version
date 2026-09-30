package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.model.FullReduction;
import org.liuym.flowerv1springboot.model.PromotionSlot;
import org.liuym.flowerv1springboot.service.PromotionService;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 营销视图：促销位（E19）、满减活动（E14）、会员权益（E17/E18）、邀请有礼（E16）
 */
public final class PromotionViews {

    private PromotionViews() {
    }

    /**
     * 促销位：coupon 为空表示纯文案位；前台按 sort_order 渲染，开关与排序都由后台控制
     */
    public record SlotView(
            UUID id,
            String position,
            String name,
            String title,
            String subtitle,
            String linkUrl,
            String imageUrl,
            Integer sortOrder,
            String status,
            LocalDateTime startTime,
            LocalDateTime endTime,
            UUID couponId,
            CouponViews.CouponView coupon,
            String stateLabel) {

        public static SlotView of(PromotionSlot slot, CouponViews.CouponView coupon) {
            return new SlotView(slot.getId(), slot.getPosition(), slot.getName(), slot.getTitle(),
                    slot.getSubtitle(), slot.getLinkUrl(), slot.getImageUrl(), slot.getSortOrder(),
                    slot.getStatus(), slot.getStartTime(), slot.getEndTime(), slot.getCouponId(), coupon,
                    stateLabel(slot, LocalDateTime.now()));
        }

        private static String stateLabel(PromotionSlot slot, LocalDateTime now) {
            if (PromotionSlot.STATUS_INACTIVE.equals(slot.getStatus())) {
                return "已关闭";
            }
            if (slot.getStartTime() != null && slot.getStartTime().isAfter(now)) {
                return "未开始";
            }
            if (slot.getEndTime() != null && slot.getEndTime().isBefore(now)) {
                return "已过期";
            }
            return "投放中";
        }
    }

    /**
     * 满减活动：阶梯文案由 CampaignPolicy 生成，后台不再手写
     */
    public record ReductionView(
            UUID id,
            String name,
            String scope,
            List<UUID> categoryIds,
            String ladderRule,
            String ladderText,
            BigDecimal bestReduce,
            boolean stackWithCoupon,
            int priority,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String status,
            boolean hit) {

        public static ReductionView of(FullReduction reduction, BigDecimal base) {
            CampaignPolicy.Tier tier = CampaignPolicy.bestTier(reduction.getLadderRule(), base);
            return new ReductionView(reduction.getId(), reduction.getName(), reduction.getScope(),
                    CouponPolicy.parseIds(reduction.getCategoryIds()), reduction.getLadderRule(),
                    CampaignPolicy.ladderText(reduction.getLadderRule()),
                    tier == null ? null : tier.reduce(),
                    Boolean.TRUE.equals(reduction.getStackWithCoupon()), reduction.getPriority(),
                    reduction.getStartTime(), reduction.getEndTime(), reduction.getStatus(), tier != null);
        }
    }

    /** 结算页满减预览：命中了哪些活动、最优是哪条、能不能和券叠加 */
    public record ReductionPreview(
            BigDecimal base,
            List<ReductionView> reductions,
            String bestName,
            BigDecimal bestReduce,
            Boolean bestStackWithCoupon,
            String note) {
    }

    /** E17/E18：会员状态与权益说明 */
    public record MemberView(
            String level,
            String levelName,
            String discountText,
            BigDecimal rate,
            BigDecimal paidAmount,
            BigDecimal upgradeThreshold,
            BigDecimal upgradeGap,
            LocalDateTime expireAt,
            long remainingDays,
            int renewCount,
            int pointsBalance,
            int costPoints,
            List<String> benefits,
            boolean member,
            boolean canOpen,
            String openHint) {

        public static MemberView of(PromotionService.MemberState state) {
            boolean member = state.rate().compareTo(BigDecimal.ONE) < 0;
            boolean canOpen = state.pointsBalance() >= state.costPoints();
            String action = member ? "续费" : "开通";
            return new MemberView(state.level(), state.levelName(), state.discountText(), state.rate(),
                    state.paidAmount(), state.upgradeThreshold(), state.upgradeGap(), state.expireAt(),
                    state.remainingDays(), state.renewCount(), state.pointsBalance(), state.costPoints(),
                    state.benefits(), member, canOpen,
                    // 按钮文案与拦截原因都由服务端给，页面不再自己拼「积分够不够」
                    canOpen ? action + " 1 年需 " + state.costPoints() + " 积分，当前余额 " + state.pointsBalance()
                            : "积分不足，还差 " + (state.costPoints() - state.pointsBalance()) + " 分才能" + action);
        }
    }

    /** E16：我的邀请页 */
    public record InviteView(
            String code,
            String link,
            int invitedCount,
            int rewardedCount,
            long boundCount,
            long firstOrderCount,
            String rewardCouponName,
            int rewardPoints,
            List<InviteeRow> records,
            String note) {
    }

    public record InviteeRow(
            UUID inviteeId,
            String nickname,
            String status,
            String statusText,
            LocalDateTime invitedAt,
            LocalDateTime rewardedAt) {
    }
}
