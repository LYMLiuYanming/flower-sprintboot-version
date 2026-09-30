package org.liuym.flowerv1springboot.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 运输路线口径：把「产地 → 分拨中心 → 配送城市 → 收花点」这条链拆成可画的线段，
 * 并按订单状态判断每一段是已行驶、在途还是待发。
 *
 * <p>这里只算数，不碰数据库也不碰外部接口：里程用球面距离乘以线路系数估算，
 * 是「量级正确」的展示口径而非结算依据，真实计费重量与运费仍由 {@link ShippingPolicy} 负责。
 */
public final class RoutePolicy {

    /** 节点类型 */
    public static final String KIND_ORIGIN = "origin";
    public static final String KIND_HUB = "hub";
    public static final String KIND_STORE = "store";
    public static final String KIND_DEST = "dest";

    /** 线路方式 */
    public static final String MODE_AIR = "air";
    public static final String MODE_SEA = "sea";
    public static final String MODE_LAND = "land";
    public static final String MODE_LAST_MILE = "last_mile";

    private RoutePolicy() {
    }

    public record Node(String kind, String name, double lng, double lat) {
    }

    /**
     * @param mode    线路方式，决定前端线型与图标
     * @param km      估算里程（公里）
     * @param passed  是否已完成
     * @param current 是否为当前在途段
     */
    public record Segment(String mode, String from, String to, long km, boolean passed, boolean current) {
    }

