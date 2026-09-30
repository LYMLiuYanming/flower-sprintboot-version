package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.AccountPolicy;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.model.Favorite;
import org.liuym.flowerv1springboot.model.Review;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.model.UserSession;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 其余输出视图：UserView 显式排除 password 字段，杜绝口令外泄
 */
public final class SupportViews {

    private SupportViews() {
    }

    public record AddressView(
            UUID id,
            String receiverName,
            String receiverPhone,
            String province,
            String city,
            String district,
            String detail,
            String fullAddress,
            String tag,
            Boolean isDefault,
            LocalDateTime createdAt) {

        public static AddressView from(Address a) {
            return new AddressView(a.getId(), a.getReceiverName(), a.getReceiverPhone(), a.getProvince(),
                    a.getCity(), a.getDistrict(), a.getDetail(), a.fullAddress(), a.getTag(), a.getIsDefault(),
                    a.getCreatedAt());
        }

        public static List<AddressView> from(List<Address> list) {
            return list.stream().map(AddressView::from).toList();
        }
    }

    public record ReviewView(
            UUID id,
            UUID productId,
            String productName,
            String productImage,
            String userName,
            Integer rating,
            String content,
            LocalDateTime createdAt) {

        public static ReviewView from(Review r) {
            return new ReviewView(r.getId(),
                    r.getProduct() == null ? null : r.getProduct().getId(),
                    r.getProduct() == null ? null : r.getProduct().getName(),
                    r.getProduct() == null ? null : r.getProduct().getMainImage(),
                    r.getUser() == null ? "匿名用户" : maskName(r.getUser().getFullName()),
                    r.getRating(), r.getContent(), r.getCreatedAt());
        }

        public static List<ReviewView> from(List<Review> list) {
            return list.stream().map(ReviewView::from).toList();
        }
    }

    public record UserView(
            UUID id,
            String username,
            String fullName,
            String phone,
            String email,
            String userType,
            String gender,
            LocalDate birthday,
            String memberLevel,
            Integer points,
            String status,
            Boolean mustChangePassword,
            LocalDateTime lastLoginTime,
            LocalDateTime createdAt,
            /** 注销申请时间：非空表示处于冷静期（D16） */
            LocalDateTime deletionRequestedAt,
            /** D05 头像统一口径：首字与由姓名派生的稳定底色，前端直接渲染，无需各自实现 */
            String avatarInitial,
            String avatarColor) {

        public static UserView from(User u) {
            String displayName = u.getFullName() != null && !u.getFullName().isBlank()
                    ? u.getFullName() : u.getUsername();
            return new UserView(u.getId(), u.getUsername(), u.getFullName(), u.getPhone(), u.getEmail(),
                    u.getUserType(), u.getGender(), u.getBirthday(), u.getMemberLevel(), u.getPoints(), u.getStatus(),
                    Boolean.TRUE.equals(u.getMustChangePassword()), u.getLastLoginTime(), u.getCreatedAt(),
                    u.getDeletionRequestedAt(),
                    AccountPolicy.avatarInitial(displayName), AccountPolicy.avatarColor(displayName));
        }
    }

    public record FavoriteView(UUID id, ProductView product, LocalDateTime createdAt) {

        public static FavoriteView from(Favorite f) {
            return new FavoriteView(f.getId(), f.getProduct() == null ? null : ProductView.from(f.getProduct()),
                    f.getCreatedAt());
        }

        public static List<FavoriteView> from(List<Favorite> list) {
            return list.stream().map(FavoriteView::from).toList();
        }
    }

    /**
     * 登录设备（D18）：不含 token 之外的敏感项，UA 只保留可读的浏览器/系统摘要。
     */
    public record SessionView(
            UUID token,
            String clientIp,
            String browser,
            String os,
            Boolean rememberMe,
            Boolean current,
            LocalDateTime loginTime,
            LocalDateTime lastAccessTime) {

        public static SessionView from(UserSession s, boolean current) {
            return new SessionView(s.getToken(), s.getClientIp(),
                    browserLabel(s.getUserAgent()), osLabel(s.getUserAgent()),
                    Boolean.TRUE.equals(s.getRememberMe()), current,
                    s.getCreatedAt(), s.getLastAccessTime());
        }

        /** 从 UA 里提炼「Chrome / Safari / 微信 …」一类可读标签，识别不到归为「其他浏览器」 */
        private static String browserLabel(String ua) {
            if (ua == null || ua.isBlank()) {
                return "未知设备";
            }
            String t = ua.toLowerCase();
            if (t.contains("micromessenger")) {
                return "微信内置浏览器";
            }
            if (t.contains("edg/")) {
                return "Edge";
            }
            if (t.contains("opr/") || t.contains("opera")) {
                return "Opera";
            }
            if (t.contains("firefox")) {
                return "Firefox";
            }
            if (t.contains("chrome") || t.contains("crios")) {
                return "Chrome";
            }
            if (t.contains("safari")) {
                return "Safari";
            }
            return "其他浏览器";
        }

        private static String osLabel(String ua) {
            if (ua == null || ua.isBlank()) {
                return "未知系统";
            }
            String t = ua.toLowerCase();
            if (t.contains("iphone")) {
                return "iPhone";
            }
            if (t.contains("ipad")) {
                return "iPad";
            }
            if (t.contains("android")) {
                return "Android";
            }
            if (t.contains("windows nt")) {
                return "Windows";
            }
            if (t.contains("mac os x") || t.contains("macintosh")) {
                return "macOS";
            }
            if (t.contains("like mac os x") || t.contains("ios")) {
                return "iOS";
            }
            if (t.contains("linux")) {
                return "Linux";
            }
            return "未知系统";
        }
    }

