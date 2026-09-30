package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "cart", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Cart {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    @Column(name = "total_amount")
    private Double totalAmount = 0.0;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount = 0;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 合计只统计可购行（B05）：失效行单独分组展示，若仍计入金额就会出现
     * 「清单里有一束下架花但合计多了 199」的账实不符
     */
    public void calculateTotal() {
        this.totalAmount = items.stream()
                .filter(CartItem::isPurchasable)
                .mapToDouble(item -> item.getPrice() * item.getQuantity())
                .sum();
        this.itemCount = items.stream()
                .filter(CartItem::isPurchasable)
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    /** 礼品包装的行数（B01）：整单收一次费用，这里只给出勾选束数 */
    public long giftWrappedCount() {
        return items.stream().filter(CartItem::isGiftWrapped).filter(CartItem::isPurchasable).count();
    }

    /** 比加入时降价的合计（B07） */
    public BigDecimal priceDropTotal() {
        return items.stream()
                .filter(CartItem::isPurchasable)
                .map(CartItem::priceDrop)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    public void addItem(CartItem item) {
        items.add(item);
        item.setCart(this);
        calculateTotal();
    }

    public void removeItem(CartItem item) {
        items.remove(item);
        item.setCart(null);
        calculateTotal();
    }

    public void clearItems() {
        items.clear();
        calculateTotal();
    }
}