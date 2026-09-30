package org.liuym.flowerv1springboot.vo;

import java.util.List;
import java.util.UUID;

/**
 * 门店自提视图（I15）：门店列表 + 按收货地址就近排序的推荐结果。
 *
 * <p>距离用 {@code RoutePolicy.distanceKm} 的球面距离，是「到店指引」口径而非导航里程；
 * 就近推荐必须能在高德地理编码不可用时工作，所以坐标来自内置城市质心时 resolution 会标成 city。
 */
public final class StoreViews {

    private StoreViews() {
    }

    /**
     * @param distanceKm    与目标地址的直线距离（公里），未给地址时为 null
     * @param readyAt       结合备花时长算出的可取时间文本，空表示未营业时段无法估算
     * @param sameCity      是否与目标地址同城：同城优先于距离，跨城 30 公里的门店不该排在同城的 40 公里之前
     */
    public record Store(UUID id, String name, String province, String city, String adcode,
                        Double lng, Double lat, String address, String phone, String openHours,
                        Integer pickupReadyMinutes, Double distanceKm, Boolean sameCity, String readyAt,
                        String feature, Boolean recommended) {
    }

    /** 就近推荐结果 */
    public record Nearby(String query, String resolution, String city, List<Store> stores, String note) {
    }
}
