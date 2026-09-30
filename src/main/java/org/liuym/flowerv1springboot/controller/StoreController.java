package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.AddressService;
import org.liuym.flowerv1springboot.service.FlowerMapService;
import org.liuym.flowerv1springboot.vo.StoreViews;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 门店自提地图与就近推荐（I15）。
 *
 * <p>页面与接口都匿名可看：门店地址、电话、营业时间属于公共信息，登录与否只影响能不能带出「默认收货地址」做就近排序。
 * 数据全部来自 flower_origin 里 kind = store 的节点，后台（I01）新增门店后这里立刻可见。
 */
@Controller
@Tag(name = "前台 · 自提门店")
public class StoreController {

    private final FlowerMapService flowerMapService;
    private final AddressService addressService;

    public StoreController(FlowerMapService flowerMapService, AddressService addressService) {
        this.flowerMapService = flowerMapService;
        this.addressService = addressService;
    }

    @GetMapping("/stores")
    @Operation(summary = "自提门店页")
    public String storesPage(Model model, HttpSession session) {
        // 带出默认地址只是为了少让顾客打一次字；取不到就留空，页面按城市列表展示
        User me = CurrentUser.of(session);
        Address address = me == null ? null : addressService.defaultAddress(me.getId());
        model.addAttribute("nearbyAddress", address == null ? "" : address.fullAddress());
        model.addAttribute("stores", flowerMapService.stores(null));
        return "shop/stores";
    }

    @GetMapping("/api/stores")
    @ResponseBody
    @Operation(summary = "门店列表", description = "city 为空返回全部启用门店")
    public Result<List<StoreViews.Store>> list(@RequestParam(required = false) String city) {
        List<StoreViews.Store> stores = flowerMapService.stores(city);
        return Result.page(stores, stores.size());
    }

    @GetMapping("/api/stores/cities")
    @ResponseBody
    @Operation(summary = "门店城市清单", description = "页面筛选项，按已有门店去重")
    public Result<List<String>> cities() {
        List<String> cities = flowerMapService.stores(null).stream()
                .map(StoreViews.Store::city)
                .filter(city -> city != null && !city.isBlank())
                .distinct()
                .collect(Collectors.toList());
        return Result.ok(cities);
    }

    @GetMapping("/api/stores/nearby")
    @ResponseBody
    @Operation(summary = "就近门店（I15）", description = "同城优先再按直线距离；地址定不到位时返回全部门店并给出原因")
    public Result<StoreViews.Nearby> nearby(@RequestParam(required = false) String address,
                                            @RequestParam(defaultValue = "5") int limit) {
        return Result.ok(flowerMapService.nearbyStores(address, Math.min(Math.max(limit, 1), 20)));
    }
}
