package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户业务层接口
 */
public interface UserService {

    /**
     * 新增用户
     * @param user 用户实体
     * @return 保存后的用户实体
     */
    User save(User user);

    /**
     * 批量新增用户
     * @param users 用户实体列表
     * @return 保存后的用户实体列表
     */
    List<User> saveAll(List<User> users);

    /**
     * 根据ID查询用户
     * @param id 用户ID（UUID）
     * @return  Optional 包装的用户实体
     */
    Optional<User> findById(UUID id);

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return Optional 包装的用户实体（用户名唯一）
     */
    Optional<User> findByUsername(String username);

    /**
     * 根据手机号查询用户
     * @param phone 手机号
     * @return Optional 包装的用户实体（手机号唯一）
     */
    Optional<User> findByPhone(String phone);

    /**
     * 根据邮箱查询用户
     * @param email 邮箱
     * @return Optional 包装的用户实体（邮箱唯一）
     */
    Optional<User> findByEmail(String email);

    /**
     * 查询所有用户（分页）
     * @param pageable 分页参数（页码、每页条数、排序规则等）
     * @return 分页用户列表
     */
    Page<User> findAll(Pageable pageable);

    /**
     * 根据用户类型查询用户（分页）
     * @param userType 用户类型（customer/admin）
     * @param pageable 分页参数
     * @return 分页用户列表
     */
    Page<User> findByUserType(String userType, Pageable pageable);

    /**
     * 根据会员等级查询用户（分页）
     * @param memberLevel 会员等级（ordinary/vip）
     * @param pageable 分页参数
     * @return 分页用户列表
     */
    Page<User> findByMemberLevel(String memberLevel, Pageable pageable);

    /**
     * 根据账号状态查询用户（分页）
     * @param status 账号状态（active/inactive/locked）
     * @param pageable 分页参数
     * @return 分页用户列表
     */
    Page<User> findByStatus(String status, Pageable pageable);

    /**
     * 更新用户信息（全量更新）
     * @param user 用户实体（需包含ID）
     * @return 更新后的用户实体
     */
    User update(User user);

    /**
     * 校验用户名密码（兼容历史明文密码）
     * @param user 用户实体
     * @param rawPassword 用户输入的原始密码
     * @return 校验通过返回 true
     */
    boolean checkPassword(User user, String rawPassword);

    /**
     * 登录认证：校验用户名密码，兼容历史明文密码并在登录成功后自动升级为 BCrypt 哈希
     * @param username 用户名
     * @param rawPassword 用户输入的原始密码
     * @return 认证通过返回用户实体，否则返回 Optional.empty()
     */
    Optional<User> authenticate(String username, String rawPassword);

    /**
     * 更新用户密码
     * @param id 用户ID
     * @param newPassword 新密码（明文，内部自动加密）
     * @return 是否更新成功
     */
    boolean updatePassword(UUID id, String newPassword);

    /**
     * 更新用户状态
     * @param id 用户ID
     * @param status 新状态（active/inactive/locked）
     * @return 是否更新成功
     */
    boolean updateStatus(UUID id, String status);

    /**
     * 更新最后登录时间
     * @param id 用户ID
     * @param lastLoginTime 最后登录时间
     * @return 是否更新成功
     */
    boolean updateLastLoginTime(UUID id, LocalDateTime lastLoginTime);

    /**
     * 累加用户积分
     * @param id 用户ID
     * @param points 要累加的积分（正数增加，负数减少）
     * @return 更新后的积分值
     */
    Integer addPoints(UUID id, Integer points);

    /**
     * 根据ID删除用户
     * @param id 用户ID
     * @return 是否删除成功
     */
    boolean deleteById(UUID id);

    /**
     * 批量删除用户
     * @param ids 用户ID列表
     */
    void deleteAllById(List<UUID> ids);

    /**
     * 检查用户名是否已存在
     * @param username 用户名
     * @return 存在返回true，否则false
     */
    boolean existsByUsername(String username);

    /**
     * 检查手机号是否已存在
     * @param phone 手机号
     * @return 存在返回true，否则false
     */
    boolean existsByPhone(String phone);

    /**
     * 检查邮箱是否已存在
     * @param email 邮箱
     * @return 存在返回true，否则false
     */
    boolean existsByEmail(String email);

    /**
     * 查询用户总数
     * @return 用户总数
     */
    long count();

    /**
     * 后台多条件筛选用户（关键词 / 用户类型 / 账号状态），空参数表示不过滤
     */
    Page<User> search(String keyword, String userType, String status, Pageable pageable);

    /**
     * 根据用户类型统计用户数量
     * @param userType 用户类型
     * @return 对应类型的用户数量
     */
    long countByUserType(String userType);

    /**
     * 注册新用户（口令由实现层统一转 BCrypt）
     */
    User register(String username, String rawPassword, String fullName, String phone, String email);

    /**
     * 服务端注册校验（D01/D02/D03）：用户名格式、手机号格式、密码强度。
     * 任一项不合法即抛 BusinessException 给出可读原因；重名/重号由控制器再查库。
     */
    void validateRegistration(String username, String rawPassword, String phone, String email);

    /**
     * 修改个人资料，手机号/邮箱唯一性由实现层校验
     */
    User updateProfile(UUID id, UserDtos.ProfileRequest form);

    /**
     * 修改密码：必须校验原密码，成功后清除强制改密标记
     */
    void changePassword(UUID id, String oldRawPassword, String newRawPassword);

    /**
     * 提交注销申请（D16）：软删除进入冷静期，记录申请时间，数据原样保留。
     * 已在冷静期内的重复调用视为幂等，不刷新申请时间。
     */
    void requestDeletion(UUID id);

    /**
     * 撤销注销申请（D16）：清空申请时间，账号恢复正常。
     */
    void cancelDeletion(UUID id);

    /**
     * 冷静期已届满的注销账号执行匿名化（D16 定时任务入口），返回处理条数。
     */
    int processExpiredDeletions();

    /**
     * 管理员重置密码，mustChangePassword 置为 true 迫使用户下次登录改密
     * @return 重置后的初始密码
     */
    String resetPasswordByAdmin(UUID id, String newRawPassword);

    /**
     * 记录最后登录时间
     */
    void touchLastLogin(UUID id);
}