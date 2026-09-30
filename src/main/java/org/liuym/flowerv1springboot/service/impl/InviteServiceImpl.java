package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.InviteCode;
import org.liuym.flowerv1springboot.model.InviteRelation;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.CouponRepository;
import org.liuym.flowerv1springboot.repository.InviteCodeRepository;
import org.liuym.flowerv1springboot.repository.InviteRelationRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.InviteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 邀请有礼（E16）：码生成与绑定靠唯一索引兜并发，首单奖励用条件 UPDATE 抢占，
 * 保证「同一笔首单只发一次奖、同一个人只被邀请一次」。
 */
@Service
@Transactional
public class InviteServiceImpl implements InviteService {

    private static final Logger log = LoggerFactory.getLogger(InviteServiceImpl.class);

    /** 邀请码字符集：去掉 0/O/1/I，用户手抄不会抄错 */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    @Autowired
    private InviteRelationRepository inviteRelationRepository;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponService couponService;

    @Override
    public InviteCode myCode(UUID userId) {
        Optional<InviteCode> existing = inviteCodeRepository.findByUserIdAndRevokedAtIsNull(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return inviteCodeRepository.saveAndFlush(buildCode(userId));
        } catch (DataIntegrityViolationException e) {
            // 并发点两次「生成邀请码」只会插入一条，冲突方直接读回已有那张
            return inviteCodeRepository.findByUserIdAndRevokedAtIsNull(userId)
                    .orElseThrow(() -> new BusinessException("邀请码生成失败，请稍后重试"));
        }
    }

    private InviteCode buildCode(UUID userId) {
        InviteCode code = new InviteCode();
        code.setUserId(userId);
        code.setCode(freshCode());
        UUID reward = sceneCouponId();
        code.setInviteeCouponId(reward);
        code.setInviterCouponId(reward);
        code.setRewardPoints(0);
        code.setInvitedCount(0);
        code.setRewardedCount(0);
        return code;
    }

    /** 奖励券模板由后台按 invite 场景维护；没配就只记邀请关系，不阻断绑定 */
    private UUID sceneCouponId() {
        List<Coupon> candidates = couponRepository.findByScene(Coupon.SCENE_INVITE);
        return candidates.isEmpty() ? null : candidates.get(0).getId();
    }

    private String freshCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder builder = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                builder.append(CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)]);
            }
            String candidate = builder.toString();
            if (inviteCodeRepository.findByCodeAndRevokedAtIsNull(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BusinessException("邀请码池已满，请联系管理员");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InviteCode> findByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return inviteCodeRepository.findByCodeAndRevokedAtIsNull(code.trim().toUpperCase());
    }

    @Override
    public String bind(String rawCode, UUID inviteeId) {
        if (inviteeId == null) {
            throw new BusinessException(401, "请先登录再绑定邀请码");
        }
        if (inviteRelationRepository.findByInviteeId(inviteeId).isPresent()) {
            return "你已经绑定过邀请关系了";
        }
        InviteCode code = findByCode(rawCode).orElseThrow(() -> new BusinessException("邀请码不存在或已失效"));
        if (code.getUserId().equals(inviteeId)) {
            throw new BusinessException("不能使用自己的邀请码");
        }
        // 老账号绑了也拿不到首单奖励，直接说清比假装成功更诚实
        if (!couponService.isNewCustomer(inviteeId)) {
            throw new BusinessException("该账号已有成交订单，只有新账号可以绑定邀请码");
        }
        InviteRelation relation = new InviteRelation();
        relation.setInviteCodeId(code.getId());
        relation.setInviterId(code.getUserId());
        relation.setInviteeId(inviteeId);
        relation.setRewardStatus(InviteRelation.STATUS_PENDING);
        try {
            inviteRelationRepository.saveAndFlush(relation);
        } catch (DataIntegrityViolationException e) {
            return "你已经绑定过邀请关系了";
        }
        inviteCodeRepository.increaseInvitedCount(code.getId());
        log.info("邀请绑定 inviter={} invitee={} code={}", code.getUserId(), inviteeId, code.getCode());
        return "绑定成功，首单支付后双方都会拿到奖励";
    }

    @Override
    @Transactional(readOnly = true)
    public List<InviteRelation> invitedBy(UUID inviterId) {
        return inviteRelationRepository.findByInviterIdOrderByCreatedAtDesc(inviterId);
    }

    /**
     * 首单奖励：条件 UPDATE 把关系从 pending 抢成 rewarded，抢到才发奖，
     * 支付回调重放或多端并发都只会发一份。
     */
    @Override
    public String grantFirstOrderReward(UUID inviteeId, UUID orderId, BigDecimal payAmount) {
        if (inviteeId == null || orderId == null) {
            return null;
        }
        InviteRelation relation = inviteRelationRepository.findByInviteeId(inviteeId).orElse(null);
        if (relation == null || !InviteRelation.STATUS_PENDING.equals(relation.getRewardStatus())) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        if (payAmount != null && payAmount.signum() <= 0) {
            inviteRelationRepository.markSkipped(relation.getId(), orderId, now);
            return null;
        }
        if (inviteRelationRepository.markRewarded(relation.getId(), InviteRelation.STATUS_REWARDED, orderId, now) == 0) {
            return null;
        }
        inviteCodeRepository.increaseRewardedCount(relation.getInviteCodeId());

        InviteCode code = inviteCodeRepository.findById(relation.getInviteCodeId()).orElse(null);
        String rewardName = null;
        if (code != null) {
            // sourceRef 传关系 id：同一份邀请关系重复回调被条件唯一索引挡住
            UserCoupon inviteeCoupon = couponService.grantByScene(inviteeId, code.getInviteeCouponId(),
                    UserCoupon.SOURCE_INVITE, relation.getId());
            UserCoupon inviterCoupon = couponService.grantByScene(code.getUserId(), code.getInviterCouponId(),
                    UserCoupon.SOURCE_INVITE, relation.getId());
            if (inviteeCoupon != null) {
                rewardName = inviteeCoupon.getName();
            } else if (inviterCoupon != null) {
                rewardName = inviterCoupon.getName();
            }
            if (code.getRewardPoints() != null && code.getRewardPoints() > 0) {
                userRepository.addPoints(code.getUserId(), code.getRewardPoints());
            }
        }
        log.info("邀请首单奖励已发 invitee={} order={} 券={}", inviteeId, orderId, rewardName);
        return rewardName == null ? "首单奖励已记录" : "首单奖励已发放：" + rewardName;
    }

    @Override
    @Transactional(readOnly = true)
    public long countInvited(UUID inviterId) {
        return inviteRelationRepository.countByInviterId(inviterId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countRewarded(UUID inviterId) {
        return inviteRelationRepository.countByInviterIdAndRewardStatus(inviterId, InviteRelation.STATUS_REWARDED);
    }

    @Override
    @Transactional(readOnly = true)
    public String displayName(UUID userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return "花友";
        }
        String name = user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
        return name == null || name.isBlank() ? "花友" : name;
    }
}
