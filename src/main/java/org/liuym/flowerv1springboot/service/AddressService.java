package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.common.AddressParser;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.vo.SupportViews.AddressView;

import java.util.List;
import java.util.UUID;

public interface AddressService {

    /** 单用户地址上限（D09），控制器回给前端做「还能加几条」提示 */
    int MAX_PER_USER = 20;

    List<AddressView> list(UUID userId);

    AddressView create(UUID userId, UserDtos.AddressForm form);

    AddressView update(UUID userId, UUID addressId, UserDtos.AddressForm form);

    void delete(UUID userId, UUID addressId);

    /** 批量删除（D08），返回实际删除条数；越权或不存在的 id 直接跳过，不误删他人地址 */
    int deleteBatch(UUID userId, List<UUID> ids);

    AddressView setDefault(UUID userId, UUID addressId);

    Address defaultAddress(UUID userId);

    /** 智能识别（D10）：把一段文本拆成姓名/电话/省市区/详址，纯函数不落库 */
    AddressParser.Parsed parse(String raw);
}
