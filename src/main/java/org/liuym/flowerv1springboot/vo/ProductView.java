package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Product;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 商品视图：字段命名与原实体 JSON 保持一致（category 仍为嵌套对象），
 * 在此基础上补充 categoryName / imageList 等派生字段，前端无需改造即可平滑切换
 */
public record ProductView(
        UUID id,
        String code,
        String name,
        String description,
        BigDecimal price,
        BigDecimal originalPrice,
        String mainImage,
        String images,
        List<String> imageList,
        CategoryRef category,
        String categoryName,
        Integer stock,
        Integer salesCount,
        BigDecimal rating,
        Integer reviewCount,
        Boolean isActive,
        Boolean isFeatured,
        Boolean isNew,
        String unit,
        String weight,
        String material,
        String packaging,
        String tags,
        List<String> tagList,
        String subtitle,
        String careTip,
        String flowerLanguage,
        String suitableFor,
        List<String> suitableForList,
        /** 划线价折扣文案（如「6.5 折」），无划线价时为 null，前端据此决定是否显示 */
        String discountLabel,
        /** 库存 ≤ 5 且在售：列表页打「库存紧张」标 */
        Boolean lowStock,
        Boolean soldOut,
        /** 主产地：后台下拉回填与详情页溯源条共用 */
        UUID originId,
        /** 运营是否真写了养护贴士：详情页没写时展示通用建议，列表页勾了该筛选条件就不会误显示 */
        Boolean careTipProvided,
        LocalDateTime createdAt) {

    /** 库存告急阈值：低于此值列表页显示「库存紧张」 */
    public static final int LOW_STOCK_ALERT = 5;

    /** 折扣文案按国内习惯用「几折」而不是百分比，且只在确实便宜时给出 */
    static String discountLabel(Product p) {
        BigDecimal original = p.getOriginalPrice();
        BigDecimal price = p.getPrice();
        if (original == null || price == null || original.compareTo(price) <= 0
                || original.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal rate = price.multiply(BigDecimal.TEN).divide(original, 1, java.math.RoundingMode.HALF_UP);
        if (rate.compareTo(new BigDecimal("9.5")) >= 0) {
            return null;
        }
        return rate.stripTrailingZeros().toPlainString() + " 折";
    }

    public record CategoryRef(UUID id, String name, String icon, Integer sortOrder) {

        public static CategoryRef from(Category c) {
            return c == null ? null : new CategoryRef(c.getId(), c.getName(), c.getIcon(), c.getSortOrder());
        }
    }

    public static ProductView from(Product p) {
        return new ProductView(
                p.getId(),
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getPrice(),
                p.getOriginalPrice(),
                p.getMainImage(),
                p.getImages(),
                split(p.getImages()),
                CategoryRef.from(p.getCategory()),
                p.getCategory() == null ? null : p.getCategory().getName(),
                p.getStock(),
                p.getSalesCount(),
                p.getRating(),
                p.getReviewCount(),
                p.getIsActive(),
                p.getIsFeatured(),
                p.getIsNew(),
                p.getUnit(),
                p.getWeight(),
                p.getMaterial(),
                p.getPackaging(),
                p.getTags(),
                split(p.getTags()),
                p.getSubtitle(),
                p.getCareTip(),
                p.getFlowerLanguage(),
                p.getSuitableFor(),
                split(p.getSuitableFor()),
                discountLabel(p),
                p.getStock() != null && p.getStock() <= LOW_STOCK_ALERT && !Boolean.FALSE.equals(p.getIsActive()),
                p.getStock() == null || p.getStock() <= 0,
                p.getOriginId(),
                p.getCareTip() != null && !p.getCareTip().isBlank(),
                p.getCreatedAt());
    }

    public static List<ProductView> from(List<Product> products) {
        return products.stream().map(ProductView::from).toList();
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
