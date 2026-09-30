package org.liuym.flowerv1springboot.model;

import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户实体类（对应 public.user 表）
 */
@Data // Lombok 注解：自动生成 getter/setter/toString/equals/hashCode
@Entity
@Table(name = "user", schema = "public") // 映射数据库表（schema=public 对应 PostgreSQL 模式）?
@EntityListeners(AuditingEntityListener.class) // 启用创建时间/更新时间自动填充
public class User implements Serializable {

    // F6 会话外置化的前提：登录态以本对象存在 HttpSession 里，Spring Session 落 Redis 时要序列化它。
    // User 只有 UUID/String/LocalDate/Boolean 等标量字段、没有任何关联实体，序列化不会把懒加载代理拖进会话。
    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID（UUID 类型，自动生成）
     */
    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    /**
     * 用户名（唯一，非空）
     */
    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    /**
     * 密码（加密后存储，非空）
     */
    @Column(name = "password", nullable = false, length = 100)
    private String password;

    /**
     * 真实姓名（非空）
     */
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    /**
     * 手机号（唯一，非空）
     */
    @Column(name = "phone", nullable = false, length = 20, unique = true)
    private String phone;

    /**
     * 邮箱（唯一，可为空）
     */
    @Column(name = "email", length = 100, unique = true)
    private String email;

    /**
     * 用户类型（默认 customer，非空）
     * 可选值：customer（普通用户）、admin（管理员）等
     */
    @Column(name = "user_type", nullable = false, length = 20)
    private String userType = "customer";

    /**
     * 性别（可为空）
     * 可选值：male（男）、female（女）、other（其他）
     */
    @Column(name = "gender", length = 10)
    private String gender;

    /**
     * 生日（可为空）
     */
    @Column(name = "birthday")
    private LocalDate birthday;

    /**
     * 会员等级（默认 ordinary，非空）
     * 可选值：ordinary（普通会员）、vip（VIP会员）等
     */
    @Column(name = "member_level", nullable = false, length = 20)
    private String memberLevel = "ordinary";

    /**
     * 积分（默认 0，非空）
     */
    @Column(name = "points", nullable = false)
    private Integer points = 0;

    /**
     * 账号状态（默认 active，非空）
     * 可选值：active（正常）、inactive（禁用）、locked（锁定）等
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "active";

    /**
     * 最后登录时间（可为空）
     */
    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    /**
     * 初始密码/被管理员重置后需强制修改，置 true 时登录后跳转改密页
     */
    @ColumnDefault("false")
    @Column(name = "must_change_password", nullable = false)
    private Boolean mustChangePassword = false;

    /**
     * 注销申请时间（D16）：非空即处于冷静期，软删除。到期由定时任务匿名化，绝不物理删行。
     * 申请撤销时置回 null。
     */
    @Column(name = "deletion_requested_at")
    private LocalDateTime deletionRequestedAt;

    public static final String TYPE_ADMIN = "admin";
    public static final String TYPE_CUSTOMER = "customer";
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";
    public static final String STATUS_LOCKED = "locked";
    /** 冷静期满完成注销后的终态：数据已匿名化，账号不可再登录 */
    public static final String STATUS_DELETED = "deleted";
    public static final String MEMBER_VIP = "vip";
    public static final String MEMBER_ORDINARY = "ordinary";

    public boolean isAdmin() {
        return TYPE_ADMIN.equals(userType);
    }

    public boolean isVip() {
        return MEMBER_VIP.equals(memberLevel);
    }

    /**
     * 创建时间（自动填充，非空）
     */
    @CreatedDate // 自动填充创建时间
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 更新时间（自动填充，非空）
     */
    @LastModifiedDate // 自动填充更新时间
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}