    /**
     * 券包视图增强（D13/D14）：在原始持券条款之上补「即将过期」与「还差多少可用」。
     * 门槛差额需要一个基准金额（购物车合计），无基准时按 0 计，即「满 X 可用」。
     */
    public record CouponAccountView(
            UUID id,
            UUID couponId,
            String name,
            String type,
            BigDecimal threshold,
            BigDecimal amount,
            BigDecimal discountRate,
            BigDecimal maxDiscount,
            String scope,
            String status,
            LocalDateTime expireAt,
            LocalDateTime receivedAt,
            LocalDateTime usedAt,
            /** 剩余天数：已过期为 0，未过期向上取整 */
            Long daysToExpire,
            /** 距到期 ≤ 提醒窗口且未过期：券包置顶展示 */
            Boolean expiringSoon,
            /** 还差多少金额可用；已达门槛或无门槛为 null */
            BigDecimal gapToThreshold) {

        public static CouponAccountView from(UserCoupon u, BigDecimal baseAmount, LocalDateTime now) {
            LocalDateTime expireAt = u.getExpireAt();
            boolean expired = expireAt != null && !expireAt.isAfter(now);
            String effectiveStatus = expired && UserCoupon.STATUS_UNUSED.equals(u.getStatus())
                    ? UserCoupon.STATUS_EXPIRED : u.getStatus();
            return new CouponAccountView(u.getId(), u.getCouponId(), u.getName(), u.getType(),
                    u.getThreshold(), u.getAmount(), u.getDiscountRate(), u.getMaxDiscount(), u.getScope(),
                    effectiveStatus, expireAt, u.getReceivedAt(), u.getUsedAt(),
                    AccountPolicy.daysToExpire(expireAt, now),
                    AccountPolicy.isExpiringSoon(expireAt, now),
                    AccountPolicy.thresholdGap(u.getThreshold(), baseAmount));
        }
    }

    /**
     * 积分流水记录（D20）。无独立流水表，由订单推导：
     * 每笔成交订单产出一条「下单获得」（pointsEarned>0），
     * 每笔用了积分的订单产出一条「下单抵扣」（pointsUsed>0），退款/取消订单再补一条回补。
     */
    public record PointRecordView(
            String type,
            String direction,
            String title,
            String refOrderNo,
            Integer points,
            LocalDateTime time) {

        public static PointRecordView of(String type, String direction, String title,
                                         String refOrderNo, Integer points, LocalDateTime time) {
            return new PointRecordView(type, direction, title, refOrderNo, points, time);
        }
    }

    /**
     * 账户概览角标（D15）：待付款单数 + 最近一笔待付款的倒计时，供侧栏/订单页/导航角标共用。
     */
    public record AccountSummaryView(
            long totalOrders,
            long pendingPayCount,
            /** 最近一笔待付款订单号，无则为 null */
            String pendingPayOrderNo,
            /** 该单付款截止时间，无则为 null */
            LocalDateTime pendingPayDeadline,
            /** 距今剩余秒数，已超时为 0；前端据此走秒级倒计时 */
            Long pendingPaySecondsLeft,
            long favoriteCount,
            long addressCount,
            long couponUnusedCount,
            long couponExpiringSoonCount,
            Integer points) {

        public static AccountSummaryView of(long totalOrders, long pendingPayCount, String pendingPayOrderNo,
                                            LocalDateTime pendingPayDeadline, LocalDateTime now,
                                            long favoriteCount, long addressCount, long couponUnusedCount,
                                            long couponExpiringSoonCount, Integer points) {
            Long secondsLeft = null;
            if (pendingPayDeadline != null) {
                long s = Duration.between(now, pendingPayDeadline).getSeconds();
                secondsLeft = Math.max(0, s);
            }
            return new AccountSummaryView(totalOrders, pendingPayCount, pendingPayOrderNo, pendingPayDeadline,
                    secondsLeft, favoriteCount, addressCount, couponUnusedCount, couponExpiringSoonCount, points);
        }
    }

    /**
     * 评价列表中昵称做脱敏展示
     */
    private static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return "匿名用户";
        }
        if (name.length() == 1) {
            return name + "**";
        }
        return name.charAt(0) + "**" + name.charAt(name.length() - 1);
    }
}
