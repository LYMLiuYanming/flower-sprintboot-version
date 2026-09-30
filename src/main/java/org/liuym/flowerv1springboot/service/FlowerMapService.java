package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.model.FlowerOrigin;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.liuym.flowerv1springboot.vo.StoreViews;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 鲜花地图：产地溯源、订单路线、销量分布、门店自提与节点后台维护的数据出口。
 * 所有方法都要能在「没配高德密钥 / 外部接口挂掉」时正常返回，页面靠兜底坐标渲染
 */
public interface FlowerMapService {

    /** 全部地图节点（产地 / 分拨中心 / 自提门店），带窗口期内的发货量 */
    List<GeoViews.OriginNode> nodes(int days);

    /** 某个商品的主产地，未标注时返回空，由页面显示「产地待标注」 */
    Optional<GeoViews.OriginNode> originOfProduct(UUID productId);

    /** 订单的运输路线：产地 → 分拨中心 → 收货城市（或自提门店），地理编码可用时叠加实测折线（I03） */
    GeoViews.OrderRoute routeOfOrder(UUID orderId, UUID userId);

    /**
     * 按收货城市聚合的成交分布（I05）。
     *
     * @param province 省份名，空表示不过滤；给定时只返回该省城市
     */
    List<GeoViews.CitySales> salesByCity(int days, String province);

    /** 按省份聚合的成交分布（I05 下钻入口） */
    List<GeoViews.ProvinceSales> salesByProvince(int days);

    /** 产地详情：节点 + 关联在售商品（I02） */
    GeoViews.OriginDetail originDetail(UUID originId, int days);

    /** 产地关联商品列表（I02），无在售商品时返回空列表 */
    List<GeoViews.LinkedProduct> productsOfOrigin(UUID originId);

    /** 地址取点（I16）：高德优先、内置城市质心兜底，永远返回一个带 resolution 的结果 */
    GeoViews.GeocodeResult resolveAddress(String address);

    /** 自提门店列表（I15），city 为空返回全部 */
    List<StoreViews.Store> stores(String city);

    /** 按收货地址的就近推荐（I15）：同城优先，其次直线距离 */
    StoreViews.Nearby nearbyStores(String addressOrCity, int limit);

    /* ---------- 后台维护（I01） ---------- */

    /** 后台节点列表：含停用节点，kind 为空返回全部 */
    List<GeoViews.OriginNode> adminNodes(String kind, String keyword);

    /**
     * 保存节点（新增或按 id 更新）。
     *
     * <p>坐标缺失时会尝试用 address 走地理编码，再退到 {@code CityGeo} 城市质心；
     * 三条路都取不到坐标才抛业务异常，避免图上出现「飘到 (0,0)」的节点。
     *
     * @return 落库后的节点视图
     */
    GeoViews.OriginNode saveOrigin(FlowerOrigin form);

    /** 启用 / 停用，返回是否真的改动了状态 */
    boolean setOriginActive(UUID id, boolean active);

    /**
     * 下架节点（I01 的「删除」）：被商品引用时不物理删除，返回提示文案说明已改为停用
     */
    String retireOrigin(UUID id);

    /** 后台坐标拾取（I01）：按地址文本取 GCJ-02 坐标 */
    GeoViews.GeocodeResult pickCoordinate(String address);

    /** 已有城市清单，后台表单的「同城市已有节点」提示用 */
    List<String> knownCities();

    /**
     * 外部依赖体检（I16）：取点与天气的缓存命中量级、有效期与最近一次失败原因。
     * 页面用它写「为什么这个点没有坐标」，返回值里不含密钥与请求地址
     */
    GeoViews.ExternalStatus diagnostics();
}
