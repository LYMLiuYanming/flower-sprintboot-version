package org.liuym.flowerv1springboot.common;

import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.UserCoupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 优惠券金额口径集中在此：门槛、满减、满减阶梯（E01）、折扣封顶、范畴（全场/多品类/商品白名单，E02/E03）、
 * 适用范围文案（E20）、到期提醒（E06）、不可用原因（E08）。
 * 纯函数、无副作用，便于单测覆盖下单时的金额计算。
 */
public final class CouponPolicy {

    /** 券码快照里可能出现的分隔符：分类多选与商品白名单都用逗号 */
    private static final String ID_SEPARATOR = ",";

    /** E06：到期前多少天开始提醒 */
    public static final int REMIND_BEFORE_DAYS = 3;

    /** E15：下单返券在领取窗口之外也要能发，这里给返券一个固定的有效天数下限 */
    public static final int MIN_REBATE_VALID_DAYS = 7;

    private CouponPolicy() {
    }

    /**
     * 参与算价的行项目：分类 + 小计 + 商品。
     *
     * <p>productId 允许为空（历史调用方只给分类），此时商品白名单券按「该行不在白名单内」处理，
     * 宁可少给优惠也不能把限定商品的券用到别的商品上。
     */
    public record Line(UUID categoryId, BigDecimal amount, UUID productId) {

        public Line(UUID categoryId, BigDecimal amount) {
            this(categoryId, amount, null);
        }

        public Line(UUID categoryId, BigDecimal amount, UUID productId) {
            this.categoryId = categoryId;
            this.amount = amount == null ? BigDecimal.ZERO : amount;
            this.productId = productId;
        }
    }

