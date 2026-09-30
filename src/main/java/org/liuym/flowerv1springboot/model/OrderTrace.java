package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 订单轨迹节点：状态流转时自动写入，后台也可补记自定义节点（如"花材已空运到港"）。
 * 与订单状态分开存，是为了保留每一步的时间与操作人，前台按时间轴展示。
 */
@Data
@Entity
@Table(name = "order_trace", schema = "public")
@EntityListeners(AuditingEntityListener.class)
public class OrderTrace {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 节点码：created/paid/processing/shipped/delivered/completed/closed/plan/custom */
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "title", nullable = false, length = 60)
    private String title;

    @Column(name = "description", length = 255)
    private String description;

    /** 操作人：系统自动节点为"系统"，后台补记为管理员账号 */
    @Column(name = "operator", length = 60)
    private String operator;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public OrderTrace() {
    }

    public OrderTrace(String code, String title, String description, String operator) {
        this.code = code;
        this.title = title;
        this.description = description;
        this.operator = operator;
    }
}
