package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.UserDtos;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.service.AddressService;
import org.liuym.flowerv1springboot.vo.SupportViews.AddressView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 收货地址：归属边界取会话用户，不接受前端传 userId
 */
@RestController
@RequestMapping("/api/addresses")
@Tag(name = "前台 · 收货地址")
public class AddressController {

    @Autowired
    private AddressService addressService;

    @GetMapping
    public Result<List<AddressView>> list(HttpSession session) {
        return Result.ok(addressService.list(userId(session)));
    }

    @GetMapping("/default")
    public Result<AddressView> defaultAddress(HttpSession session) {
        Address address = addressService.defaultAddress(userId(session));
        return Result.ok(address == null ? null : AddressView.from(address));
    }

    @PostMapping
    public Result<AddressView> create(@Valid @RequestBody UserDtos.AddressForm form, HttpSession session) {
        return Result.ok("地址已保存", addressService.create(userId(session), form));
    }

    @PutMapping("/{id}")
    public Result<AddressView> update(@PathVariable UUID id,
                                      @Valid @RequestBody UserDtos.AddressForm form,
                                      HttpSession session) {
        return Result.ok("地址已更新", addressService.update(userId(session), id, form));
    }

    @PatchMapping("/{id}/default")
    public Result<AddressView> setDefault(@PathVariable UUID id, HttpSession session) {
        return Result.ok("已设为默认地址", addressService.setDefault(userId(session), id));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable UUID id, HttpSession session) {
        addressService.delete(userId(session), id);
        return Result.ok("地址已删除", null);
    }

    private UUID userId(HttpSession session) {
        return CurrentUser.require(session).getId();
    }
}
