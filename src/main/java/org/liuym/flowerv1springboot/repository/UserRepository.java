package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户数据访问层（JPA 自动实现 CRUD 操作）
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    // 根据用户名查询（唯一）
    Optional<User> findByUsername(String username);

    // 根据手机号查询（唯一）
    Optional<User> findByPhone(String phone);

    // 根据邮箱查询（唯一）
    Optional<User> findByEmail(String email);

    // 根据用户类型查询（分页）
    Page<User> findByUserType(String userType, Pageable pageable);

    // 根据会员等级查询（分页）
    Page<User> findByMemberLevel(String memberLevel, Pageable pageable);

    // 根据账号状态查询（分页）
    Page<User> findByStatus(String status, Pageable pageable);

    // 检查用户名是否存在
    boolean existsByUsername(String username);

    // 检查手机号是否存在
    boolean existsByPhone(String phone);

    // 检查邮箱是否存在
    boolean existsByEmail(String email);

    // 根据用户类型统计数量
    long countByUserType(String userType);

    // 更新密码（自定义 JPQL）
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.password = :newPassword, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updatePassword(@Param("id") UUID id, @Param("newPassword") String newPassword);

    // 更新状态（自定义 JPQL）
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.status = :status, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") String status);

    // 更新最后登录时间（自定义 JPQL）
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.lastLoginTime = :lastLoginTime, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateLastLoginTime(@Param("id") UUID id, @Param("lastLoginTime") LocalDateTime lastLoginTime);

    /**
     * 积分增减走条件更新：余额不足时影响行数为 0，由调用方判定失败，避免并发扣成负数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.points = u.points + :delta, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.id = :id AND u.points + :delta >= 0")
    int addPoints(@Param("id") UUID id, @Param("delta") Integer delta);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.memberLevel = :memberLevel, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateMemberLevel(@Param("id") UUID id, @Param("memberLevel") String memberLevel);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.mustChangePassword = :mustChange, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int updateMustChangePassword(@Param("id") UUID id, @Param("mustChange") Boolean mustChange);

    /** 记录注销申请时间（D16）；status 保持不变以便冷静期内仍可登录撤销 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.deletionRequestedAt = :requestedAt, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int markDeletionRequested(@Param("id") UUID id, @Param("requestedAt") LocalDateTime requestedAt);

    /** 撤销注销申请：清空申请时间 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.deletionRequestedAt = null, u.updatedAt = CURRENT_TIMESTAMP WHERE u.id = :id")
    int cancelDeletionRequest(@Param("id") UUID id);

    /** 冷静期已满、待匿名化的账号 */
    @Query("SELECT u FROM User u WHERE u.deletionRequestedAt IS NOT NULL "
            + "AND u.deletionRequestedAt <= :deadline AND u.status <> 'deleted'")
    List<User> findDeletionExpired(@Param("deadline") LocalDateTime deadline);

    /**
     * 注销完成（软删除）：置终态并匿名化可识别信息，保留行与历史订单外键。
     * 手机号 / 邮箱用不可回收的占位值替换（各自以 id 派生保证唯一、且不超过列长 20），
     * 用户名保留以便客服回溯，登录由 status='deleted' 阻断。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE public.\"user\" SET status = 'deleted', deletion_requested_at = NULL, "
            + "full_name = '已注销用户', phone = 'x' || substr(replace(id::text, '-', ''), 1, 19), "
            + "email = NULL, must_change_password = false, updated_at = now() WHERE id = :id",
            nativeQuery = true)
    int anonymizeForDeletion(@Param("id") UUID id);

    // 后台多条件筛选：关键词/用户类型/账号状态，关键词以空串表示不过滤
    @Query("SELECT u FROM User u WHERE "
            + "(:keyword = '' OR u.username LIKE %:keyword% OR u.fullName LIKE %:keyword% OR u.phone LIKE %:keyword% OR u.email LIKE %:keyword%) "
            + "AND (:userType IS NULL OR u.userType = :userType) "
            + "AND (:status IS NULL OR u.status = :status)")
    Page<User> search(@Param("keyword") String keyword,
                      @Param("userType") String userType,
                      @Param("status") String status,
                      Pageable pageable);
}