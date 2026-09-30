package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.AccountPolicy;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户业务层实现类
 */
@Service
@Transactional // 事务管理：增删改操作默认开启事务，查询操作无事务?
public class UserServiceImpl implements UserService {

    /** BCrypt 哈希存储时以 $2 开头，用于识别历史明文密码 */
    private static final String BCRYPT_PREFIX = "$2";

    // 注入用户数据访问层（需自行创建 UserRepository 接口，继承 JpaRepository）
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    public User save(User user) {
        // 新增用户时自动将明文密码加密为 BCrypt 哈希
        encodePasswordIfNeeded(user);
        return userRepository.save(user);
    }

    @Override
    public List<User> saveAll(List<User> users) {
        // 批量新增时统一加密密码
        users.forEach(this::encodePasswordIfNeeded);
        return userRepository.saveAll(users);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    @Override
    public Optional<User> findByPhone(String phone) {
        return userRepository.findByPhone(phone);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Override
    public Page<User> findAll(Pageable pageable) {
        return userRepository.findAll(pageable);
    }

    @Override
    public Page<User> findByUserType(String userType, Pageable pageable) {
        return userRepository.findByUserType(userType, pageable);
    }

    @Override
    public Page<User> findByMemberLevel(String memberLevel, Pageable pageable) {
        return userRepository.findByMemberLevel(memberLevel, pageable);
    }

    @Override
    public Page<User> findByStatus(String status, Pageable pageable) {
        return userRepository.findByStatus(status, pageable);
    }

    @Override
    public User update(User user) {
        // 校验用户是否存在
        if (!userRepository.existsById(user.getId())) {
            throw new RuntimeException("用户不存在，更新失败！用户ID：" + user.getId());
        }
        // 可添加更新前的校验逻辑（如：不允许修改用户名、手机号等唯一字段）
        encodePasswordIfNeeded(user);
        return userRepository.save(user);
    }

    @Override
    public boolean checkPassword(User user, String rawPassword) {
        if (user == null || user.getPassword() == null || rawPassword == null) {
            return false;
        }
        String stored = user.getPassword();
        if (stored.startsWith(BCRYPT_PREFIX)) {
            return passwordEncoder.matches(rawPassword, stored);
        }
        // 历史明文密码：直接比对，登录成功后由 authenticate 升级为哈希
        return stored.equals(rawPassword);
    }

    @Override
    @Transactional
    public Optional<User> authenticate(String username, String rawPassword) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !checkPassword(user, rawPassword)) {
            return Optional.empty();
        }
        // 旧明文密码无 $2 前缀，登录成功后顺带升级为 BCrypt 哈希
        if (!user.getPassword().startsWith(BCRYPT_PREFIX)) {
            user.setPassword(passwordEncoder.encode(rawPassword));
            userRepository.save(user);
        }
        return Optional.of(user);
    }

