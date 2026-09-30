package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.AccountPolicy;
import org.liuym.flowerv1springboot.common.AddressParser;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.repository.AddressRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.AddressService;
import org.liuym.flowerv1springboot.vo.SupportViews.AddressView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AddressServiceImpl implements AddressService {

    private final AddressRepository addressRepository;
    private final UserRepository userRepository;

    public AddressServiceImpl(AddressRepository addressRepository, UserRepository userRepository) {
        this.addressRepository = addressRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AddressView> list(UUID userId) {
        return AddressView.from(addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId));
    }

    @Override
    public AddressView create(UUID userId, UserDtos.AddressForm form) {
        // D09：数量上限，超限给出可读提示并带上具体条数
        long existing = addressRepository.countByUserId(userId);
        if (existing >= MAX_PER_USER) {
            throw new BusinessException("最多只能保存 " + MAX_PER_USER + " 个收货地址，请先删除不常用的再添加");
        }
        Address address = new Address();
        address.setUser(userRepository.getReferenceById(userId));
        applyForm(address, form);
        if (existing == 0) {
            address.setIsDefault(true);
        }
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            addressRepository.clearDefault(userId);
        }
        Address saved = addressRepository.save(address);
        return AddressView.from(saved);
    }

    @Override
    public AddressView update(UUID userId, UUID addressId, UserDtos.AddressForm form) {
        Address address = requireOwned(userId, addressId);
        applyForm(address, form);
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            addressRepository.clearDefault(userId);
        }
        return AddressView.from(addressRepository.save(address));
    }

    @Override
    public void delete(UUID userId, UUID addressId) {
        Address address = requireOwned(userId, addressId);
        boolean wasDefault = Boolean.TRUE.equals(address.getIsDefault());
        addressRepository.delete(address);
        if (wasDefault) {
            promoteFirstAsDefault(userId);
        }
    }

    @Override
    public int deleteBatch(UUID userId, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        boolean removedDefault = false;
        for (UUID id : ids) {
            // 逐个校验归属：deleteByIdAndUserId 的 where 带 user_id，越权 id 影响行数为 0
            int affected = addressRepository.deleteByIdAndUserId(id, userId);
            if (affected > 0) {
                removed++;
                // 用条件更新前无法得知是否默认，删除后再查剩余列表统一纠正，语义与单删一致
                removedDefault = true;
            }
        }
        if (removedDefault) {
            promoteFirstAsDefault(userId);
        }
        return removed;
    }

    @Override
    public AddressView setDefault(UUID userId, UUID addressId) {
        requireOwned(userId, addressId);
        addressRepository.clearDefault(userId);
        addressRepository.markDefault(addressId, userId);
        return AddressView.from(requireOwned(userId, addressId));
    }

    @Override
    @Transactional(readOnly = true)
    public Address defaultAddress(UUID userId) {
        return addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsDefault()))
                .findFirst()
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public AddressParser.Parsed parse(String raw) {
        return AddressParser.parse(raw);
    }

    /** 默认地址被删后把最新一条提为默认，保证「至少一个默认」的体验连续性 */
    private void promoteFirstAsDefault(UUID userId) {
        boolean stillHasDefault = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId).stream()
                .anyMatch(a -> Boolean.TRUE.equals(a.getIsDefault()));
        if (stillHasDefault) {
            return;
        }
        List<Address> rest = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
        if (!rest.isEmpty()) {
            addressRepository.clearDefault(userId);
            addressRepository.markDefault(rest.get(0).getId(), userId);
        }
    }

    private Address requireOwned(UUID userId, UUID addressId) {
        return addressRepository.findByIdAndUserId(addressId, userId)
                .orElseThrow(() -> BusinessException.forbidden("地址不存在或无权访问"));
    }

    private void applyForm(Address address, UserDtos.AddressForm form) {
        address.setReceiverName(form.receiverName().trim());
        address.setReceiverPhone(form.receiverPhone().trim());
        address.setProvince(trim(form.province()));
        address.setCity(trim(form.city()));
        address.setDistrict(trim(form.district()));
        address.setDetail(form.detail().trim());
        // D07：家/公司/学校为预设，也允许自定义短标签，统一走 AccountPolicy 归一化（去控制字符 + 限长）
        address.setTag(AccountPolicy.normalizeAddressTag(form.tag()));
        address.setIsDefault(Boolean.TRUE.equals(form.isDefault()));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
