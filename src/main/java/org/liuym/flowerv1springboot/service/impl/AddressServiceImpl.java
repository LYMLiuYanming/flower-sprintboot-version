package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.model.User;
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

    /** 单用户地址上限，防止刷数据 */
    private static final long MAX_PER_USER = 20;

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
        if (addressRepository.countByUserId(userId) >= MAX_PER_USER) {
            throw new BusinessException("收货地址数量已达上限（" + MAX_PER_USER + " 条）");
        }
        Address address = new Address();
        address.setUser(userRepository.getReferenceById(userId));
        applyForm(address, form);
        boolean first = addressRepository.countByUserId(userId) == 0;
        if (first) {
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
        addressRepository.delete(address);
        if (Boolean.TRUE.equals(address.getIsDefault())) {
            List<Address> rest = addressRepository.findByUserIdOrderByIsDefaultDescCreatedAtDesc(userId);
            if (!rest.isEmpty()) {
                rest.get(0).setIsDefault(true);
                addressRepository.save(rest.get(0));
            }
        }
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
        address.setTag(trim(form.tag()));
        address.setIsDefault(Boolean.TRUE.equals(form.isDefault()));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
