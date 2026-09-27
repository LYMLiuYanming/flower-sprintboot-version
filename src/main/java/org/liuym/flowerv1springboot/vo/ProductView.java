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
        LocalDateTime createdAt) {

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
