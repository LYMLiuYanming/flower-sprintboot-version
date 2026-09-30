package org.liuym.flowerv1springboot.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 高德开放平台 Web 服务端客户端：地理编码、天气与真实路径三件事，结果一律本地缓存。
 *
 * <p>地图与天气属于「锦上添花」而非交易主链路，所以密钥未配、配额耗尽、网络超时都按「查不到」处理：
 * 方法返回 Optional 或空集合并就地降级，绝不向调用方抛异常，免得一次外部抖动把结算页或订单详情打死。
 *
 * <p>本类持有 Web 服务端密钥，只能服务端使用；浏览器侧的底图密钥由页面单独注入，
 * 任何返回值都不带密钥与请求 URL，接口 JSON 里不会出现凭据。
 */
@Component
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private static final String BASE = "https://restapi.amap.com";

    /** 驾车真实路径（I03）：跨省干线用它，比直线估算更接近实际行驶里程 */
    public static final String PATH_DRIVING = "driving";
    /** 骑行路径（I03）：同城末端闪送用，接口与驾车不同且响应形状也不同 */
    public static final String PATH_BICYCLING = "bicycling";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final String serviceKey;
    private final long geoHours;
    private final long weatherMinutes;
    private final Cache<String, Optional<Point>> geoCache;
    private final Cache<String, Optional<Live>> liveCache;
    private final Cache<String, Optional<List<Forecast>>> forecastCache;
    private final Cache<String, Optional<Path>> pathCache;

    /**
     * 外部依赖熔断（L07）：连续失败到达阈值后，一段时间内直接降级、不再出门。
     *
     * <p>缓存能挡住「同一个地址被反复查」，挡不住「第一次查的键源源不断」——新客下单填的都是新地址，
     * 每个都是真请求。高德挂 1 分钟时，没有熔断就是拿全部流量去撞一个 5 秒超时。
     */
    private final CircuitBreaker circuit;

    /**
     * 最近一次外部失败的原因（infocode + info），只用于给页面写「天气暂时读不到」这类可读降级（I09）。
     * 用原子引用而不是每请求打日志：并发下只有一个可见结果，且绝不包含密钥。
     */
    private final AtomicReference<String> lastIssue = new AtomicReference<>("");

    public AmapClient(@Value("${amap.service-key:}") String serviceKey,
                      @Value("${amap.weather-cache-minutes:10}") long weatherMinutes,
                      @Value("${amap.geo-cache-hours:12}") long geoHours) {
        this(serviceKey, weatherMinutes, geoHours, 5, 60);
    }

    /**
     * 全参构造：熔断阈值交给配置注入。
     *
     * @param failureThreshold 连续失败几次打开断路（至少 2，单次抖动不该熔断）
     * @param openSeconds      打开后多少秒内直接降级；恢复期放一个探针出去试探
     */
    @Autowired
    public AmapClient(@Value("${amap.service-key:}") String serviceKey,
                      @Value("${amap.weather-cache-minutes:10}") long weatherMinutes,
                      @Value("${amap.geo-cache-hours:12}") long geoHours,
                      @Value("${amap.circuit.failure-threshold:5}") int failureThreshold,
                      @Value("${amap.circuit.open-seconds:60}") long openSeconds) {
        this.serviceKey = serviceKey == null ? "" : serviceKey.trim();
        this.geoHours = Math.max(1, geoHours);
        this.weatherMinutes = Math.max(1, weatherMinutes);
        this.circuit = new CircuitBreaker(failureThreshold, Duration.ofSeconds(Math.max(5, openSeconds)));
        this.geoCache = Caffeine.newBuilder().maximumSize(4000).recordStats()
                .expireAfterWrite(Duration.ofHours(this.geoHours)).build();
        this.liveCache = Caffeine.newBuilder().maximumSize(400).recordStats()
                .expireAfterWrite(Duration.ofMinutes(this.weatherMinutes)).build();
        this.forecastCache = Caffeine.newBuilder().maximumSize(400).recordStats()
                .expireAfterWrite(Duration.ofMinutes(this.weatherMinutes)).build();
        // 路径比地理编码更贵（响应体大、配额低），容量给小一点，坐标不变的情况下 12 小时内重放没有意义
        this.pathCache = Caffeine.newBuilder().maximumSize(800).recordStats()
                .expireAfterWrite(Duration.ofHours(this.geoHours)).build();
    }

    /** 熔断器当前状态（L07）：CLOSED 正常、OPEN 正在降级、HALF_OPEN 正在试探恢复 */
    public CircuitBreaker.Snapshot circuit() {
        return circuit.snapshot();
    }

    /** 因熔断被挡下的外呼次数 = 省下的配额；也是「降级了多久」的另一半证据 */
    public long circuitBlockedCalls() {
        return circuit.snapshot().totalBlocked();
    }

    /** 熔断阈值（连续失败几次打开），诊断面板要说「再失败 N 次就降级」用它 */
    public int circuitFailureThreshold() {
        return circuit.failureThreshold();
    }

    /** 熔断打开后的静默时长（秒） */
    public long circuitOpenSeconds() {
        return circuit.openWindowSeconds();
    }

    /** 未配置服务端密钥时，所有地理/天气能力静默关闭，页面走纯文字降级 */
    public boolean available() {
        return !serviceKey.isEmpty();
    }

    /** 缓存有效期（小时），给体检接口写「同一地址 12 小时内不会二次扣配额」用 */
    public long geoCacheHours() {
        return geoHours;
    }

    /** 天气缓存有效期（分钟） */
    public long weatherCacheMinutes() {
        return weatherMinutes;
    }

    /**
     * 外部依赖体检（I16）：四类能力各自的命中/回源计数，用来回答「配额还有多少、缓存有没有生效」。
     *
     * <p>只含计数与最近一次失败原因，绝不含密钥与完整请求 URL；Caffeine 的 recordStats 是最终一致计数，
     * 高并发下个别自增会丢，用于观察量级足够，不该拿它做计费依据。
     */
    public External externalCalls() {
        return new External(geoCache.estimatedSize(), liveCache.estimatedSize(), forecastCache.estimatedSize(),
                pathCache.estimatedSize());
    }

    public Stats stats() {
        CacheStats geo = geoCache.stats();
        CacheStats live = liveCache.stats();
        CacheStats forecast = forecastCache.stats();
        CacheStats path = pathCache.stats();
        return new Stats(!serviceKey.isEmpty(), geo.hitCount(), geo.missCount(), live.hitCount(), live.missCount(),
                forecast.hitCount(), forecast.missCount(), path.hitCount(), path.missCount(),
                geo.evictionCount() + live.evictionCount() + forecast.evictionCount() + path.evictionCount(),
                lastIssue.get());
    }

    /** 命中率（百分比，一位小数）：零请求时返回 0，避免除零得到 NaN 让页面显示「NaN%」 */
    public static double hitRate(long hits, long misses) {
        long lookups = hits + misses;
        return lookups <= 0 ? 0 : Math.round(hits * 1000.0 / lookups) / 10.0;
    }

    /** 四张缓存各自装了多少条在等待复用 */
    public record External(long geoEntries, long liveEntries, long forecastEntries, long pathEntries) {
    }

    /** @param lastIssue 最近一次外部失败的可读原因，正常时为空串；不含密钥 */
    public record Stats(boolean configured, long geoHits, long geoMisses, long liveHits, long liveMisses,
                        long forecastHits, long forecastMisses, long pathHits, long pathMisses, long evictions,
                        String lastIssue) {

        public long lookups() {
            return geoHits + geoMisses + liveHits + liveMisses + forecastHits + forecastMisses + pathHits + pathMisses;
        }

        /** 四类能力合起来的命中率，体检面板用它一句话说明缓存有没有在干活 */
        public double hitRate() {
            return AmapClient.hitRate(geoHits + liveHits + forecastHits + pathHits,
                    geoMisses + liveMisses + forecastMisses + pathMisses);
        }

        public double geoHitRate() {
            return AmapClient.hitRate(geoHits, geoMisses);
        }

        public double liveHitRate() {
            return AmapClient.hitRate(liveHits, liveMisses);
        }

        public double pathHitRate() {
            return AmapClient.hitRate(pathHits, pathMisses);
        }
    }

    /** 最近一次外部失败的可读原因；正常时为空，密钥未配置时也应为空 */
    public Optional<String> lastIssue() {
        return Optional.ofNullable(lastIssue.get()).filter(s -> !s.isBlank());
    }

    /** 地址文本转经纬度；高德按省市逐级匹配，取第一条命中 */
    public Optional<Point> geocode(String address) {
        if (!available() || address == null || address.isBlank()) {
            return Optional.empty();
        }
        // 同一地址被填成「北京市 朝阳区 」与「北京市朝阳区」不该各占一份配额，缓存键先归一化（I16）
        String key = normalize(address);
        return geoCache.get(key, k -> fetch(() ->
                first(get("/v3/geocode/geo", "address", k), "geocodes").map(AmapClient::toPoint)));
    }

    /** 城市实况天气，cityOrAdcode 支持「北京市」或 adcode */
    public Optional<Live> liveWeather(String cityOrAdcode) {
        if (!available() || cityOrAdcode == null || cityOrAdcode.isBlank()) {
            return Optional.empty();
        }
        String key = cityOrAdcode.trim();
        return liveCache.get(key, k -> fetch(() -> first(
                get("/v3/weather/weatherInfo", "city", k, "extensions", "base"), "lives").map(AmapClient::toLive)));
    }

    /** 未来几天预报，用于「明天下雨要不要改时段」这类前瞻判断 */
    public List<Forecast> forecast(String cityOrAdcode) {
        if (!available() || cityOrAdcode == null || cityOrAdcode.isBlank()) {
            return List.of();
        }
        String key = cityOrAdcode.trim();
        Optional<List<Forecast>> cached = forecastCache.get(key, k -> fetch(() -> {
            JsonNode body = get("/v3/weather/weatherInfo", "city", k, "extensions", "all");
            List<Forecast> list = new ArrayList<>();
            JsonNode forecasts = body == null ? null : body.get("forecasts");
            if (forecasts != null && forecasts.isArray()) {
                forecasts.forEach(node -> list.add(Forecast.of(node)));
            }
            return Optional.of(list);
        }));
        return cached == null ? List.of() : cached.orElseGet(List::of);
    }

    /**
     * 实况天气的缓存态（I16/I09）：接口返回里带这个标记，页面就能写「以下是十几分钟内的缓存」
     * 或「刚刚查过且没查到，暂时没有再打外部接口」。
     *
     * <p>失败的查询也会进缓存（负缓存，TTL 与成功一致），所以 failed 不是缺陷，而是省配额的手段；
     * 三态用字符串而不是布尔，因为「没查过」和「查过但失败」给用户的说法完全不同。
     *
     * @return value = 有可复用的结果；failed = 近期查过且失败；none = 本地没有这条记录
     */
    public String liveCacheState(String cityOrAdcode) {
        return cacheState(liveCache, cityOrAdcode == null ? "" : cityOrAdcode.trim());
    }

    /** 地理编码的缓存态（I16）：value 表示这次取点没有再消耗配额 */
    public String geoCacheState(String address) {
        return available() && address != null && !address.isBlank()
                ? cacheState(geoCache, normalize(address)) : "none";
    }

    /** 预报的缓存态（I08/I16）：与实况共用 TTL，因为预报接口同样是按分钟计费的低频数据 */
    public String forecastCacheState(String cityOrAdcode) {
        return cacheState(forecastCache, cityOrAdcode == null ? "" : cityOrAdcode.trim());
    }

    private <T> String cacheState(Cache<String, Optional<T>> cache, String key) {
        if (!available() || key.isEmpty()) {
            return "none";
        }
        Optional<T> hit = cache.getIfPresent(key);
        return hit == null ? "none" : (hit.isPresent() ? "value" : "failed");
    }

    /**
     * 两点间的真实路径（I03）。失败返回空，由调用方退回「直线估算 + 弧线美化」的画法。
     *
     * <p>缓存键含方式与起终点坐标（截到 4 位小数，约 10 米），同城重复查询同一对坐标只打一次配额。
     */
    public Optional<Path> path(String mode, double fromLng, double fromLat, double toLng, double toLat) {
        if (!available() || !PATH_DRIVING.equals(mode) && !PATH_BICYCLING.equals(mode)) {
            return Optional.empty();
        }
        String key = mode + "|" + coord(fromLng) + "," + coord(fromLat) + "|" + coord(toLng) + "," + coord(toLat);
        return pathCache.get(key, k -> fetch(() -> {
            String origin = coord(fromLng) + "," + coord(fromLat);
            String destination = coord(toLng) + "," + coord(toLat);
            // 驾车走 v3（status/infocode 口径），骑行只有 v4（errcode 口径），两者响应形状不同，分开取更清楚
            JsonNode body = PATH_DRIVING.equals(mode)
                    ? get("/v3/direction/driving", "origin", origin, "destination", destination, "strategy", "0")
                    : getV4("/v4/direction/bicycling", "origin", origin, "destination", destination);
            return Optional.ofNullable(toPath(mode, body));
        }));
    }

    /** 路径结果对前端只暴露坐标与量级，不含请求 URL，因此不会把密钥带出去 */
    public record Path(String mode, List<double[]> points, Long meters, Long seconds) {
        public boolean usable() {
            return points != null && points.size() >= 2;
        }
    }

    private <T> Optional<T> fetch(SupplierThatCanThrow<Optional<T>> call) {
        try {
            return call.get();
        } catch (InterruptedException e) {
            // 中断信号要交还给调用线程，否则线程池会以为没人打断过它；对外仍然只是「查不到」
            Thread.currentThread().interrupt();
            log.warn("高德接口调用被中断：{}", scrub(e.toString()));
            lastIssue.set("调用被中断");
            return Optional.empty();
        } catch (Exception e) {
            // 外部依赖失败只降级不阻断；同一密钥的持续故障会由缓存节流，不会每请求刷日志
            log.warn("高德接口调用失败：{}", scrub(e.toString()));
            lastIssue.set("网络或接口异常：" + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 抹掉异常文本里的服务端密钥（I09）。
     *
     * <p>URI.create 与 HttpClient 抛出的消息可能整段带上请求 URL，而 URL 的 query 里就有 key=，
     * 这条路径的文本既进日志又进 /api/map 的体检返回，所以在这里统一替换，密钥不外泄给页面。
     */
    public String scrub(String raw) {
        if (raw == null || raw.isEmpty() || serviceKey.isEmpty()) {
            return raw == null ? "" : raw;
        }
        return raw.replace(serviceKey, "***").replace(encode(serviceKey), "***");
    }

    /** 坐标截到 4 位小数（约 10 米）：缓存键与请求都用它，避免末位抖动白占配额 */
    static String coord(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    /** 缓存键归一化：地址里的空白与全角空格对高德而言是同一个地点 */
    static String normalize(String address) {
        return address.trim().replaceAll("[\\s\u3000]+", "");
    }

    private JsonNode get(String path, String... keyValues) throws Exception {
        return send(path, true, keyValues);
    }

    /** v4 接口族用 errcode 表达成败，形状与 v3 不同，单独一条路径更清楚 */
    private JsonNode getV4(String path, String... keyValues) throws Exception {
        return send(path, false, keyValues);
    }

    private JsonNode send(String path, boolean v3Shape, String... keyValues) throws Exception {
        // L07 熔断：断路窗口内的请求直接降级返回，既不烧配额，也不让用户干等 5 秒超时
        if (!circuit.allowRequest()) {
            CircuitBreaker.Snapshot open = circuit.snapshot();
            lastIssue.set("地图服务连续失败，已临时降级，稍后自动恢复");
            log.debug("高德 {} 被熔断挡下（连续失败 {} 次，剩余 {}ms）", path,
                    open.consecutiveFailures(), open.openRemainingMs());
            return null;
        }
        StringBuilder query = new StringBuilder("key=").append(encode(serviceKey));
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            query.append('&').append(keyValues[i]).append('=').append(encode(keyValues[i + 1]));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + path + "?" + query))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            circuit.recordFailure();
            throw e;
        } catch (Exception | Error e) {
            // 连接/超时/解析类异常都算依赖不可用；异常照旧抛出，由 fetch() 统一降级并写日志
            circuit.recordFailure();
            throw e;
        }
        if (response.statusCode() != 200) {
            log.debug("高德 {} 返回 HTTP {}", path, response.statusCode());
            // 5xx 与限流是依赖侧故障，计入熔断；4xx 多为参数问题，判成功让计数回血
            if (response.statusCode() >= 500 || response.statusCode() == 429) {
                circuit.recordFailure();
            } else {
                circuit.recordSuccess();
            }
            lastIssue.set(path + " 返回 HTTP " + response.statusCode());
            return null;
        }
        JsonNode body = mapper.readTree(response.body());
        if (v3Shape) {
            // 高德的 v3 以字符串 "1" 表示成功，infocode 10000 之外都是配额/密钥/参数问题
            if (!"1".equals(text(body, "status"))) {
                String infocode = text(body, "infocode");
                String info = text(body, "info");
                log.debug("高德 {} 业务失败 infocode={} info={}", path, infocode, info);
                lastIssue.set(path + " 业务失败：" + (info == null ? "未知原因" : info)
                        + (infocode == null ? "" : "（" + infocode + "）"));
                // 配额/密钥类失败继续打只会更糟，计入熔断；纯「查无此地址」在上游按空结果处理，不走这里
                circuit.recordFailure();
                return null;
            }
            circuit.recordSuccess();
            return body;
        }
        // v4 的 errcode=0 才是成功，且没有 status 字段
        Integer errcode = integer(body, "errcode");
        if (errcode == null || errcode != 0) {
            String reason = text(body, "errmsg");
            log.debug("高德 {} errcode={} errmsg={}", path, errcode, reason);
            lastIssue.set(path + " errcode=" + errcode + (reason == null ? "" : " " + reason));
            circuit.recordFailure();
            return null;
        }
        circuit.recordSuccess();
        return body;
    }

    static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 高德的「无值」会返回空数组而不是 null，取文本时要把这种形状当成空 */
    static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || !value.isValueNode() || value.isNull() ? null : value.asText().trim();
    }

    static Integer integer(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double decimal(JsonNode node, String field) {
        String raw = text(node, field);
        if (raw == null) {
            return null;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SupplierThatCanThrow<T> {
        T get() throws Exception;
    }

    public record Point(String address, String province, String city, String district,
                        String adcode, Double lng, Double lat) {
        public boolean located() {
            return lng != null && lat != null;
        }
    }

    public record Live(String city, String adcode, String weather, Integer temperature, Integer humidity,
                       String windDirection, String windPower, String reportTime) {
    }

    public record Forecast(String city, String adcode, String reportTime, List<Cast> casts) {

        public record Cast(String date, String dayWeather, String nightWeather,
                           Integer dayTemp, Integer nightTemp) {
        }

        public static Forecast of(JsonNode node) {
            List<Cast> casts = new ArrayList<>();
            for (JsonNode cast : node.path("casts")) {
                casts.add(new Cast(text(cast, "date"), text(cast, "dayweather"), text(cast, "nightweather"),
                        integer(cast, "daytemp"), integer(cast, "nighttemp")));
            }
            return new Forecast(text(node, "city"), text(node, "adcode"), text(node, "reporttime"), casts);
        }
    }

    /** 地理编码结果 → 值对象 */
    static Point toPoint(JsonNode node) {
        String location = text(node, "location");
        Double lng = null;
        Double lat = null;
        if (location != null && location.contains(",")) {
            String[] parts = location.split(",");
            lng = safeDouble(parts[0]);
            lat = safeDouble(parts[1]);
        }
        return new Point(text(node, "formatted_address"), text(node, "province"), text(node, "city"),
                text(node, "district"), text(node, "adcode"), lng, lat);
    }

    static Live toLive(JsonNode node) {
        return new Live(text(node, "city"), text(node, "adcode"), text(node, "weather"),
                integer(node, "temperature"), integer(node, "humidity"),
                text(node, "winddirection"), text(node, "windpower"), text(node, "reporttime"));
    }

    /**
     * 路径响应 → 坐标序列。v3 驾车在 route.paths[0].path，v4 骑行在 data.paths[0].polyline，
     * 两处都是「lng,lat;lng,lat」的字符串，统一在这一处拆开，调用方只看到同一种形状。
     */
    static Path toPath(String mode, JsonNode body) {
        if (body == null) {
            return null;
        }
        JsonNode path = PATH_DRIVING.equals(mode)
                ? body.path("route").path("paths").path(0)
                : body.path("data").path("paths").path(0);
        if (path.isMissingNode() || path.isNull()) {
            return null;
        }
        List<double[]> points = parsePolyline(text(path, PATH_DRIVING.equals(mode) ? "path" : "polyline"));
        if (points.size() < 2) {
            return null;
        }
        return new Path(mode, points, decimalToLong(text(path, "distance")), decimalToLong(text(path, "duration")));
    }

    /** 折线文本拆解：单个坐标坏点跳过而不是整体作废，因为高德偶发会给出空段 */
    static List<double[]> parsePolyline(String raw) {
        List<double[]> points = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return points;
        }
        for (String pair : raw.split(";")) {
            String[] parts = pair.split(",");
            if (parts.length != 2) {
                continue;
            }
            Double lng = safeDouble(parts[0]);
            Double lat = safeDouble(parts[1]);
            if (lng != null && lat != null) {
                points.add(new double[]{lng, lat});
            }
        }
        return points;
    }

    private static Long decimalToLong(String raw) {
        Double value = raw == null ? null : safeDouble(raw);
        return value == null ? null : Math.round(value);
    }

    /** 从响应里挑出某数组字段的第一个对象；高德空结果会给空数组，这里统一成 empty */
    static Optional<JsonNode> first(JsonNode body, String arrayField) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode list = body.get(arrayField);
        if (list == null || !list.isArray() || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    private static Double safeDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