    @Override
    @Transactional
    public boolean updatePassword(UUID id, String newPassword) {
        // 传入明文密码，内部统一加密后存储
        String encoded = newPassword != null && newPassword.startsWith(BCRYPT_PREFIX)
                ? newPassword
                : passwordEncoder.encode(newPassword);
        int affectedRows = userRepository.updatePassword(id, encoded);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    public boolean updateStatus(UUID id, String status) {
        // 校验状态值合法性
        if (!List.of("active", "inactive", "locked").contains(status)) {
            throw new IllegalArgumentException("无效的用户状态：" + status);
        }
        int affectedRows = userRepository.updateStatus(id, status);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    public boolean updateLastLoginTime(UUID id, LocalDateTime lastLoginTime) {
        int affectedRows = userRepository.updateLastLoginTime(id, lastLoginTime);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    public Integer addPoints(UUID id, Integer points) {
        if (points == null) {
            throw new BusinessException("积分参数不能为空");
        }
        // 条件更新，积分余额不足时不会把点数扣成负数
        if (userRepository.addPoints(id, points) == 0) {
            throw new BusinessException("可用积分不足");
        }
        return userRepository.findById(id).map(User::getPoints).orElse(0);
    }

    @Override
    @Transactional
    public boolean deleteById(UUID id) {
        if (userRepository.existsById(id)) {
            userRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    @Transactional
    public void deleteAllById(List<UUID> ids) {
        userRepository.deleteAllById(ids);
    }

    @Override
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return userRepository.existsByPhone(phone);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userRepository.existsByEmail(email);
    }

    @Override
    public long count() {
        return userRepository.count();
    }

    @Override
    public long countByUserType(String userType) {
        return userRepository.countByUserType(userType);
    }

    @Override
    public Page<User> search(String keyword, String userType, String status, Pageable pageable) {
        // LIKE 参数必须传空串而非 null，PostgreSQL 才能推断出参数类型
        return userRepository.search(keyword == null ? "" : keyword.trim(), userType, status, pageable);
    }


    @Override
    @Transactional
    public User register(String username, String rawPassword, String fullName, String phone, String email) {
        User user = new User();
        user.setUsername(username.trim());
        user.setPassword(rawPassword);
        user.setFullName(fullName.trim());
        user.setPhone(phone.trim());
        user.setEmail(email);
        user.setUserType(User.TYPE_CUSTOMER);
        user.setMemberLevel(User.MEMBER_ORDINARY);
        user.setStatus(User.STATUS_ACTIVE);
        user.setPoints(0);
        user.setMustChangePassword(false);
        return userRepository.save(user);
    }

    @Override
    public void validateRegistration(String username, String rawPassword, String phone, String email) {
        // 用户名格式（D01）
        String usernameErr = AccountPolicy.usernameError(username);
        if (usernameErr != null) {
            throw new BusinessException(usernameErr);
        }
        // 手机号格式（D02）
        String phoneErr = AccountPolicy.phoneError(phone);
        if (phoneErr != null) {
            throw new BusinessException(phoneErr);
        }
        // 密码强度（D03）
        String passwordErr = AccountPolicy.passwordError(rawPassword, username);
        if (passwordErr != null) {
            throw new BusinessException(passwordErr);
        }
        if (email != null && !email.isBlank() && email.length() > 100) {
            throw new BusinessException("邮箱长度超限");
        }
    }

    @Override
    @Transactional
    public User updateProfile(UUID id, UserDtos.ProfileRequest form) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        // 手机号在服务端再做一次强校验，防止绕过 @Valid
        String phoneErr = AccountPolicy.phoneError(form.phone());
        if (phoneErr != null) {
            throw new BusinessException(phoneErr);
        }
        if (form.phone() != null && !form.phone().equals(user.getPhone())
                && userRepository.existsByPhone(form.phone())) {
            throw new BusinessException("手机号已被其他账号使用");
        }
        String email = form.email() == null || form.email().isBlank() ? null : form.email().trim();
        if (email != null && !email.equals(user.getEmail()) && userRepository.existsByEmail(email)) {
            throw new BusinessException("邮箱已被其他账号使用");
        }
        user.setFullName(form.fullName().trim());
        user.setPhone(form.phone().trim());
        user.setEmail(email);
        // PUT 语义为整体提交：可选项留空即清空，避免页面删除后旧值仍在
        user.setGender(form.gender() == null || form.gender().isBlank() ? null : form.gender().trim());
        user.setBirthday(form.birthday());
        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void changePassword(UUID id, String oldRawPassword, String newRawPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (!checkPassword(user, oldRawPassword)) {
            throw new BusinessException("原密码不正确");
        }
        if (oldRawPassword != null && oldRawPassword.equals(newRawPassword)) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        // 已加密存储时，明文比对可能漏掉「旧密码=新密码」，再用 matches 兜一次
        if (user.getPassword() != null && user.getPassword().startsWith(BCRYPT_PREFIX)
                && passwordEncoder.matches(newRawPassword, user.getPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        // 新密码强度（D03/D04）
        String passwordErr = AccountPolicy.passwordError(newRawPassword, user.getUsername());
        if (passwordErr != null) {
            throw new BusinessException(passwordErr);
        }
        user.setPassword(passwordEncoder.encode(newRawPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public String resetPasswordByAdmin(UUID id, String newRawPassword) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        String initial = newRawPassword == null || newRawPassword.isBlank() ? randomInitialPassword() : newRawPassword;
        user.setPassword(passwordEncoder.encode(initial));
        user.setMustChangePassword(true);
        userRepository.save(user);
        return initial;
    }

    @Override
    @Transactional
    public void touchLastLogin(UUID id) {
        userRepository.updateLastLoginTime(id, LocalDateTime.now());
    }

    @Override
    @Transactional
    public void requestDeletion(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (!User.TYPE_CUSTOMER.equals(user.getUserType())) {
            throw new BusinessException("仅普通账号可自助注销，管理员账号请联系系统");
        }
        if (User.STATUS_DELETED.equals(user.getStatus())) {
            throw new BusinessException("该账号已完成注销");
        }
        // 幂等：已在冷静期内不刷新申请时间，避免反复点击把冷静期无限延后
        if (user.getDeletionRequestedAt() != null) {
            return;
        }
        userRepository.markDeletionRequested(id, LocalDateTime.now());
    }

    @Override
    @Transactional
    public void cancelDeletion(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("用户不存在"));
        if (User.STATUS_DELETED.equals(user.getStatus())) {
            throw new BusinessException("账号已注销完成，无法撤销，请联系客服");
        }
        userRepository.cancelDeletionRequest(id);
    }

    /**
     * 每天凌晨扫描冷静期已届满的注销账号做匿名化。软删除：只置终态 + 抹掉可识别信息，保留行与历史订单外键。
     */
    @Override
    @Scheduled(cron = "${account.deletion-sweep-cron:0 30 3 * * ?}")
    @Transactional
    public int processExpiredDeletions() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(AccountPolicy.DELETION_COOLDOWN_DAYS);
        List<User> expired = userRepository.findDeletionExpired(deadline);
        for (User user : expired) {
            userRepository.anonymizeForDeletion(user.getId());
        }
        return expired.size();
    }

    /**
     * 初始密码：8 位字母数字混合，仅用于管理员重置场景
     */
    private String randomInitialPassword() {
        java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
        StringBuilder letters = new StringBuilder();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            letters.append((char) ('a' + random.nextInt(26)));
        }
        for (int i = 0; i < 3; i++) {
            digits.append((char) ('0' + random.nextInt(10)));
        }
        return letters + "" + digits + "A1";
    }

    /**
     * 若密码为明文（非 BCrypt 哈希格式）则加密后再入库，已是哈希的不再重复加密
     */
    private void encodePasswordIfNeeded(User user) {
        String password = user.getPassword();
        if (password != null && !password.startsWith(BCRYPT_PREFIX)) {
            user.setPassword(passwordEncoder.encode(password));
        }
    }
}