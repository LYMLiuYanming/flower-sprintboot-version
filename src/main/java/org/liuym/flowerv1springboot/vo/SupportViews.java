package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.model.Favorite;
import org.liuym.flowerv1springboot.model.Review;
import org.liuym.flowerv1springboot.model.User;

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
            LocalDateTime createdAt) {

        public static UserView from(User u) {
            return new UserView(u.getId(), u.getUsername(), u.getFullName(), u.getPhone(), u.getEmail(),
                    u.getUserType(), u.getGender(), u.getBirthday(), u.getMemberLevel(), u.getPoints(), u.getStatus(),
                    Boolean.TRUE.equals(u.getMustChangePassword()), u.getLastLoginTime(), u.getCreatedAt());
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
