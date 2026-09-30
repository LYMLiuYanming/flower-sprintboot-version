package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.service.FlowerMapService;
import org.liuym.flowerv1springboot.service.WeatherService;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.liuym.flowerv1springboot.vo.StoreViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 鲜花地图出口：产地与物流节点、销量分布、订单路线、门店自提与城市天气。
 * 数据允许为空（外部地图服务不可用时），但接口本身不会 500，页面据此降级。
 * 响应里没有任何密钥字段——JS API key 与安全密钥只由 common/amap.html 注入页面脚本
 */
@RestController
@RequestMapping("/api/map")
@Tag(name = "前台 · 鲜花地图")
public class MapController {

    /** 窗口期上限两年，下限一天，避免 days=0 或超大值把聚合拖垮 */
    private static final int MAX_DAYS = 730;

    private final FlowerMapService flowerMapService;
    private final WeatherService weatherService;

    public MapController(FlowerMapService flowerMapService, WeatherService weatherService) {
        this.flowerMapService = flowerMapService;
        this.weatherService = weatherService;
    }

    @GetMapping("/nodes")
    @Operation(summary = "地图节点", description = "产地 / 分拨中心 / 自提门店，含窗口期内发货量")
    public Result<List<GeoViews.OriginNode>> nodes(@RequestParam(defaultValue = "180") int days) {
        return Result.ok(flowerMapService.nodes(clampDays(days)));
    }

    @GetMapping("/sales")
    @Operation(summary = "销量城市分布", description = "按收货城市聚合的成交单量、枝数与金额；province 给定时只返回该省（I05 下钻）")
    public Result<List<GeoViews.CitySales>> sales(@RequestParam(defaultValue = "180") int days,
                                                  @RequestParam(required = false) String province) {
        return Result.ok(flowerMapService.salesByCity(clampDays(days), province));
    }

    @GetMapping("/sales/provinces")
    @Operation(summary = "销量省份聚合", description = "省份下钻入口，坐标为该省按枝数加权的城市质心均值")
    public Result<List<GeoViews.ProvinceSales>> salesByProvince(@RequestParam(defaultValue = "180") int days) {
        return Result.ok(flowerMapService.salesByProvince(clampDays(days)));
    }

    @GetMapping("/origin/{originId}")
    @Operation(summary = "产地详情", description = "节点信息 + 关联商品列表（I02）")
    public Result<GeoViews.OriginDetail> originDetail(@PathVariable UUID originId,
                                                      @RequestParam(defaultValue = "180") int days) {
        return Result.ok(flowerMapService.originDetail(originId, clampDays(days)));
    }

    @GetMapping("/origin/{originId}/products")
    @Operation(summary = "产地关联商品", description = "该产地作为主产地的商品，含下架商品，前端按 active 分组显示")
    public Result<List<GeoViews.LinkedProduct>> originProducts(@PathVariable UUID originId) {
        return Result.ok(flowerMapService.productsOfOrigin(originId));
    }

    @GetMapping("/product/{productId}/origin")
    @Operation(summary = "商品主产地", description = "详情页「这束花来自哪里」用；未标注时 data 为空")
    public Result<GeoViews.OriginNode> productOrigin(@PathVariable UUID productId) {
        return Result.ok(flowerMapService.originOfProduct(productId).orElse(null));
    }

    @GetMapping("/order/{orderId}/route")
    @Operation(summary = "订单运输路线", description = "产地 → 分拨中心 → 收花点的线段与估算里程，地理编码可用时叠加实测折线，仅本人订单可见")
    public Result<GeoViews.OrderRoute> orderRoute(@PathVariable UUID orderId, HttpSession session) {
        return Result.ok(flowerMapService.routeOfOrder(orderId, CurrentUser.require(session).getId()));
    }

    @GetMapping("/weather")
    @Operation(summary = "配送城市天气", description = "按地址或城市名取实况天气与鲜花养护建议；查不到时 data 为空")
    public Result<GeoViews.WeatherCard> weather(@RequestParam String address) {
        return Result.ok(weatherService.forAddress(address).orElse(null));
    }

    @GetMapping("/weather/forecast")
    @Operation(summary = "未来天气预报（I08）", description = "默认未来 3 日，逐日给出配送建议；外部接口不可用时 state=failed")
    public Result<GeoViews.ForecastCard> forecast(@RequestParam String address,
                                                  @RequestParam(defaultValue = "3") int days) {
        return Result.ok(weatherService.forecast(address, Math.min(Math.max(days, 1), 7)));
    }

    @GetMapping("/weather/status")
    @Operation(summary = "天气服务可用性（I09）", description = "区分未配密钥 / 接口失败 / 城市无法识别，供页面写可读的空状态")
    public Result<GeoViews.ServiceStatus> weatherStatus(@RequestParam(required = false) String address) {
        return Result.ok(weatherService.status(address));
    }

    @GetMapping("/geocode")
    @Operation(summary = "地址取点（I16）", description = "高德优先、内置城市质心兜底；resolution 标明取点精度")
    public Result<GeoViews.GeocodeResult> geocode(@RequestParam String address) {
        return Result.ok(flowerMapService.resolveAddress(address));
    }

    @GetMapping("/diagnostics")
    @Operation(summary = "外部依赖体检（I16）",
            description = "取点与天气的缓存命中率、有效期与最近一次失败原因；只回计数与可读文案，不含密钥")
    public Result<GeoViews.ExternalStatus> diagnostics() {
        return Result.ok(flowerMapService.diagnostics());
    }

    @GetMapping("/stores")
    @Operation(summary = "自提门店列表（I15）", description = "city 为空返回全部启用门店，含地址、电话、营业时间与备花时长")
    public Result<List<StoreViews.Store>> stores(@RequestParam(required = false) String city) {
        return Result.ok(flowerMapService.stores(city));
    }

    @GetMapping("/stores/nearby")
    @Operation(summary = "就近门店推荐（I15）", description = "按收货地址同城优先 + 直线距离排序，并给出预计可取时间")
    public Result<StoreViews.Nearby> nearbyStores(@RequestParam(required = false) String address,
                                                  @RequestParam(defaultValue = "5") int limit) {
        return Result.ok(flowerMapService.nearbyStores(address, Math.min(Math.max(limit, 1), 20)));
    }

    private static int clampDays(int days) {
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }
}