    /** 逗号串 id 列表解析：脏值直接跳过，不让一条坏数据把整单打崩 */
    public static List<UUID> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (String part : csv.split(ID_SEPARATOR)) {
            String text = part.trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                ids.add(UUID.fromString(text));
            } catch (IllegalArgumentException ignored) {
                // 非 UUID 片段按脏数据处理
            }
        }
        return List.copyOf(ids);
    }

    public static String joinIds(Collection<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (UUID id : ids) {
            if (id == null) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append(ID_SEPARATOR);
            }
            text.append(id);
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** 券上绑定的分类集合：单选 categoryId 与多选 categoryIds 取并集 */
    public static List<UUID> boundCategoryIds(UserCoupon coupon) {
        if (coupon == null || !Coupon.SCOPE_CATEGORY.equals(coupon.getScope())) {
            return List.of();
        }
        Set<UUID> ids = new LinkedHashSet<>(parseIds(coupon.getCategoryIds()));
        if (coupon.getCategoryId() != null) {
            ids.add(coupon.getCategoryId());
        }
        return List.copyOf(ids);
    }

    public static List<UUID> boundProductIds(UserCoupon coupon) {
        return coupon == null ? List.of() : parseIds(coupon.getProductIds());
    }

    /**
     * 券的适用基数：全场券取整单；分类券只取适用分类（含子分类，由 scopeCategoryIds 给定）的行项目小计；
     * 商品白名单券再收窄到名单内的行。
     * scopeCategoryIds 传 null 或空时退化为只认券上绑定的分类本身。
     */
    public static BigDecimal baseAmount(UserCoupon coupon, Collection<UUID> scopeCategoryIds, List<Line> lines) {
        if (coupon == null || lines == null) {
            return BigDecimal.ZERO;
        }
        List<UUID> boundCategories = boundCategoryIds(coupon);
        boolean categoryScope = !boundCategories.isEmpty();
        List<UUID> productWhitelist = boundProductIds(coupon);
        Set<UUID> scopeIds = categoryScope
                ? (scopeCategoryIds == null || scopeCategoryIds.isEmpty()
                ? new LinkedHashSet<>(boundCategories) : new LinkedHashSet<>(scopeCategoryIds))
                : Set.of();
        BigDecimal base = BigDecimal.ZERO;
        for (Line line : lines) {
            if (categoryScope && !(line.categoryId() != null && scopeIds.contains(line.categoryId()))) {
                continue;
            }
            if (!productWhitelist.isEmpty()
                    && !(line.productId() != null && productWhitelist.contains(line.productId()))) {
                continue;
            }
            base = base.add(line.amount() == null ? BigDecimal.ZERO : line.amount());
        }
        return base.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 门槛：阶梯券以最低档为准（否则「满199减20」这类单档门槛会漏掉阶梯配置），
     * 单档券仍取模板快照上的 threshold。
     */
    public static BigDecimal thresholdOf(UserCoupon coupon) {
        BigDecimal ladderLow = CampaignPolicy.lowestThreshold(coupon == null ? null : coupon.getLadderRule());
        BigDecimal plain = coupon == null || coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
        if (ladderLow.signum() > 0) {
            return plain.signum() > 0 ? plain.min(ladderLow) : ladderLow;
        }
        return plain;
    }

    public static boolean meetsThreshold(UserCoupon coupon, BigDecimal base) {
        return base.compareTo(thresholdOf(coupon)) >= 0;
    }

    /**
     * 本券在这笔金额上能抵多少；不可用（不满足门槛/条款缺失/抵不动）返回 null
     */
    public static BigDecimal discountOf(UserCoupon coupon, BigDecimal base) {
        if (coupon == null || base == null || base.compareTo(BigDecimal.ZERO) <= 0 || !meetsThreshold(coupon, base)) {
            return null;
        }
        // E01：阶梯优先，取够得着的档里减免最大的那档
        CampaignPolicy.Tier tier = CampaignPolicy.bestTier(coupon.getLadderRule(), base);
        if (tier != null) {
            BigDecimal ladder = tier.reduce().min(base).setScale(2, RoundingMode.HALF_UP);
            return ladder.compareTo(BigDecimal.ZERO) > 0 ? ladder : null;
        }
        BigDecimal discount;
        if (Coupon.TYPE_CASH.equals(coupon.getType())) {
            if (coupon.getAmount() == null || coupon.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            discount = coupon.getAmount();
        } else if (Coupon.TYPE_DISCOUNT.equals(coupon.getType())) {
            BigDecimal rate = coupon.getDiscountRate();
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
                return null;
            }
            discount = base.subtract(base.multiply(rate).setScale(2, RoundingMode.HALF_UP));
            if (coupon.getMaxDiscount() != null && coupon.getMaxDiscount().compareTo(BigDecimal.ZERO) > 0) {
                discount = discount.min(coupon.getMaxDiscount());
            }
        } else {
            return null;
        }
        // 抵扣不超过基数本身（分类券尤其如此，避免把整单金额抵穿）
        discount = discount.min(base).setScale(2, RoundingMode.HALF_UP);
        return discount.compareTo(BigDecimal.ZERO) > 0 ? discount : null;
    }

    /**
     * E08：不可用原因。门槛不足会把「还差多少」说清，用户才知道要继续加购还是换券。
     * 返回 null 表示这张券在这笔金额上是可用的。
     */
    public static String unavailableReason(UserCoupon coupon, Collection<UUID> scopeCategoryIds, List<Line> lines) {
        if (coupon == null) {
            return "优惠券不存在";
        }
        if (UserCoupon.STATUS_USED.equals(coupon.getStatus())) {
            return "已使用";
        }
        if (UserCoupon.STATUS_GIFTING.equals(coupon.getStatus())) {
            return "正在转赠给他人";
        }
        if (coupon.isExpiredAt(LocalDateTime.now())) {
            return "已过期";
        }
        BigDecimal base = baseAmount(coupon, scopeCategoryIds, lines);
        List<UUID> boundCategories = boundCategoryIds(coupon);
        List<UUID> whitelist = boundProductIds(coupon);
        if (!boundCategories.isEmpty() && base.signum() <= 0) {
            return "本单没有适用分类的花礼";
        }
        if (!whitelist.isEmpty() && base.signum() <= 0) {
            return "本单没有该券指定的花礼";
        }
        BigDecimal threshold = thresholdOf(coupon);
        if (base.compareTo(threshold) < 0) {
            return "还差 ¥" + threshold.subtract(base).setScale(2, RoundingMode.HALF_UP).toPlainString() + " 可用";
        }
        if (discountOf(coupon, base) == null) {
            return "券面条款已调整，本单抵不出金额";
        }
        return null;
    }

    /**
     * E06：到期提醒。剩 3 天内（含）给出提醒文案，未使用且未过期才提醒。
     * 返回 null 表示不需要提醒。
     */
    public static String expireReminder(UserCoupon coupon, LocalDateTime now) {
        if (coupon == null || coupon.getExpireAt() == null || !UserCoupon.STATUS_UNUSED.equals(coupon.getStatus())) {
            return null;
        }
        if (coupon.isExpiredAt(now)) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), coupon.getExpireAt().toLocalDate());
        if (days < 0 || days > REMIND_BEFORE_DAYS) {
            return null;
        }
        if (days == 0) {
            return "今天到期";
        }
        if (days == 1) {
            return "明天到期";
        }
        return days + " 天后到期";
    }

    /** E13：核销率，领取为 0 时返回 0，避免除零 */
    public static BigDecimal consumeRate(long claimed, long used) {
        if (claimed <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(used * 100L)
                .divide(BigDecimal.valueOf(claimed), 2, RoundingMode.HALF_UP);
    }

    /**
     * E20：适用范围文案自动生成。分类名与商品条数由服务层从库里取，
     * 文案模板集中在这里，避免后台人工写「全场通用」结果券却限了三个分类。
     */
    public static String scopeText(List<UUID> boundCategoryIds, int productWhitelistSize,
                                   List<String> categoryNames, boolean newUserOnly, boolean memberOnly) {
        StringBuilder text = new StringBuilder();
        if (productWhitelistSize > 0) {
            text.append("指定 ").append(productWhitelistSize).append(" 款花礼可用");
        } else if (boundCategoryIds != null && !boundCategoryIds.isEmpty()) {
            int size = categoryNames == null ? boundCategoryIds.size() : categoryNames.size();
            String names = categoryNames == null || categoryNames.isEmpty()
                    ? "指定分类" : String.join("、", categoryNames.subList(0, Math.min(3, categoryNames.size())));
            text.append(size > 3 ? names : "「" + names + "」").append(" 可用");
        } else {
            text.append("全场花礼通用");
        }
        if (memberOnly) {
            text.insert(0, "会员专享 · ");
        }
        if (newUserOnly) {
            text.insert(0, "新客专享 · ");
        }
        return text.toString();
    }
}
