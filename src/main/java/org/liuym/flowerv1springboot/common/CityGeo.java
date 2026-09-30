package org.liuym.flowerv1springboot.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 城市质心字典：把「收货地址文本」翻译成地图坐标的兜底来源。
 *
 * <p>高德地理编码受密钥配额与引擎稳定性影响（当前该 Key 直接返回 ENGINE_RESPONSE_DATA_ERROR），
 * 而路线图缺一个点就整条断掉，所以城市级坐标内置在代码里：地理编码可用时以它为准（能精确到门牌），
 * 不可用时退到城市质心（地图上差几公里，语义完全正确）。
 *
 * <p>坐标为各市市政府驻地量级，用于气泡与折线端点足够；经纬度取 GCJ-02，与高德底图同一坐标系，
 * 换成百度/Mapbox 底图需要重新纠偏。
 */
public final class CityGeo {

    public record Center(String name, String adcode, double lng, double lat) {
    }

    private static final Map<String, Center> CITIES = new LinkedHashMap<>();

    /**
     * adcode 前两位 → 省级行政区名（I05 省份下钻用）。
     *
     * <p>不按城市名硬编省份，是因为地址文本里的省市写法太杂（「新疆伊犁哈萨克自治州」这类连州名都带后缀），
     * 而 adcode 前两位是国标固定值，从编码反推省名唯一且稳定；地理编码给的 adcode 与内置质心的 adcode 同系，
     * 两条来源因此能落到同一张表上。
     */
    private static final Map<String, String> PROVINCES = new LinkedHashMap<>();

    private static final Map<String, Center> BY_ADCODE = new LinkedHashMap<>();

    private static void put(String name, String adcode, double lng, double lat) {
        Center center = new Center(name, adcode, lng, lat);
        CITIES.put(name, center);
        // 同一 adcode 可能挂了两个地名（呈贡 / 斗南），反查时保留第一个，避免歧义结果
        BY_ADCODE.putIfAbsent(adcode, center);
    }

    private static void province(String prefix, String name) {
        PROVINCES.put(prefix, name);
    }

    static {
        province("11", "北京");
        province("12", "天津");
        province("13", "河北");
        province("14", "山西");
        province("15", "内蒙古");
        province("21", "辽宁");
        province("22", "吉林");
        province("23", "黑龙江");
        province("31", "上海");
        province("32", "江苏");
        province("33", "浙江");
        province("34", "安徽");
        province("35", "福建");
        province("36", "江西");
        province("37", "山东");
        province("41", "河南");
        province("42", "湖北");
        province("43", "湖南");
        province("44", "广东");
        province("45", "广西");
        province("46", "海南");
        province("50", "重庆");
        province("51", "四川");
        province("52", "贵州");
        province("53", "云南");
        province("54", "西藏");
        province("61", "陕西");
        province("62", "甘肃");
        province("63", "青海");
        province("64", "宁夏");
        province("65", "新疆");
    }

