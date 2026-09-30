package org.liuym.flowerv1springboot.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Entity
@Table(name = "order_item", schema = "public")
public class OrderItem {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @JsonIgnore
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;

    @Column(name = "product_image", length = 255)
    private String productImage;

    @Column(name = "price", precision = 10, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "subtotal", precision = 10, scale = 2, nullable = false)
    private BigDecimal subtotal;

    /** 每束花的一句话（B02）：购物车行备注在下单时快照，之后改购物车不影响这张单 */
    @Column(name = "item_note", length = 200)
    private String itemNote;

    /** 这一束是否要求礼品包装（B01）：包装费按整单收一次，行标记给花艺师分束用 */
    @Column(name = "gift_wrap", nullable = false)
    private Boolean giftWrap = false;

    public void setItemNote(String itemNote) {
        this.itemNote = CheckoutPolicy.cleanNote(itemNote);
    }

    public boolean isGiftWrapped() {
        return Boolean.TRUE.equals(giftWrap);
    }

    public void calculateSubtotal() {
        this.subtotal = this.price.multiply(BigDecimal.valueOf(this.quantity));
    }
}