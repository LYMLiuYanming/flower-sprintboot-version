package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.model.FlowerOrigin;
import org.liuym.flowerv1springboot.service.FlowerMapService;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 产地 / 分拨中心 / 自提门店的后台维护（I01）。
 *
 * <p>用 @Controller 而不是 @RestController：页面路由 /admin/origin-list 要返回 Thymeleaf 视图名，
 * 接口方法逐个标 @ResponseBody，这样不必把视图注册塞进别人持有的 AdminController。
 * 鉴权与写操作留痕由 AuthInterceptor（/admin/**、/api/admin/**）和 AdminAuditFilter 统一负责。
 */
@Controller
@Tag(name = "后台 · 地图节点维护")
public class OriginAdminController {

    private final FlowerMapService flowerMapService;

    public OriginAdminController(FlowerMapService flowerMapService) {
        this.flowerMapService = flowerMapService;
    }

    @GetMapping("/admin/origin-list")
    public String originListPage() {
        return "admin/origin-list";
    }

    @GetMapping("/api/admin/origins")
    @ResponseBody
    @Operation(summary = "节点列表", description = "含停用节点；kind 为空返回全部，keyword 命中名称/城市/省/品种")
    public Result<List<GeoViews.OriginNode>> list(@RequestParam(required = false) String kind,
                                                  @RequestParam(required = false) String keyword) {
        List<GeoViews.OriginNode> nodes = flowerMapService.adminNodes(kind, keyword);
        return Result.page(nodes, nodes.size());
    }

    @GetMapping("/api/admin/origins/cities")
    @ResponseBody
    @Operation(summary = "已有城市清单", description = "新增节点时的候选城市，含内置质心字典里的城市")
    public Result<List<String>> cities() {
        return Result.ok(flowerMapService.knownCities());
    }

    @GetMapping("/api/admin/origins/pick")
    @ResponseBody
    @Operation(summary = "地址拾取坐标", description = "按地址取 GCJ-02 坐标：高德优先，退到内置城市质心；只回坐标与口径，不回密钥")
    public Result<GeoViews.GeocodeResult> pick(@RequestParam String address) {
        return Result.ok(flowerMapService.pickCoordinate(address));
    }

    @GetMapping("/api/admin/origins/{id}/products")
    @ResponseBody
    @Operation(summary = "节点关联商品", description = "删除前确认引用关系（I02）")
    public Result<List<GeoViews.LinkedProduct>> products(@PathVariable UUID id) {
        return Result.ok(flowerMapService.productsOfOrigin(id));
    }

    @PostMapping("/api/admin/origins")
    @ResponseBody
    @Operation(summary = "新增节点", description = "坐标缺失时按地址 → 高德 → 城市质心兜底，三者都取不到则报错")
    public Result<GeoViews.OriginNode> create(@Valid @RequestBody Form form) {
        return Result.ok("已新增节点", flowerMapService.saveOrigin(form.toEntity(null)));
    }

    @PutMapping("/api/admin/origins/{id}")
    @ResponseBody
    @Operation(summary = "更新节点", description = "节点类型创建后不可改；坐标越界会被拒绝")
    public Result<GeoViews.OriginNode> update(@PathVariable UUID id, @Valid @RequestBody Form form) {
        return Result.ok("已保存", flowerMapService.saveOrigin(form.toEntity(id)));
    }

    @PostMapping("/api/admin/origins/{id}/active")
    @ResponseBody
    @Operation(summary = "启用 / 停用", description = "停用后地图与自提页不再出现该节点")
    public Result<Void> active(@PathVariable UUID id, @RequestParam boolean active) {
        return flowerMapService.setOriginActive(id, active)
                ? Result.ok(active ? "已启用" : "已停用", null)
                : Result.error("状态没有变化");
    }

    @DeleteMapping("/api/admin/origins/{id}")
    @ResponseBody
    @Operation(summary = "下架节点", description = "被商品引用时改为停用并返回提示，不做物理删除；data 是给运营看的原因")
    public Result<String> retire(@PathVariable UUID id) {
        return Result.ok("已处理", flowerMapService.retireOrigin(id));
    }

    /**
     * 后台表单：字段与 flower_origin 一一对应。
     * 嵌套在控制器里而不塞进 dto/*Dtos，是因为这套字段只服务本页面，不该让别批次的数据类长出来。
     */
    public record Form(UUID id,
                       @NotBlank(message = "请填写节点名称") @Size(max = 60, message = "名称不超过 60 字") String name,
                       @Size(max = 16) String kind,
                       @Size(max = 40) String province,
                       @Size(max = 40) String city,
                       @Size(max = 12) String adcode,
                       BigDecimal lng, BigDecimal lat, Integer altitude,
                       @Size(max = 255) String flowers,
                       @Size(max = 120) String feature,
                       @Size(max = 500) String story,
                       @Size(max = 60) String season,
                       @Size(max = 255, message = "图片地址不超过 255 字") String imageUrl,
                       @Size(max = 160) String address,
                       @Size(max = 20) String phone,
                       @Size(max = 60) String openHours,
                       Integer pickupReadyMinutes,
                       Integer sortOrder, Boolean active) {

        FlowerOrigin toEntity(UUID pathId) {
            FlowerOrigin entity = new FlowerOrigin();
            entity.setId(pathId != null ? pathId : id);
            entity.setName(name);
            entity.setKind(kind);
            entity.setProvince(province);
            entity.setCity(city);
            entity.setAdcode(adcode);
            entity.setLng(lng);
            entity.setLat(lat);
            entity.setAltitude(altitude);
            entity.setFlowers(flowers);
            entity.setFeature(feature);
            entity.setStory(story);
            entity.setSeason(season);
            // 图片地址的格式校验放在服务层统一做（只收站内相对路径），控制器不重复一套规则
            entity.setImageUrl(imageUrl);
            entity.setAddress(address);
            entity.setPhone(phone);
            entity.setOpenHours(openHours);
            entity.setPickupReadyMinutes(pickupReadyMinutes);
            entity.setSortOrder(sortOrder);
            entity.setIsActive(active);
            return entity;
        }
    }
}
