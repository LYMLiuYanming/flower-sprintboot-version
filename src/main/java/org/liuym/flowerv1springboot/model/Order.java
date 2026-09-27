package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Entity
@Table(name = "`order`", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "order_no", nullable = false, unique = true, length = 32)
    private String orderNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "discount_amount", precision = 10, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "pay_amount", precision = 10, scale = 2, nullable = false)
    private BigDecimal payAmount;

    @Column(name = "freight", precision = 10, scale = 2)
    private BigDecimal freight = BigDecimal.ZERO;

    @Convert(converter = OrderStatusConverter.class)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "pay_method", length = 20)
    private String payMethod;

    @Column(name = "pay_time")
    private LocalDateTime payTime;

    @Column(name = "express_company", length = 50)
    private String expressCompany;

    @Column(name = "express_no", length = 60)
    private String expressNo;

    @Column(name = "ship_time")
    private LocalDateTime shipTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @ColumnDefault("0")
    @Column(name = "points_earned", nullable = false)
    private Integer pointsEarned = 0;

    @ColumnDefault("0")
    @Column(name = "points_used", nullable = false)
    private Integer pointsUsed = 0;

    /** 本单优惠券实际抵扣额（VIP 折扣与积分抵扣仍并入 discountAmount） */
    @Column(name = "coupon_amount", precision = 10, scale = 2)
    private BigDecimal couponAmount;

    /** 使用的持券 id：取消/退款时据此把券退回券包 */
    @Column(name = "user_coupon_id", columnDefinition = "uuid")
    private UUID userCouponId;

    @Column(name = "receiver_name", nullable = false, length = 50)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    @Column(name = "receiver_address", nullable = false, length = 255)
    private String receiverAddress;

    @Column(name = "remark", length = 500)
    private String remark;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void calculatePayAmount() {
        this.payAmount = this.totalAmount.subtract(this.discountAmount).add(this.freight);
    }

    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(this);
    }

    public boolean isPayable() {
        return status == OrderStatus.PENDING;
    }
}