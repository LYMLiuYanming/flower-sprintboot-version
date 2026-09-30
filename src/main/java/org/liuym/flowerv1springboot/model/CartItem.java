package org.liuym.flowerv1springboot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@Table(name = "cart_item", schema = "public")
public class CartItem {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    @JsonIgnore
    private Cart cart;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 1;

    @Column(name = "price")
    private Double price;

    @Column(name = "subtotal")
    private Double subtotal;

    /** 礼品包装开关（B01）：费用按整单收一次，标记只说明"这一束要包" */
    @Column(name = "gift_wrap", nullable = false)
    private Boolean giftWrap = false;

    /** 每束单独一句话（B02）：下单时快照进 order_item.item_note */
    @Column(name = "note", length = 200)
    private String note;

    /** 加购价快照（B07）：降价提示以它为准，改数量与改价都不覆盖 */
    @Column(name = "added_price", precision = 10, scale = 2)
    private BigDecimal addedPrice;

    @Column(name = "added_at")
    private LocalDateTime addedAt;

    public void calculateSubtotal() {
        this.subtotal = this.price * this.quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
        calculateSubtotal();
    }

    public void setPrice(Double price) {
        this.price = price;
        calculateSubtotal();
    }

    /** 备注落库前先清洗，防止超长文本或换行把行高撑坏（B02） */
    public void setNote(String note) {
        this.note = CheckoutPolicy.cleanNote(note);
    }

    public boolean isGiftWrapped() {
        return Boolean.TRUE.equals(giftWrap);
    }

    /**
     * 可购判定（B05）：商品还在架且有货。下架与缺货的行不参与合计、不能结算，
     * 但仍留在购物车里供用户自行清理，直接删掉会让用户以为丢了东西
     */
    @Transient
    public boolean isPurchasable() {
        Product current = getProduct();
        if (current == null || !Boolean.TRUE.equals(current.getIsActive())) {
            return false;
        }
        Integer stock = current.getStock();
        return stock != null && stock > 0;
    }

    /** 相对加购时的降价额（B07）：涨价或价格快照缺失返回 0，提示只在真降价时出现 */
    @Transient
    public BigDecimal priceDrop() {
        if (addedPrice == null || getProduct() == null || getProduct().getPrice() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal drop = addedPrice.subtract(getProduct().getPrice());
        return drop.compareTo(BigDecimal.ZERO) > 0 ? drop.setScale(2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
    }
}
