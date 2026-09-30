package org.liuym.flowerv1springboot.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 客服账号与数据范围（U26）：主键即 user_id。
 *
 * <p>口径：登记为 supervisor 的账号看全量队列；登记为普通客服的账号只看分配给自己的工单，
 * 打开别人的工单按 404 处理并写审计。<b>未在表里登记的 admin 视为全量可见</b>——
 * 现存后台账号都靠这条兜底，收口时逐人登记即可，不会把后台锁死。
 */
@Data
@Entity
@Table(name = "ticket_agent", schema = "public")
public class TicketAgent {

    /** 主键即账号 id：一人一行，新建时由服务层直接赋值 */
    @Id
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "display_name", length = 60)
    private String displayName;

    @Column(name = "supervisor", nullable = false)
    private Boolean supervisor = false;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public boolean isSupervisor() {
        return Boolean.TRUE.equals(supervisor);
    }
}
