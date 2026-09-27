package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.vo.SupportViews.AddressView;

import java.util.List;
import java.util.UUID;

public interface AddressService {

    List<AddressView> list(UUID userId);

    AddressView create(UUID userId, UserDtos.AddressForm form);

    AddressView update(UUID userId, UUID addressId, UserDtos.AddressForm form);

    void delete(UUID userId, UUID addressId);

    AddressView setDefault(UUID userId, UUID addressId);

    Address defaultAddress(UUID userId);
}
