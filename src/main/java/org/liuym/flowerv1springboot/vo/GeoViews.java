package org.liuym.flowerv1springboot.vo;

import org.liuym.flowerv1springboot.common.FlowerCarePolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 地图与天气视图：坐标一律 GCJ-02 十进制，前端高德直接可用；
 * 文案（方式名、提示级别、tips）在服务端定稿，页面不再自己拼判断分支。
 *
 * <p>这里没有任何密钥字段：JS API key 与安全密钥只在 common/amap.html 的脚本里出现，
 * 接口 JSON 一律不带，避免把服务端凭据发到浏览器。
 */
public final class GeoViews {

    private GeoViews() {
    }

    /** 地图节点：产地 / 分拨中心 / 自提门店 */
    public record OriginNode(
            UUID id,
            String name,
            String kind,
            String province,
            String city,
            String adcode,
            Double lng,
            Double lat,
            Integer altitude,
            String flowers,
            String feature,
            String story,
            String season,
            /** 节点配图（I01 后台维护）：站内图片路径，产地卡片用它讲「这片土地长什么样」 */
            String imageUrl,
            /** 当前在售商品数，产地卡片用来解释「我们在这里采什么」 */
            Long productCount,
            /** 窗口期内从该节点发出的枝数 */
            Long units,
            Long orders,
            /** 门店到店信息（I15）：产地与分拨中心可空 */
            String address,
            String phone,
            String openHours,
            Integer pickupReadyMinutes,
            /** 停用节点只对后台可见（I01），前台列表里永远为 true */
            Boolean active) {
    }

    /** 路线上的一个点 */
    public record RoutePoint(String kind, String name, Double lng, Double lat, String adcode) {
    }

    /**
     * 路线上的一段。
     *
     * @param icon/color 方式图标与配色（I04），由服务端 RoutePolicy 定稿
     * @param shape      real = 高德返回的真实驾驶/骑行折线；estimated = 直线乘系数估算（I03）
     * @param polyline   折线坐标 [[lng,lat],...]，estimated 时为空，前端自己画弧线
     */
    public record RouteSegment(String mode, String modeLabel, String icon, String color,
                               String from, String to, Long km, Long minutes, String shape,
                               List<List<Double>> polyline, Boolean passed, Boolean current) {
    }

    /** 图例条目（I04）：一条路线里实际用到的运输方式 */
    public record ModeLegend(String mode, String label, String icon, String color) {
    }

    public record OrderRoute(
            UUID orderId,
            String orderNo,
            String status,
            String deliveryMethod,
            String deliveryMethodName,
            List<RoutePoint> points,
            List<RouteSegment> segments,
            List<ModeLegend> legend,
            Long totalKm,
            /** 其中按真实路径绘制的段数，页面用它标注「本图含 N 段实测路线」 */
            Integer realSegments,
            String receiverCity,
            /** 取点方式：geocode 精确到门牌 / city 城市质心兜底 / none 画不出来 */
            String resolution,
            Boolean available,
            String note) {
    }

    /** 城市销量气泡 */
    public record CitySales(String city, String province, String adcode, Double lng, Double lat,
                            Long orders, Long units, BigDecimal amount) {
    }

    /** 省份聚合行（I05）：下钻入口，lng/lat 给该省城市质心的均值，用于气泡定位 */
    public record ProvinceSales(String province, Long cities, Long orders, Long units, BigDecimal amount,
                                Double lng, Double lat) {
    }

    /** 天气卡片：level 决定色阶，tips 是给人看的养护建议 */
    public record WeatherCard(String city, String adcode, String weather, Integer temperature, Integer humidity,
                              String windDirection, String windPower, String reportTime,
                              String level, String headline, List<String> tips, Boolean recommendColdChain,
                              /** 结合天气的养护一句话（I10），页面直接展示 */
                              String careNote) {
    }

    /** 未来 3 日预报与逐日配送结论（I08） */
    public record ForecastCard(String city, String adcode, String reportTime,
                               List<FlowerCarePolicy.ForecastDay> days,
                               /** ok = 有数据；unconfigured = 未配密钥；failed = 外部接口不可用 */
                               String state, String note) {
    }

    /**
     * 天气与地理编码服务的可用性状态（I09/I16）：让页面能写「为什么这里没数据」，
     * 而不是显示一个空白。lastIssue 只含高德返回的 infocode/info 文本，不含密钥与完整 URL。
     */
    public record ServiceStatus(Boolean configured, Boolean available, String state, String note,
                                Integer cacheMinutes, String lastIssue, String city) {
    }

    /** 地址取点结果（I16）：地图选点、后台维护与就近门店共用一套口径 */
    public record GeocodeResult(String input, Double lng, Double lat, String province, String city,
                                String district, String adcode,
                                /** geocode 命中门牌 / city 内置质心兜底 / none 定不到位 */
                                String resolution, Boolean located, String note) {
    }

    /** 产地关联商品（I02） */
    public record LinkedProduct(UUID id, String name, BigDecimal price, String mainImage,
                                Integer salesCount, String unit, Integer stock, Boolean active) {
    }

    /**
     * 缓存计数（I16）：只给量级与命中率，用来判断「缓存有没有在替我们省配额」。
     * Caffeine 的计数是最终一致的，所以这里当观察指标看，不能当计费依据。
     */
    public record CacheCounters(Long lookups, Double hitRate, Double geoHitRate, Double liveHitRate,
                                Double pathHitRate, Long entries, Long evictions) {
    }

    /**
     * 外部依赖体检（I16）：取点与天气的可用性、缓存有效期与命中情况。
     * 不含密钥也不含请求 URL——lastIssue 只有高德回给的可读失败原因。
     */
    public record ExternalStatus(Boolean configured, String state, String note,
                                 Integer weatherCacheMinutes, Integer geoCacheHours, Long dictionaryCities,
                                 CacheCounters caches, String lastIssue) {
    }

    /** 产地详情：节点 + 关联商品 + 窗口期发货量（I02） */
    public record OriginDetail(OriginNode node, List<LinkedProduct> products, Long units, Long orders,
                               String note) {
    }
}
