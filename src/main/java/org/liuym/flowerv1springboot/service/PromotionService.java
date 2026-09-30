package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.CampaignPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.dto.MarketingDtos;
import org.liuym.flowerv1springboot.model.FullReduction;
import org.liuym.flowerv1springboot.model.PromotionSlot;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.VipMembership;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 营销中心：满减活动（E14）、促销位开关与排序（E19）、会员开通/续费（E18）
 */
public interface PromotionService {

    /* ---------- E14 满减活动 ---------- */

    List<FullReduction> findAllReductions();

    /** 生效窗口内且启用的活动，按优先级从高到低 */
    List<FullReduction> findActiveReductions();

    FullReduction findReduction(UUID id);

    /** id 为 null 表示新建 */
    FullReduction saveReduction(UUID id, MarketingDtos.ReductionForm form);

    boolean updateReductionStatus(UUID id, String status);

    boolean removeReduction(UUID id);

    /** 这笔金额在该活动上能自动减多少，返回 null 表示没命中 */
    CampaignPolicy.Tier hitReduction(FullReduction reduction, List<CouponPolicy.Line> lines);

    /** 活动在本单上的适用基数（分类活动只算适用分类的行） */
    BigDecimal reductionBase(FullReduction reduction, List<CouponPolicy.Line> lines);

    /** 同单命中多条活动时按「减免更大 → 优先级更高」挑一条 */
    Optional<FullReduction> bestReduction(List<CouponPolicy.Line> lines);

    /* ---------- E19 促销位 ---------- */

    List<PromotionSlot> findAllSlots();

    List<PromotionSlot> findVisibleSlots(String position);

    PromotionSlot findSlot(UUID id);

    PromotionSlot saveSlot(UUID id, MarketingDtos.SlotForm form);

    boolean updateSlotStatus(UUID id, String status);

    /** 按传入顺序重排 sort_order（从 1 开始），只更新属于本次提交的行 */
    void resortSlots(List<UUID> ids);

    boolean removeSlot(UUID id);

    /* ---------- E18 会员 ---------- */

    /** 会员状态与权益：等级、折扣、到期时间、续费所需积分 */
    MemberState memberState(UUID userId);

    /** 积分开通/续费，返回最新记录 */
    VipMembership openMembership(UUID userId, int years);

    /** E18：开通前给前台看的资格与价格，不落库 */
    record MemberState(
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
            List<String> benefits) {
    }

    /** User.member_level 与策略类等级字典之间的展示桥 */
    static String levelName(String level) {
        if (CampaignPolicy.LEVEL_SVIP.equalsIgnoreCase(level)) {
            return "钻石会员";
        }
        if (CampaignPolicy.LEVEL_VIP.equalsIgnoreCase(level)) {
            return "黄金会员";
        }
        return "花友";
    }

    /**
     * 等级归一化：库里历史只有 ordinary/vip，未知值一律按非会员处理，
     * 避免脏数据拿到 svip 折扣
     */
    static String normalizeLevel(String level) {
        if (CampaignPolicy.LEVEL_SVIP.equalsIgnoreCase(level)) {
            return CampaignPolicy.LEVEL_SVIP;
        }
        if (User.MEMBER_VIP.equalsIgnoreCase(level)) {
            return CampaignPolicy.LEVEL_VIP;
        }
        return CampaignPolicy.LEVEL_ORDINARY;
    }
}
