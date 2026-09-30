package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 登录会话台账（D18）：每次登录落一行，用于「登录设备」列表与「退出其他设备」。
 *
 * <p>这里只是应用侧的服务端记录，不代表容器内的 HttpSession 本体；
 * 「退出其他设备」把除当前外的记录置 revoked，下一次这些设备凭记住我续登或访问受保护资源时失效，
 * 但绝不影响当前会话。
 */
@Data
@Entity
@Table(name = "user_session", schema = "public",
        indexes = {
                @Index(name = "idx_user_session_user", columnList = "user_id,revoked,last_access_time")
        })
@EntityListeners(AuditingEntityListener.class)
public class UserSession {

    /** 会话令牌：登录成功时生成并写回 HttpSession，作为「当前设备」的判定依据 */
    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "token", nullable = false, columnDefinition = "uuid")
    private UUID token;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    /** 登录时的容器 HttpSession id，仅用于排查，退出后无意义 */
    @Column(name = "http_session_id", length = 80)
    private String httpSessionId;

    /** 登录成功后是否同时下发了「记住我」Cookie */
    @Column(name = "remember_me", nullable = false)
    private Boolean rememberMe = false;

    /** 被「退出其他设备」作废：作废后不得再据此续登 */
    @Column(name = "revoked", nullable = false)
    private Boolean revoked = false;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "last_access_time", nullable = false)
    private LocalDateTime lastAccessTime;
}
