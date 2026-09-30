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

    /** 签收时间：用户确认收货或 15 天自动确认时写入，C26 的「送达 N 天后引导评价」以它为锚点 */
    @Column(name = "deliver_time")
    private LocalDateTime deliverTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    /** 退款单状态镜像（C19）：列表筛选与详情徽标都要按它判定，null 表示从未申请过退款 */
    @Convert(converter = RefundStatusConverter.class)
    @Column(name = "refund_status", length = 20)
    private RefundStatus refundStatus;

    /** 申请退款金额：恒等于建单时的实付，页面不再算第二套账 */
    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "refund_reason", length = 200)
    private String refundReason;

    @Column(name = "refund_requested_at")
    private LocalDateTime refundRequestedAt;

    /** 退款到账时间 */
    @Column(name = "refunded_at")
    private LocalDateTime refundedAt;

    /**
     * 资源回退闸门（C20）：库存/销量/积分/券/时段名额只在把它从 null 写成当前时间的
     * 那一行上执行一次，取消与退款两条路径并发进来也不会重复回退。
     */
    @Column(name = "rollback_at")
    private LocalDateTime rollbackAt;

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

    /** 配送方式码（same_city/next_day/scheduled/air_cold/sea_fresh/self_pickup），历史订单可为空 */
    @Column(name = "delivery_method", length = 20)
    private String deliveryMethod;

    /** 整单估算重量（kg）：运费按首重 + 续重算出，落库便于对账与复核 */
    @Column(name = "delivery_weight", precision = 6, scale = 2)
    private BigDecimal deliveryWeight;

    /** 预约定时达的所选时段，ISO 文本 */
    @Column(name = "delivery_slot", length = 20)
    private String deliverySlot;

    /** 预计送达时间：下单时按配送方式时效或所选时段算出 */
    @Column(name = "expected_arrive_at")
    private LocalDateTime expectedArriveAt;

    /** 送达方式码，见 DeliveryPolicy.Placement：无接触放置需求要结构化，备注里捞不可靠 */
    @Column(name = "delivery_preference", length = 20)
    private String deliveryPreference;

    /** 联系收花人方式码，见 DeliveryPolicy.Contact */
    @Column(name = "contact_preference", length = 20)
    private String contactPreference;

    /** 贺卡样式码，见 GreetingCardPolicy */
    @Column(name = "card_style", length = 20)
    private String cardStyle;

    @Column(name = "card_recipient", length = 50)
    private String cardRecipient;

    @Column(name = "card_signature", length = 50)
    private String cardSignature;

    /** 贺卡正文快照：下单后改模板不影响已下单贺卡 */
    @Column(name = "card_message", length = 200)
    private String cardMessage;

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

    /** 资源是否已回退过（C20）：闸门列非空即已回退，重复关单不得再补库存或退积分 */
    public boolean isRolledBack() {
        return rollbackAt != null;
    }

    /** 是否还允许再提一次退款申请：被驳回或已撤销后允许重开 */
    public boolean canReapplyRefund() {
        return refundStatus == null || refundStatus == RefundStatus.REJECTED
                || refundStatus == RefundStatus.REVOKED;
    }
}