package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.AmapClient;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CityGeo;
import org.liuym.flowerv1springboot.common.RoutePolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.FlowerOrigin;
import org.liuym.flowerv1springboot.model.Order;
import org.liuym.flowerv1springboot.model.OrderStatus;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.FlowerOriginRepository;
import org.liuym.flowerv1springboot.repository.MapQueryRepository;
import org.liuym.flowerv1springboot.repository.OrderItemRepository;
import org.liuym.flowerv1springboot.repository.OrderRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.service.FlowerMapService;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.liuym.flowerv1springboot.vo.StoreViews;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 地图三层的数据装配：节点表 + 订单聚合 + 坐标解析。
 *
 * <p>坐标解析是这里唯一的外部依赖，也最容易坏，所以统一走 {@link #locate}：
 * 先问高德（可精确到门牌），问不到退到内置城市质心，两条都不通才放弃该节点；
 * 外部接口故障只会让地图少一层，不会让订单详情或结算页报错。
 */
@Service
@Transactional(readOnly = true)
public class FlowerMapServiceImpl implements FlowerMapService {

    /** 只有已成交（含后续流转）的订单算进销量地图，待付款与取消/退款不算 */
    private static final List<OrderStatus> SETTLED =
            List.of(OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.SHIPPED,
                    OrderStatus.DELIVERED, OrderStatus.COMPLETED);

    private static final String RES_GEOCODE = "geocode";
    private static final String RES_CITY = "city";
    private static final String RES_NONE = "none";

    /** 真实路径（I03）只在两种情况下才拉：末端同城段用骑行、干线段用驾车 */
    private static final double LAST_MILE_REAL_LIMIT_KM = 30;
    /** 折线端点偏差容忍：同城 3 公里、跨省 15 公里，超出就认定返回的是脏数据 */
    private static final double TOLERANCE_LAST_MILE_KM = 3;
    private static final double TOLERANCE_TRUNK_KM = 15;

    /** 就近推荐（I15）：同城优先，跨城的距离优势不能把「市区那家」挤掉 */
    private static final Comparator<StoreViews.Store> NEARBY_ORDER =
            Comparator.comparing((StoreViews.Store s) -> Boolean.FALSE.equals(s.sameCity()))
                    .thenComparing(s -> s.distanceKm() == null ? Double.MAX_VALUE : s.distanceKm());

    private final FlowerOriginRepository originRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final MapQueryRepository mapQueryRepository;
    private final AmapClient amapClient;

    public FlowerMapServiceImpl(FlowerOriginRepository originRepository, ProductRepository productRepository,
                               OrderRepository orderRepository, OrderItemRepository orderItemRepository,
                               MapQueryRepository mapQueryRepository, AmapClient amapClient) {
        this.originRepository = originRepository;
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.mapQueryRepository = mapQueryRepository;
        this.amapClient = amapClient;
    }

    @Override
    public List<GeoViews.OriginNode> nodes(int days) {
        Map<UUID, long[]> stats = salesStats(days);
        List<GeoViews.OriginNode> nodes = new ArrayList<>();
        // 后台下架的节点不再出现在前台地图上（I01 的停用要真的生效），所以这里按 isActive 过滤
        for (FlowerOrigin origin : originRepository.findAllByOrderByKindAscSortOrderAsc()) {
            if (!Boolean.TRUE.equals(origin.getIsActive())) {
                continue;
            }
            long[] acc = stats.getOrDefault(origin.getId(), new long[2]);
            nodes.add(toNode(origin, acc[0], acc[1], productRepository.countByOriginIdAndIsActiveTrue(origin.getId())));
        }
        return nodes;
    }

    @Override
    public Optional<GeoViews.OriginNode> originOfProduct(UUID productId) {
        return productRepository.findById(productId)
                .map(Product::getOriginId)
                .flatMap(originRepository::findById)
                .filter(origin -> Boolean.TRUE.equals(origin.getIsActive()))
                .map(origin -> toNode(origin, 0, 0,
                        productRepository.countByOriginIdAndIsActiveTrue(origin.getId())));
    }

    @Override
    public GeoViews.OriginDetail originDetail(UUID originId, int days) {
        FlowerOrigin origin = originRepository.findById(originId)
                .orElseThrow(() -> BusinessException.notFound("产地节点不存在"));
        long[] acc = salesStats(days).getOrDefault(origin.getId(), new long[2]);
        List<GeoViews.LinkedProduct> products = productsOfOrigin(originId);
        long activeProducts = products.stream().filter(p -> Boolean.TRUE.equals(p.active())).count();
        String note = products.isEmpty()
                ? "该产地暂无在售商品，可能为规划基地或商品尚未标注主产地" : null;
        return new GeoViews.OriginDetail(toNode(origin, acc[0], acc[1], activeProducts),
                products, acc[0], acc[1], note);
    }

    @Override
    public List<GeoViews.LinkedProduct> productsOfOrigin(UUID originId) {
        List<GeoViews.LinkedProduct> list = new ArrayList<>();
        for (Object[] row : mapQueryRepository.productsOfOrigin(originId)) {
            list.add(new GeoViews.LinkedProduct((UUID) row[0], (String) row[1], (BigDecimal) row[2],
                    (String) row[3], toInt(row[4]), (String) row[5], toInt(row[6]), (Boolean) row[7]));
        }
        return list;
    }

    @Override
    public GeoViews.OrderRoute routeOfOrder(UUID orderId, UUID userId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> BusinessException.notFound("订单不存在"));
        if (userId != null && !order.getUser().getId().equals(userId)) {
            throw new BusinessException(403, "无权查看该订单");
        }
        ShippingPolicy.Method method = ShippingPolicy.resolve(order.getDeliveryMethod());
        Optional<FlowerOrigin> origin = originOfOrder(orderId);
        Endpoint endpoint = endpointFor(method, order.getReceiverAddress());

        List<RoutePolicy.Node> points = new ArrayList<>();
        origin.ifPresent(o -> points.add(toNode(RoutePolicy.KIND_ORIGIN, o)));
        if (endpoint != null) {
            if (origin.isPresent()) {
                nearestOf(hubs(), endpoint.node()).ifPresent(hub -> points.add(toNode(RoutePolicy.KIND_HUB, hub)));
            }
            points.add(endpoint.node());
        }

        List<RoutePolicy.Segment> computed = RoutePolicy.segments(points, method.code(), order.getStatus().getCode());
        List<GeoViews.RouteSegment> segments = shapeSegments(computed, points, method.code());
        int realSegments = (int) segments.stream().filter(s -> "real".equals(s.shape())).count();
        List<GeoViews.ModeLegend> legend = RoutePolicy.modes(computed).stream()
                .map(mode -> new GeoViews.ModeLegend(mode, RoutePolicy.modeLabel(mode),
                        RoutePolicy.modeIcon(mode), RoutePolicy.modeColor(mode)))
                .toList();
        boolean available = points.size() >= 2;
        String resolution = !available ? RES_NONE
                : (endpoint.resolved() != null && RES_GEOCODE.equals(endpoint.resolved().resolution()) ? RES_GEOCODE : RES_CITY);
        return new GeoViews.OrderRoute(order.getId(), order.getOrderNo(), order.getStatus().getCode(),
                method.code(), method.name(),
                points.stream().map(p -> new GeoViews.RoutePoint(p.kind(), p.name(), p.lng(), p.lat(), null)).toList(),
                segments, legend, RoutePolicy.totalKm(points, method.code()), realSegments,
                endpoint == null ? null : endpoint.city(), resolution, available,
                available ? null : "未能定位收货地址，路线暂不显示");
    }

    /**
     * 线段装配（I03/I04）：能用真实路径的段用实测里程与折线，用不了的保持估算口径。
     *
     * <p>外部接口不可用时这里一段都不会失败，只是全部标成 estimated，
     * 页面照旧画弧线，订单详情不会因为高德抖动而空白。
     */
    private List<GeoViews.RouteSegment> shapeSegments(List<RoutePolicy.Segment> computed,
                                                      List<RoutePolicy.Node> points, String deliveryMethod) {
        List<GeoViews.RouteSegment> segments = new ArrayList<>();
        for (int i = 0; i < computed.size(); i++) {
            RoutePolicy.Segment segment = computed.get(i);
            RoutePolicy.Node from = points.get(i);
            RoutePolicy.Node to = points.get(i + 1);
            String mode = segment.mode();
            Optional<AmapClient.Path> path = realPath(mode, from, to);
            String shape = "estimated";
            long km = segment.km();
            Long minutes = null;
            List<List<Double>> polyline = List.of();
            if (path.isPresent()) {
                shape = "real";
                km = RoutePolicy.metersToKm(path.get().meters() == null ? km * 1000 : path.get().meters());
                minutes = path.get().seconds() == null ? null : Math.max(1, path.get().seconds() / 60);
                polyline = path.get().points().stream().map(p -> List.of(p[0], p[1])).toList();
            }
            segments.add(new GeoViews.RouteSegment(mode, RoutePolicy.modeLabel(mode), RoutePolicy.modeIcon(mode),
                    RoutePolicy.modeColor(mode), segment.from(), segment.to(), km, minutes, shape, polyline,
                    segment.passed(), segment.current()));
        }
        return segments;
    }

    /** 取实测路径：末端短段优先骑行，长段用驾车；端点不贴合或接口不可用时返回空 */
    private Optional<AmapClient.Path> realPath(String mode, RoutePolicy.Node from, RoutePolicy.Node to) {
        if (!amapClient.available() || from == null || to == null) {
            return Optional.empty();
        }
        double km = RoutePolicy.distanceKm(from, to);
        boolean lastMile = RoutePolicy.MODE_LAST_MILE.equals(mode);
        if (lastMile && km > LAST_MILE_REAL_LIMIT_KM) {
            return Optional.empty();
        }
        String pathMode = lastMile ? AmapClient.PATH_BICYCLING : AmapClient.PATH_DRIVING;
        Optional<AmapClient.Path> path = amapClient.path(pathMode, from.lng(), from.lat(), to.lng(), to.lat());
        if (path.isEmpty() || !path.get().usable()) {
            return Optional.empty();
        }
        double tolerance = lastMile ? TOLERANCE_LAST_MILE_KM : TOLERANCE_TRUNK_KM;
        return RoutePolicy.realPathUsable(from, to, path.get().points(), tolerance) ? path : Optional.empty();
    }

    @Override
    public List<GeoViews.CitySales> salesByCity(int days, String province) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        Map<String, CityAcc> acc = new LinkedHashMap<>();
        for (Object[] row : orderRepository.salesByOrder(SETTLED, since)) {
            Resolved resolved = locate((String) row[0]);
            if (resolved == null) {
                continue;
            }
            String city = stripCity(resolved.city());
            CityAcc city1 = acc.computeIfAbsent(city, k -> new CityAcc(resolved, CityGeo.provinceOf(resolved.adcode())));
            city1.orders++;
            city1.units += toLong(row[2]);
            BigDecimal amount = (BigDecimal) row[1];
            city1.amount = city1.amount.add(amount == null ? BigDecimal.ZERO : amount);
        }
        String wanted = province == null || province.isBlank() ? null : province.trim();
        List<GeoViews.CitySales> list = new ArrayList<>();
        for (Map.Entry<String, CityAcc> entry : acc.entrySet()) {
            CityAcc value = entry.getValue();
            if (wanted != null && !wanted.equals(value.province())) {
                continue;
            }
            list.add(new GeoViews.CitySales(entry.getKey(), value.province(), value.resolved.adcode(),
                    value.resolved.lng(), value.resolved.lat(), value.orders, value.units,
                    value.amount.setScale(2, RoundingMode.HALF_UP)));
        }
        list.sort(Comparator.comparingLong(GeoViews.CitySales::units).reversed());
        return list;
    }

    /**
     * 省份聚合（I05）：先按城市聚合再上卷到省，坐标取「按枝数加权的城市质心均值」，
     * 这样省会气泡落在销量的重心而不是地理中心，下钻前后两张图视觉上连续。
     */
    @Override
    public List<GeoViews.ProvinceSales> salesByProvince(int days) {
        Map<String, ProvinceAcc> acc = new LinkedHashMap<>();
        for (GeoViews.CitySales city : salesByCity(days, null)) {
            ProvinceAcc province = acc.computeIfAbsent(city.province(), k -> new ProvinceAcc());
            province.cities++;
            province.orders += city.orders();
            province.units += city.units();
            province.amount = province.amount.add(city.amount());
            double weight = Math.max(1, city.units());
            province.weight += weight;
            province.lngSum += city.lng() == null ? 0 : city.lng() * weight;
            province.latSum += city.lat() == null ? 0 : city.lat() * weight;
        }
        List<GeoViews.ProvinceSales> list = new ArrayList<>();
        for (Map.Entry<String, ProvinceAcc> entry : acc.entrySet()) {
            ProvinceAcc value = entry.getValue();
            list.add(new GeoViews.ProvinceSales(entry.getKey(), value.cities, value.orders, value.units,
                    value.amount.setScale(2, RoundingMode.HALF_UP),
                    value.weight == 0 ? null : round4(value.lngSum / value.weight),
                    value.weight == 0 ? null : round4(value.latSum / value.weight)));
        }
        list.sort(Comparator.comparingLong(GeoViews.ProvinceSales::units).reversed());
        return list;
    }

    @Override
    public GeoViews.GeocodeResult resolveAddress(String address) {
        return toResult(address, locate(address));
    }

    @Override
    public List<StoreViews.Store> stores(String city) {
        List<StoreViews.Store> list = new ArrayList<>();
        String wanted = city == null || city.isBlank() ? null : stripCity(city);
        for (FlowerOrigin store : stores()) {
            if (wanted != null && !wanted.equals(stripCity(store.getCity()))) {
                continue;
            }
            list.add(toStore(store, null, null));
        }
        return list;
    }

    /**
     * 就近推荐（I15）：目标地址先取点（高德优先、城市质心兜底），再按「同城优先 + 直线距离」排序。
     * 地址完全定不到位时不猜，返回全部门店并给出可读的 note，让页面退回列表模式。
     */
    @Override
    public StoreViews.Nearby nearbyStores(String addressOrCity, int limit) {
        List<FlowerOrigin> candidates = stores();
        if (candidates.isEmpty()) {
            return new StoreViews.Nearby(addressOrCity, RES_NONE, null, List.of(), "暂无可用的自提门店");
        }
        Resolved resolved = locate(addressOrCity);
        if (resolved == null) {
            return new StoreViews.Nearby(addressOrCity, RES_NONE, null,
                    candidates.stream().map(s -> toStore(s, null, null)).toList(),
                    "没能定位到该地址，已按营业顺序列出全部门店");
        }
        RoutePolicy.Node target = new RoutePolicy.Node(RoutePolicy.KIND_DEST, resolved.city(),
                resolved.lng(), resolved.lat());
        String targetCity = stripCity(resolved.city());
        List<RoutePolicy.Ranked> ranked = RoutePolicy.rankByDistance(target,
                candidates.stream().map(s -> new RoutePolicy.Node(RoutePolicy.KIND_STORE, s.getName(),
                        s.getLng().doubleValue(), s.getLat().doubleValue())).toList());
        Map<String, FlowerOrigin> byName = new HashMap<>();
        candidates.forEach(s -> byName.put(s.getName(), s));
        List<StoreViews.Store> sorted = new ArrayList<>();
        for (RoutePolicy.Ranked item : ranked) {
            FlowerOrigin store = byName.get(item.node().name());
            if (store != null) {
                sorted.add(toStore(store, round1(item.km()), targetCity));
            }
        }
        List<StoreViews.Store> limited = sorted.stream().sorted(NEARBY_ORDER).limit(Math.max(1, Math.min(limit, 20)))
                .toList();
        List<StoreViews.Store> marked = new ArrayList<>();
        for (int i = 0; i < limited.size(); i++) {
            marked.add(withRecommended(limited.get(i), i == 0));
        }
        String note = RES_GEOCODE.equals(resolved.resolution())
                ? "已按门牌坐标就近排序" : "按城市质心就近排序，实际距离可能有几公里偏差";
        return new StoreViews.Nearby(addressOrCity, resolved.resolution(), targetCity, marked, note);
    }

    private static StoreViews.Store withRecommended(StoreViews.Store store, boolean recommended) {
        return new StoreViews.Store(store.id(), store.name(), store.province(), store.city(), store.adcode(),
                store.lng(), store.lat(), store.address(), store.phone(), store.openHours(),
                store.pickupReadyMinutes(), store.distanceKm(), store.sameCity(), store.readyAt(), store.feature(),
                recommended);
    }

    /* ---------- 后台维护（I01） ---------- */

    @Override
    public List<GeoViews.OriginNode> adminNodes(String kind, String keyword) {
        List<FlowerOrigin> origins = kind == null || kind.isBlank()
                ? originRepository.findAllByOrderByKindAscSortOrderAsc()
                : originRepository.findByKindOrderBySortOrderAscNameAsc(kind.trim());
        String kw = keyword == null ? "" : keyword.trim();
        Map<UUID, long[]> stats = salesStats(180);
        List<GeoViews.OriginNode> list = new ArrayList<>();
        for (FlowerOrigin origin : origins) {
            if (!kw.isEmpty() && !matches(origin, kw)) {
                continue;
            }
            long[] acc = stats.getOrDefault(origin.getId(), new long[2]);
            list.add(toNode(origin, acc[0], acc[1], mapQueryRepository.productCountOfOrigin(origin.getId())));
        }
        return list;
    }

    @Override
    @Transactional
    public GeoViews.OriginNode saveOrigin(FlowerOrigin form) {
        String name = form.getName() == null ? "" : form.getName().trim();
        if (name.isEmpty()) {
            throw new BusinessException("请填写节点名称");
        }
        String kind = form.getKind() == null || form.getKind().isBlank() ? RoutePolicy.KIND_ORIGIN : form.getKind().trim();
        if (!List.of(RoutePolicy.KIND_ORIGIN, RoutePolicy.KIND_HUB, RoutePolicy.KIND_STORE).contains(kind)) {
            throw new BusinessException("节点类型只能是产地 / 分拨中心 / 门店");
        }
        FlowerOrigin target = form.getId() == null
                ? originRepository.findFirstByName(name).orElseGet(FlowerOrigin::new)
                : originRepository.findById(form.getId()).orElseThrow(() -> BusinessException.notFound("节点不存在"));
        if (target.getId() != null && !kind.equals(target.getKind())) {
            throw new BusinessException("节点类型创建后不可改，请新建一个");
        }
        if (form.getId() == null && target.getId() != null) {
            throw new BusinessException("已有同名节点「" + name + "」，请直接编辑");
        }
        // 坐标缺失时按地址 → 高德 → 城市质心依次兜底，三条都不通才拒绝保存
        GeoViews.GeocodeResult picked = resolveCoordinates(form);
        BigDecimal lng = form.getLng() != null ? form.getLng()
                : (picked.located() ? BigDecimal.valueOf(picked.lng()) : null);
        BigDecimal lat = form.getLat() != null ? form.getLat()
                : (picked.located() ? BigDecimal.valueOf(picked.lat()) : null);
        if (lng == null || lat == null) {
            throw new BusinessException("缺少坐标：请填写经纬度，或填写能被识别的地址（含城市名也可）");
        }
        if (lng.doubleValue() < 73 || lng.doubleValue() > 136 || lat.doubleValue() < 17 || lat.doubleValue() > 54) {
            throw new BusinessException("坐标超出中国范围，请确认是 GCJ-02（高德）经纬度");
        }
        target.setName(name);
        target.setKind(kind);
        target.setLng(scale(lng));
        target.setLat(scale(lat));
        String adcode = blankToNull(form.getAdcode()) != null ? form.getAdcode().trim() : picked.adcode();
        // 省份不硬填：adcode 认得出的省名才写库，认不出留空，让销量下钻把这类节点归到「其他」而不是假造一个省
        String province = CityGeo.provinceOf(adcode);
        target.setProvince(blankToNull(form.getProvince()) != null ? form.getProvince().trim()
                : (picked.province() != null ? picked.province() : "其他".equals(province) ? null : province));
        target.setCity(blankToNull(form.getCity()) == null ? picked.city() : form.getCity().trim());
        target.setAdcode(adcode);
        target.setAltitude(form.getAltitude());
        target.setFlowers(blankToNull(form.getFlowers()));
        target.setFeature(blankToNull(form.getFeature()));
        target.setStory(blankToNull(form.getStory()));
        target.setSeason(blankToNull(form.getSeason()));
        target.setImageUrl(safeImageUrl(form.getImageUrl()));
        target.setAddress(blankToNull(form.getAddress()));
        target.setPhone(blankToNull(form.getPhone()));
        target.setOpenHours(blankToNull(form.getOpenHours()));
        target.setPickupReadyMinutes(form.getPickupReadyMinutes());
        target.setSortOrder(form.getSortOrder() == null ? nextSort(kind) : form.getSortOrder());
        target.setIsActive(form.getIsActive() == null ? Boolean.TRUE : form.getIsActive());
        FlowerOrigin saved = originRepository.save(target);
        return toNode(saved, 0, 0, mapQueryRepository.productCountOfOrigin(saved.getId()));
    }

    @Override
    @Transactional
    public boolean setOriginActive(UUID id, boolean active) {
        return originRepository.updateActive(id, active, LocalDateTime.now()) > 0;
    }

    @Override
    @Transactional
    public String retireOrigin(UUID id) {
        FlowerOrigin origin = originRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("节点不存在"));
        long products = mapQueryRepository.productCountOfOrigin(id);
        originRepository.retire(id, LocalDateTime.now());
        return products > 0
                ? "「" + origin.getName() + "」仍被 " + products + " 款商品引用，已改为停用而不是删除"
                : "已停用「" + origin.getName() + "」，地图与结算页不再出现该节点";
    }

    @Override
    public GeoViews.GeocodeResult pickCoordinate(String address) {
        return resolveAddress(address);
    }

    @Override
    public List<String> knownCities() {
        List<String> cities = new ArrayList<>(originRepository.distinctActiveCities());
        CityGeo.all().stream().map(CityGeo.Center::name).filter(c -> !cities.contains(c)).limit(40).forEach(cities::add);
        return cities;
    }

    /**
     * 外部依赖体检（I16）：把「缓存有没有在干活、上一次为什么没取到」变成页面写得出的一句话。
     *
     * <p>只回计数与可读原因，不回密钥也不回请求 URL；Caffeine 的命中计数是最终一致的，
     * 这里当量级指标看，所以没有任何一处用它做配额判断或计费。
     */
    @Override
    public GeoViews.ExternalStatus diagnostics() {
        AmapClient.Stats stats = amapClient.stats();
        AmapClient.External entries = amapClient.externalCalls();
        String state = !stats.configured() ? "unconfigured" : (stats.lookups() == 0 ? "idle" : "ok");
        return new GeoViews.ExternalStatus(stats.configured(), state, diagnosticsNote(state),
                (int) amapClient.weatherCacheMinutes(), (int) amapClient.geoCacheHours(),
                (long) CityGeo.all().size(),
                new GeoViews.CacheCounters(stats.lookups(), stats.hitRate(), stats.geoHitRate(),
                        stats.liveHitRate(), stats.pathHitRate(),
                        entries.geoEntries() + entries.liveEntries() + entries.forecastEntries()
                                + entries.pathEntries(),
                        stats.evictions()),
                stats.lastIssue() == null || stats.lastIssue().isBlank() ? null : stats.lastIssue());
    }

    private static String diagnosticsNote(String state) {
        return switch (state) {
            case "unconfigured" -> "未配置服务端密钥：取点全部由内置城市字典兜底，路线图与销量图仍能画出来，"
                    + "只是末端定位不到门牌";
            case "idle" -> "密钥已配置，本轮还没有回源查询；同一地址与坐标在有效期内不会重复扣配额";
            default -> "外部查询已由本地缓存吸收，重复地址与重复城市不再打接口";
        };
    }

    /**
     * 节点配图只收站内根相对路径：这个值最终会进 <img src>，
     * 放行 //cdn.example.com 这类协议相对地址或带冒号的伪协议，等于让后台文本框替页面背锅。
     */
    private static String safeImageUrl(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        if (value.length() > 255 || !value.startsWith("/") || value.startsWith("//") || value.contains(":")) {
            throw new BusinessException("节点配图只支持站内路径，例如 /img/photos/hero-rose.jpg");
        }
        return value;
    }

    /* ---------- 内部 ---------- */

    /**
     * 坐标兜底链（I01 + I16）：后台拾取与新建共用，保证「只填城市名也能存」。
     * 页面已经给出坐标时不再打外部接口——坐标拾取按钮就是为此存在的，保存时重复查一次只是浪费配额。
     */
    private GeoViews.GeocodeResult resolveCoordinates(FlowerOrigin form) {
        if (form.getLng() != null && form.getLat() != null) {
            String hint = firstNotBlank(form.getAddress(), form.getCity(), form.getProvince(), form.getName());
            return new GeoViews.GeocodeResult(hint, form.getLng().doubleValue(), form.getLat().doubleValue(),
                    null, blankToNull(form.getCity()), null, blankToNull(form.getAdcode()), RES_GEOCODE, true,
                    "沿用提交的坐标");
        }
        String hint = firstNotBlank(form.getAddress(), form.getCity(), form.getProvince(), form.getName());
        return toResult(hint, locate(hint));
    }

    /** 高德优先、城市质心兜底；两条路都不通返回 null，调用方跳过该节点 */
    private Resolved locate(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        Optional<AmapClient.Point> point = amapClient.geocode(address);
        if (point.isPresent() && point.get().located()) {
            AmapClient.Point p = point.get();
            String city = firstNotBlank(p.city(), p.province(), p.address());
            return new Resolved(city, p.adcode(), p.lng(), p.lat(), RES_GEOCODE);
        }
        return CityGeo.fromAddress(address)
                .map(center -> new Resolved(center.name(), center.adcode(), center.lng(), center.lat(), RES_CITY))
                .orElseGet(() -> CityGeo.byCity(address)
                        .map(center -> new Resolved(center.name(), center.adcode(), center.lng(), center.lat(), RES_CITY))
                        .orElse(null));
    }

    private GeoViews.GeocodeResult toResult(String input, Resolved resolved) {
        if (resolved == null) {
            return new GeoViews.GeocodeResult(input, null, null, null, null, null, null, RES_NONE, false,
                    amapClient.available()
                            ? "高德未能识别该地址，内置城市字典也没有命中，请补充更完整的地址"
                            : "未配置高德服务端密钥，仅能按内置城市字典取点；该地址不在字典内");
        }
        return new GeoViews.GeocodeResult(input, resolved.lng(), resolved.lat(),
                CityGeo.provinceOf(resolved.adcode()), resolved.city(), null, resolved.adcode(),
                resolved.resolution(), true,
                RES_GEOCODE.equals(resolved.resolution()) ? "已精确到门牌坐标" : "按内置城市质心兜底（约几公里偏差）");
    }

    /**
     * @param targetCity 目标地址所在城市；给了才算 sameCity，同城优先于距离（I15）
     */
    private StoreViews.Store toStore(FlowerOrigin store, Double distanceKm, String targetCity) {
        Boolean sameCity = targetCity == null ? null
                : targetCity.equalsIgnoreCase(stripCity(store.getCity()));
        return new StoreViews.Store(store.getId(), store.getName(), store.getProvince(), store.getCity(),
                store.getAdcode(), store.getLng().doubleValue(), store.getLat().doubleValue(),
                store.getAddress(), store.getPhone(), store.getOpenHours(), store.getPickupReadyMinutes(),
                distanceKm, sameCity,
                RoutePolicy.pickupReadyText(store.getOpenHours(), store.getPickupReadyMinutes(),
                        LocalDateTime.now()),
                store.getFeature(), null);
    }

    private Map<UUID, long[]> salesStats(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, days));
        Map<UUID, long[]> stats = new HashMap<>();
        for (Object[] row : orderItemRepository.salesByOrigin(SETTLED, since)) {
            long[] acc = stats.computeIfAbsent((UUID) row[0], k -> new long[2]);
            acc[0] += toLong(row[1]);
            acc[1] += toLong(row[2]);
        }
        return stats;
    }

    /** 订单主产地：多产地订单取第一个，路线起点够用；一单跨多产地的拆单展示留给后续 */
    private Optional<FlowerOrigin> originOfOrder(UUID orderId) {
        return orderItemRepository.originIdsOfOrder(orderId).stream().findFirst().flatMap(originRepository::findById);
    }

    /** 自提单以门店为终点（优先收货城市那家，没有则给排序首家），其余用收货地址；定不到就返回 null 由上层降级 */
    private Endpoint endpointFor(ShippingPolicy.Method method, String address) {
        if (ShippingPolicy.SELF_PICKUP.code().equals(method.code())) {
            List<FlowerOrigin> stores = stores();
            if (stores.isEmpty()) {
                return null;
            }
            FlowerOrigin picked = CityGeo.fromAddress(address)
                    .flatMap(center -> stores.stream().filter(s -> center.name().equals(stripCity(s.getCity()))).findFirst())
                    .orElse(stores.get(0));
            Resolved resolved = new Resolved(picked.getCity(), picked.getAdcode(),
                    picked.getLng().doubleValue(), picked.getLat().doubleValue(), RES_CITY);
            return new Endpoint(new RoutePolicy.Node(RoutePolicy.KIND_STORE, picked.getName(),
                    resolved.lng(), resolved.lat()), picked.getCity(), resolved);
        }
        Resolved resolved = locate(address);
        if (resolved == null) {
            return null;
        }
        return new Endpoint(new RoutePolicy.Node(RoutePolicy.KIND_DEST, stripCity(resolved.city()) + "·收花点",
                resolved.lng(), resolved.lat()), resolved.city(), resolved);
    }

    private List<FlowerOrigin> hubs() {
        return originRepository.findByKindAndIsActiveTrueOrderBySortOrderAsc(RoutePolicy.KIND_HUB);
    }

    private List<FlowerOrigin> stores() {
        return originRepository.findByKindAndIsActiveTrueOrderBySortOrderAsc(RoutePolicy.KIND_STORE);
    }

    private Optional<FlowerOrigin> nearestOf(List<FlowerOrigin> candidates, RoutePolicy.Node target) {
        if (candidates.isEmpty() || target == null) {
            return Optional.empty();
        }
        FlowerOrigin best = candidates.get(0);
        double bestKm = Double.MAX_VALUE;
        for (FlowerOrigin candidate : candidates) {
            double km = RoutePolicy.distanceKm(target, toNode(candidate.getKind(), candidate));
            if (km < bestKm) {
                bestKm = km;
                best = candidate;
            }
        }
        return Optional.of(best);
    }

    /** 新节点的默认排序：排在同类最后一个之后，保证后台新建不会插到已有节点前面 */
    private int nextSort(String kind) {
        return originRepository.findByKindOrderBySortOrderAscNameAsc(kind).stream()
                .map(FlowerOrigin::getSortOrder).filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(0) + 10;
    }

    private RoutePolicy.Node toNode(String kind, FlowerOrigin origin) {
        return new RoutePolicy.Node(kind, origin.getName(), origin.getLng().doubleValue(), origin.getLat().doubleValue());
    }

    private GeoViews.OriginNode toNode(FlowerOrigin origin, long units, long orders, long productCount) {
        return new GeoViews.OriginNode(origin.getId(), origin.getName(), origin.getKind(),
                origin.getProvince(), origin.getCity(), origin.getAdcode(),
                origin.getLng().doubleValue(), origin.getLat().doubleValue(), origin.getAltitude(),
                origin.getFlowers(), origin.getFeature(), origin.getStory(), origin.getSeason(),
                origin.getImageUrl(),
                productCount, units, orders, origin.getAddress(), origin.getPhone(), origin.getOpenHours(),
                origin.getPickupReadyMinutes(), origin.getIsActive());
    }

    /** 后台关键词过滤：名称/城市/省/主营品种任一命中 */
    private boolean matches(FlowerOrigin origin, String keyword) {
        return contains(origin.getName(), keyword) || contains(origin.getCity(), keyword)
                || contains(origin.getProvince(), keyword) || contains(origin.getFlowers(), keyword);
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.contains(keyword);
    }

    private String stripCity(String raw) {
        if (raw == null || raw.isBlank()) {
            return "未知城市";
        }
        String trimmed = raw.trim();
        return trimmed.endsWith("市") && trimmed.length() > 2 ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static Double round4(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private static Double round1(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNotBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static long toLong(Object raw) {
        return raw instanceof Number number ? number.longValue() : 0L;
    }

    private static int toInt(Object raw) {
        return raw instanceof Number number ? number.intValue() : 0;
    }

    private record Resolved(String city, String adcode, Double lng, Double lat, String resolution) {
    }

    private record Endpoint(RoutePolicy.Node node, String city, Resolved resolved) {
    }

    private static final class CityAcc {
        private final Resolved resolved;
        private final String province;
        private long orders;
        private long units;
        private BigDecimal amount = BigDecimal.ZERO;

        private CityAcc(Resolved resolved, String province) {
            this.resolved = resolved;
            this.province = province;
        }

        private String province() {
            return province;
        }
    }

    private static final class ProvinceAcc {
        private long cities;
        private long orders;
        private long units;
        private BigDecimal amount = BigDecimal.ZERO;
        private double weight;
        private double lngSum;
        private double latSum;
    }
}