    static {
        put("北京", "110000", 116.4074, 39.9042);
        put("上海", "310000", 121.4737, 31.2304);
        put("天津", "120000", 117.2010, 39.0842);
        put("重庆", "500000", 106.5516, 29.5630);
        put("石家庄", "130100", 114.5024, 38.0455);
        put("唐山", "130200", 118.1802, 39.6305);
        put("秦皇岛", "130300", 119.5974, 39.9354);
        put("太原", "140100", 112.5489, 37.8706);
        put("呼和浩特", "150100", 111.7490, 40.8424);
        put("沈阳", "210100", 123.4290, 41.7968);
        put("大连", "210200", 121.6186, 38.9146);
        put("长春", "220100", 125.3235, 43.8171);
        put("哈尔滨", "230100", 126.5306, 45.8005);
        put("南京", "320100", 118.7969, 32.0603);
        put("无锡", "320200", 120.3019, 31.5747);
        put("徐州", "320300", 117.2839, 34.2058);
        put("常州", "320400", 119.9783, 31.7975);
        put("苏州", "320500", 120.5853, 31.2989);
        put("南通", "320600", 120.8646, 31.9802);
        put("扬州", "321000", 119.4128, 32.3922);
        put("杭州", "330100", 120.1551, 30.2741);
        put("宁波", "330200", 121.5503, 29.8746);
        put("温州", "330300", 120.6994, 27.9945);
        put("金华", "330700", 119.6472, 29.0793);
        put("合肥", "340100", 117.2830, 31.8612);
        put("福州", "350100", 119.2965, 26.0745);
        put("厦门", "350200", 118.0894, 24.4798);
        put("泉州", "350500", 118.6757, 24.8741);
        put("南昌", "360100", 115.8580, 28.6820);
        put("济南", "370100", 117.0810, 36.6510);
        put("青岛", "370200", 120.3826, 36.0671);
        put("潍坊", "370700", 119.1618, 36.7068);
        put("烟台", "370600", 121.3094, 37.5355);
        put("郑州", "410100", 113.6254, 34.7466);
        put("洛阳", "410300", 112.4539, 34.6197);
        put("武汉", "420100", 114.3054, 30.5931);
        put("长沙", "430100", 112.9388, 28.2282);
        put("广州", "440100", 113.2644, 23.1291);
        put("深圳", "440300", 114.0579, 22.5431);
        put("珠海", "440400", 113.5543, 22.2249);
        put("佛山", "440600", 113.1227, 23.0287);
        put("东莞", "441900", 113.7463, 23.0468);
        put("汕头", "440500", 116.6820, 23.3547);
        put("南宁", "450100", 108.3665, 22.8170);
        put("海口", "460100", 110.1999, 20.0442);
        put("成都", "510100", 104.0665, 30.5723);
        put("绵阳", "510700", 104.6796, 31.4675);
        put("贵阳", "520100", 106.6302, 26.6477);
        put("昆明", "530100", 102.8329, 24.8801);
        put("拉萨", "540100", 91.1322, 29.6604);
        put("西安", "610100", 108.9402, 34.3416);
        put("兰州", "620100", 103.8343, 36.0611);
        put("西宁", "630100", 101.7782, 36.6171);
        put("银川", "640100", 106.2782, 38.4684);
        put("乌鲁木齐", "650100", 87.6168, 43.8256);
        // 伊犁是郁金香基地所在城市，且地址文本常写成「伊犁哈萨克自治州」，字典里没有它该产地就只能靠地理编码
        put("伊犁", "654000", 81.2874, 43.9069);
        // 产地与市场所在地：让产地与门店节点即使没有地理编码也能落到图上
        put("呈贡", "530114", 102.7968, 24.8985);
        put("青州", "370781", 118.4777, 36.6882);
        put("三圣", "510104", 104.1365, 30.5768);
        put("岭南", "440103", 113.2347, 23.0961);
        put("虹桥", "310112", 121.3610, 31.1900);
        put("顺义", "110113", 116.6563, 40.1300);
        put("斗南", "530114", 102.7968, 24.8985);
    }

    private CityGeo() {
    }

    public static Optional<Center> byCity(String cityName) {
        if (cityName == null || cityName.isBlank()) {
            return Optional.empty();
        }
        String key = cityName.trim().replaceAll("[市省]$", "");
        return key.isEmpty() ? Optional.empty() : Optional.ofNullable(CITIES.get(key));
    }

    /** 按 adcode 反查城市质心：地图节点表里已存编码的走这条，比再打一次地理编码更省 */
    public static Optional<Center> byAdcode(String adcode) {
        if (adcode == null || adcode.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ADCODE.get(adcode.trim()));
    }

    /**
     * adcode → 省级行政区名。够不着的编码（如 9 位的历史值）返回「其他」，
     * 让省份下钻在数据残缺时仍然给得出一个分组，而不是把订单从统计里悄悄丢掉。
     */
    public static String provinceOf(String adcode) {
        if (adcode == null || adcode.trim().length() < 2) {
            return "其他";
        }
        return PROVINCES.getOrDefault(adcode.trim().substring(0, 2), "其他");
    }

    /** 已知省份清单，按 adcode 段顺序，给后台与地图的省份筛选用 */
    public static List<String> provinces() {
        return new ArrayList<>(PROVINCES.values());
    }

    /**
     * 从自由文本地址里取城市质心：命中多个城市名时取出现位置最靠前的（省名一般先于市名，
     * 位置最靠前即最外层行政单位），同位置取更长的名字，避免「石家庄」被「家庄」这类误伤
     */
    public static Optional<Center> fromAddress(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        String text = address.trim();
        Center best = null;
        int bestAt = Integer.MAX_VALUE;
        for (Center center : CITIES.values()) {
            int at = text.indexOf(center.name());
            if (at < 0) {
                continue;
            }
            if (at < bestAt || (at == bestAt && best != null && center.name().length() > best.name().length())) {
                best = center;
                bestAt = at;
            }
        }
        return Optional.ofNullable(best);
    }

    public static List<Center> all() {
        return new ArrayList<>(CITIES.values());
    }
}