    /** 球面距离（公里），Haversine；同城两点很近时至少给 1 公里，避免出现 0 km 的线段 */
    public static double distanceKm(Node from, Node to) {
        double radius = 6371.0;
        double dLat = Math.toRadians(to.lat() - from.lat());
        double dLng = Math.toRadians(to.lng() - from.lng());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(from.lat())) * Math.cos(Math.toRadians(to.lat()))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double km = 2 * radius * Math.asin(Math.min(1, Math.sqrt(a)));
        return km < 1 ? 1 : km;
    }

    /**
     * 线路系数：直线距离到实际行驶里程的经验放大倍数。
     * 空运航线最接近直线，公路绕行最多。
     */
    public static double lineFactor(String deliveryMethod) {
        if (ShippingPolicy.AIR_COLD.code().equals(deliveryMethod)) {
            return 1.12;
        }
        if (ShippingPolicy.SEA_FRESH.code().equals(deliveryMethod)) {
            return 1.25;
        }
        if (ShippingPolicy.SELF_PICKUP.code().equals(deliveryMethod)) {
            return 1.30;
        }
        return 1.35;
    }

    /** 运输方式对应的方式文案，用于线段标签 */
    public static String modeLabel(String mode) {
        return switch (mode) {
            case MODE_AIR -> "空运冷链";
            case MODE_SEA -> "海运保鲜";
            case MODE_LAST_MILE -> "同城闪送";
            default -> "干线冷链";
        };
    }

    /**
     * 方式图标（I04）：图标名由服务端定稿并随线段一起下发，
     * 页面不再自己维护一份 mode→icon 字典，避免出现「后端加了海运、前端画成卡车」。
     */
    public static String modeIcon(String mode) {
        return switch (mode) {
            case MODE_AIR -> "fa-plane";
            case MODE_SEA -> "fa-ship";
            case MODE_LAST_MILE -> "fa-bicycle";
            default -> "fa-truck";
        };
    }

    /** 图例配色与线段一致：已过翡翠、在途琥珀、待发灰 */
    public static String modeColor(String mode) {
        return switch (mode) {
            case MODE_AIR -> "#2f6fb0";
            case MODE_SEA -> "#1f8a8c";
            case MODE_LAST_MILE -> "#d98c3a";
            default -> "#0f7b63";
        };
    }

    /** 路线里实际出现的方式（按行程顺序去重），画分段图例用 */
    public static List<String> modes(List<Segment> segments) {
        List<String> modes = new ArrayList<>();
        for (Segment segment : segments == null ? List.<Segment>of() : segments) {
            if (segment.mode() != null && !modes.contains(segment.mode())) {
                modes.add(segment.mode());
            }
        }
        return modes;
    }

    /**
     * 真实路径是否可用（I03）：折线首尾必须贴着起终点，否则宁可用估算线。
     *
     * <p>高德偶尔会返回「同一起点、不同城市」的缓存串路（配额重试时见过），
     * 那种路径画出来会把路线甩到上千公里外，比直线示意更误导人。
     *
     * @param toleranceKm 允许的端点偏差，同城末端给 3、跨省干线给 15
     */
    public static boolean realPathUsable(Node from, Node to, List<double[]> points, double toleranceKm) {
        if (from == null || to == null || points == null || points.size() < 2) {
            return false;
        }
        double[] first = points.get(0);
        double[] last = points.get(points.size() - 1);
        if (first == null || last == null || first.length < 2 || last.length < 2) {
            return false;
        }
        double startGap = distanceKm(from, new Node(from.kind(), from.name(), first[0], first[1]));
        double endGap = distanceKm(to, new Node(to.kind(), to.name(), last[0], last[1]));
        return startGap <= toleranceKm && endGap <= toleranceKm;
    }

    /** 米 → 展示用公里（真实路径里程），最低 1 公里，避免出现 0 km 的线段 */
    public static long metersToKm(long meters) {
        return Math.max(1, Math.round(meters / 1000.0));
    }

    /** 排序后的候选节点（I15 就近推荐）：距离相同按名称，保证同一地址刷新后顺序不跳 */
    public record Ranked(Node node, double km) {
    }

    public static List<Ranked> rankByDistance(Node target, List<Node> candidates) {
        List<Ranked> ranked = new ArrayList<>();
        if (target == null || candidates == null) {
            return ranked;
        }
        for (Node candidate : candidates) {
            if (candidate != null) {
                ranked.add(new Ranked(candidate, distanceKm(target, candidate)));
            }
        }
        ranked.sort(Comparator.comparingDouble(Ranked::km).thenComparing(r -> r.node().name()));
        return ranked;
    }

    /**
     * 自提预计可取时间（I15）：备花时长要贴着门店营业时间算，
     * 「2 小时后可取」在晚上 20:30 下单时是假承诺——门店 21:00 就打烊了。
     *
     * @param openHours     文本形如「09:00-21:00」，解析不出来就退回只报备花时长
     * @param readyMinutes  下单到可取的备花分钟数
     * @return 给人看的文案；门店信息缺失时返回 null，由前端隐藏这一行
     */
    public static String pickupReadyText(String openHours, Integer readyMinutes, java.time.LocalDateTime now) {
        if (readyMinutes == null || readyMinutes <= 0) {
            return null;
        }
        int[] window = parseWindow(openHours);
        if (window == null) {
            return "备花约 " + readyMinutes + " 分钟";
        }
        int open = window[0];
        int close = window[1];
        int nowMinute = now.getHour() * 60 + now.getMinute();
        // 开门前下的单从开门起算，否则会出现「8 点下单说 8 点 30 能取」
        int at = nowMinute < open ? open + readyMinutes : nowMinute + readyMinutes;
        if (at <= close) {
            return "今天 " + hhmm(at) + " 可取";
        }
        return "今天已错过取货时段，明天 " + hhmm(Math.min(close, open + readyMinutes)) + " 起可取";
    }

    /** 营业时间文本 → [开门分钟, 打烊分钟]；不合常理的写法（打烊早于开门）按无效处理 */
    public static int[] parseWindow(String openHours) {
        if (openHours == null || !openHours.contains("-")) {
            return null;
        }
        String[] parts = openHours.split("-");
        int open = toMinutes(parts[0]);
        int close = parts.length > 1 ? toMinutes(parts[1]) : -1;
        if (open < 0 || close < 0 || close <= open) {
            return null;
        }
        return new int[]{open, close};
    }

    private static int toMinutes(String raw) {
        if (raw == null) {
            return -1;
        }
        String text = raw.trim();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,2}):(\\d{2})").matcher(text);
        if (!matcher.find()) {
            return -1;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return -1;
        }
        return hour * 60 + minute;
    }

    private static String hhmm(int minutes) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    /** 干线段用哪种方式跑：由配送方式决定，末端固定同城闪送 */
    public static String trunkMode(String deliveryMethod) {
        if (ShippingPolicy.AIR_COLD.code().equals(deliveryMethod)) {
            return MODE_AIR;
        }
        if (ShippingPolicy.SEA_FRESH.code().equals(deliveryMethod)) {
            return MODE_SEA;
        }
        return MODE_LAND;
    }

    /**
     * 按订单状态推出已完成的干线段数：待付款一段没走，已付款走完结地到分拨，
     * 已发货走完分拨到城市，签收后全部走完。
     */
    public static int passedSegments(String statusCode, int totalSegments) {
        int passed = switch (statusCode == null ? "" : statusCode) {
            case "paid" -> 1;
            case "shipped" -> Math.max(1, totalSegments - 1);
            case "delivered", "completed" -> totalSegments;
            default -> 0;
        };
        return Math.min(passed, totalSegments);
    }

    /**
     * 生成线段：points 至少两个，干线段统一用同一种方式，最后一段视为末端闪送。
     * 只有一个节点（比如缺产地坐标）时返回空集，由调用方决定降级展示。
     */
    public static List<Segment> segments(List<Node> points, String deliveryMethod, String statusCode) {
        if (points == null || points.size() < 2) {
            return List.of();
        }
        int total = points.size() - 1;
        int passed = passedSegments(statusCode, total);
        String trunk = trunkMode(deliveryMethod);
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Node from = points.get(i);
            Node to = points.get(i + 1);
            // 末端段只认「从分拨中心出发去收花点」这一段；缺分拨中心的短链路仍按干线标注
            boolean lastMile = i == total - 1 && KIND_HUB.equals(from.kind()) && !KIND_STORE.equals(to.kind());
            String mode = lastMile ? MODE_LAST_MILE : trunk;
            long km = Math.round(distanceKm(from, to) * lineFactor(deliveryMethod));
            segments.add(new Segment(mode, from.name(), to.name(), km, i < passed, i == passed && "shipped".equals(statusCode)));
        }
        return segments;
    }

    /** 全程估算里程（公里） */
    public static long totalKm(List<Node> points, String deliveryMethod) {
        if (points == null || points.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (int i = 0; i + 1 < points.size(); i++) {
            sum += distanceKm(points.get(i), points.get(i + 1));
        }
        return Math.round(sum * lineFactor(deliveryMethod));
    }

    /** 在若干候选节点里挑离目标最近的一个，用于选分拨中心与自提门店 */
    public static Node nearest(Node target, List<Node> candidates) {
        Node best = null;
        double bestKm = Double.MAX_VALUE;
        if (target == null || candidates == null) {
            return null;
        }
        for (Node candidate : candidates) {
            double km = distanceKm(target, candidate);
            if (km < bestKm) {
                bestKm = km;
                best = candidate;
            }
        }
        return best;
    }
}
