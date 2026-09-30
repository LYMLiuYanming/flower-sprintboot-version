package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.FlowerCarePolicy;
import org.liuym.flowerv1springboot.common.GardenPolicy;
import org.liuym.flowerv1springboot.model.Address;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.FlowerOrigin;
import org.liuym.flowerv1springboot.model.FlowerPlot;
import org.liuym.flowerv1springboot.model.PlotExchange;
import org.liuym.flowerv1springboot.model.PlotGrowthLog;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.CouponRepository;
import org.liuym.flowerv1springboot.repository.FlowerOriginRepository;
import org.liuym.flowerv1springboot.repository.FlowerPlotRepository;
import org.liuym.flowerv1springboot.repository.MapQueryRepository;
import org.liuym.flowerv1springboot.repository.PlotExchangeRepository;
import org.liuym.flowerv1springboot.repository.PlotGrowthLogRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.AddressService;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.GardenService;
import org.liuym.flowerv1springboot.service.WeatherService;
import org.liuym.flowerv1springboot.vo.GardenViews;
import org.liuym.flowerv1springboot.vo.GeoViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 花田编排：读天气 → 算成长 → 条件落库 → 记流水 → 发奖励券。
 *
 * <p>三条约束决定了这里的写法：
 * <ul>
 *   <li>成长值只在服务端算，写库一律走条件 UPDATE + 影响行数判断，重复点击与并发不会双发；</li>
 *   <li>奖励复用后台的定向发券出口（{@link CouponService#issue}），养成奖励与自然赠券同一套发行与核销账；</li>
 *   <li>发券前先占位（状态先落库），发券失败整笔回滚，所以既不会「券发了花还在」，也不会「花兑掉了券没到」。</li>
 * </ul>
 */
@Service
@Transactional
public class GardenServiceImpl implements GardenService {

    private static final Logger log = LoggerFactory.getLogger(GardenServiceImpl.class);
    /** 还占着地块的状态：成长中、已成熟、转赠待确认都算「手上的花还没了结」 */
    private static final List<String> ACTIVE = List.of(FlowerPlot.STATUS_GROWING, FlowerPlot.STATUS_MATURE,
            FlowerPlot.STATUS_GIFTED);
    /** 已经开过花的状态，用于统计成就 */
    private static final List<String> BLOOMED = List.of(FlowerPlot.STATUS_MATURE, FlowerPlot.STATUS_REDEEMED,
            FlowerPlot.STATUS_GIFTED);
    private static final String FALLBACK_CITY = "北京";
    private static final int EXCHANGE_PREVIEW = 8;

    private final FlowerPlotRepository plotRepository;
    private final FlowerOriginRepository originRepository;
    private final CouponRepository couponRepository;
    private final CouponService couponService;
    private final UserRepository userRepository;
    private final AddressService addressService;
    private final WeatherService weatherService;
    private final PlotGrowthLogRepository growthLogRepository;
    private final PlotExchangeRepository exchangeRepository;
    private final MapQueryRepository mapQueryRepository;

    public GardenServiceImpl(FlowerPlotRepository plotRepository, FlowerOriginRepository originRepository,
                             CouponRepository couponRepository, CouponService couponService,
                             UserRepository userRepository, AddressService addressService,
                             WeatherService weatherService, PlotGrowthLogRepository growthLogRepository,
                             PlotExchangeRepository exchangeRepository, MapQueryRepository mapQueryRepository) {
        this.plotRepository = plotRepository;
        this.originRepository = originRepository;
        this.couponRepository = couponRepository;
        this.couponService = couponService;
        this.userRepository = userRepository;
        this.addressService = addressService;
        this.weatherService = weatherService;
        this.growthLogRepository = growthLogRepository;
        this.exchangeRepository = exchangeRepository;
        this.mapQueryRepository = mapQueryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public GardenViews.Home home(UUID userId, int slotNo) {
        LocalDate today = LocalDate.now();
        boolean vip = isVip(userId);
        int allowed = GardenPolicy.slotsFor(vip);
        List<FlowerPlot> active = plotRepository.findByUserIdAndStatusInOrderBySlotNoAsc(userId, ACTIVE);
        Map<Integer, FlowerPlot> bySlot = new HashMap<>();
        active.forEach(plot -> bySlot.putIfAbsent(nz(plot.getSlotNo()), plot));

        // 至少展示到会员上限；已经存在的旧地块（例如运营改过等级）也要能进去看
        int shown = Math.max(allowed, bySlot.keySet().stream().max(Comparator.naturalOrder()).orElse(1));
        List<GardenViews.Slot> slots = new ArrayList<>();
        for (int slot = 1; slot <= shown; slot++) {
            FlowerPlot plot = bySlot.get(slot);
            boolean locked = !GardenPolicy.canOpenSlot(slot, vip);
            slots.add(new GardenViews.Slot(slot, GardenPolicy.slotName(slot), view(plot, today),
                    locked, locked ? GardenPolicy.lockedNote(slot) : null, plot != null));
        }
        int focused = Math.min(Math.max(1, slotNo), Math.max(shown, 1));
        FlowerPlot plot = bySlot.get(focused) != null ? bySlot.get(focused) : active.stream().findFirst().orElse(null);

        String city = cityOf(userId);
        GeoViews.WeatherCard card = weatherService.forAddress(city).orElse(null);
        FlowerCarePolicy.Growth growth = weatherService.growthFor(city);
        GardenPolicy.Seed seed = plot == null ? null : GardenPolicy.seed(plot.getSeedCode()).orElse(null);

        List<GardenViews.SeedCard> seeds = GardenPolicy.SEEDS.stream()
                .map(item -> new GardenViews.SeedCard(item.code(), item.name(), item.icon(), item.originName(),
                        item.needGrowth(), item.rewardText(), item.note(), plot == null))
                .toList();

        List<FlowerPlot> history = plotRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long matured = history.stream().filter(p -> BLOOMED.contains(p.getStatus())).count();
        long gifted = history.stream().filter(p -> p.getGiftUserId() != null).count();
        List<GardenViews.Received> received = history.stream()
                .filter(p -> userId.equals(p.getGiftUserId()))
                .sorted(Comparator.comparing(FlowerPlot::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .map(p -> new GardenViews.Received(seedName(p), giverName(p), p.getGiftMessage(),
                        text(p.getUpdatedAt()), null))
                .toList();

        return new GardenViews.Home(view(plot, today), slots, focused, vip, allowed, seeds, GardenPolicy.WATER_PER_DAY,
                card, bonus(growth, card),
                // 取不到实时天气时传 null 而不是「0 加成」：否则页面会说「今日天气平和」，那是编出来的
                GardenPolicy.careNote(seed, card == null ? null : growth),
                plot == null ? new GardenViews.GrowthCurve(0, 0, 0, List.of(), "种下一株花苗后，这里会记录它的成长曲线")
                        : curveOf(plot),
                exchanges(userId).stream().limit(EXCHANGE_PREVIEW).toList(), pendingGifts(userId),
                matured, gifted, (long) received.size(), received,
                // 券的账只在券包里说了算（I12）：这里现读张数，花田不另立一套状态
                (int) mapQueryRepository.gardenCouponCount(userId),
                (int) mapQueryRepository.gardenCouponUsable(userId, LocalDateTime.now()),
                forecastOf(city, today), weatherState(city, card));
    }

    /**
     * 今日 + 未来 3 日预报与逐日配送结论（I08）。
     *
     * <p>建议文案一律取自 {@code FlowerCarePolicy}，页面只做陈列；这里再多算一次 label，
     * 是因为高德把「今天」排在第几行并不稳定，按下标取会把明天写成今天。
     */
    private GardenViews.Forecast forecastOf(String city, LocalDate today) {
        GeoViews.ForecastCard card = weatherService.forecast(city, GardenPolicy.FORECAST_ROWS);
        List<GardenViews.ForecastDayRow> rows = card.days().stream()
                .map(d -> new GardenViews.ForecastDayRow(
                        GardenPolicy.forecastDayLabel(d.date(), today), d.date(), d.dayWeather(), d.nightWeather(),
                        d.dayTemp(), d.nightTemp(), d.level(), d.headline(), d.deliveryNote(),
                        d.recommendColdChain(), GardenPolicy.isSameDay(d.date(), today)))
                .toList();
        return new GardenViews.Forecast(card.city(), card.reportTime(), card.state(), card.note(), rows);
    }

    /**
     * 天气可用性的可读状态（I09/I16）。
     *
     * <p>降级时不给技术原因（infocode、密钥、请求 URL 一类）而是一句「取不到 + 已按常态给建议」，
     * 因为这句话要直接印在顾客看到的卡片上；运营要查细节走 /api/map/weather/status。
     */
    private GardenViews.WeatherState weatherState(String city, GeoViews.WeatherCard card) {
        GeoViews.ServiceStatus service = weatherService.status(city);
        WeatherService.CacheView cache = weatherService.cacheView(city);
        boolean degraded = card == null;
        String state = service.state() == null ? "unknown_city" : service.state();
        return new GardenViews.WeatherState(cache.city(), state, degraded ? degradedNote(state) : cache.note(),
                degraded, service.cacheMinutes(), cache.resolution(), cache.servedFromCache());
    }

    private static String degradedNote(String state) {
        return "unconfigured".equals(state)
                ? "实时天气服务暂未开通，已按城市气候常态给出建议，不影响浇水与兑换"
                : "暂时取不到实时天气，已按城市气候常态给出建议，稍后会自动重试";
    }

    @Override
    public GardenViews.Action plant(UUID userId, String seedCode, int slotNo) {
        GardenPolicy.Seed seed = GardenPolicy.seed(seedCode)
                .orElseThrow(() -> new BusinessException("没有这种花苗"));
        if (!GardenPolicy.canOpenSlot(slotNo, isVip(userId))) {
            throw new BusinessException(GardenPolicy.lockedNote(slotNo));
        }
        if (plotRepository.findFirstByUserIdAndSlotNoAndStatusIn(userId, slotNo, ACTIVE).isPresent()) {
            throw new BusinessException("这块地上还有花没了结，先兑换或送人再开新坑");
        }
        FlowerPlot plot = new FlowerPlot();
        plot.setUserId(userId);
        plot.setSlotNo(slotNo);
        plot.setSeedCode(seed.code());
        plot.setOriginId(originRepository.findAllByOrderByKindAscSortOrderAsc().stream()
                .filter(o -> o.getName().equals(seed.originName())).map(FlowerOrigin::getId).findFirst().orElse(null));
        plot.setGrowth(0);
        plot.setStage(GardenPolicy.STAGE_SEED);
        plot.setStatus(FlowerPlot.STATUS_GROWING);
        FlowerPlot saved;
        try {
            // 条件性写入：V23 的部分唯一索引 (user_id, slot_no) WHERE status='growing' 才是判定依据，
            // 上面那次 find 只是提前给出更友好的一句话，两个请求同时冲进来也只有一条能落
            saved = plotRepository.saveAndFlush(plot);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(GardenPolicy.slotName(slotNo) + "上已经有一株在培育了，刷新花田看看");
        }
        LocalDate today = LocalDate.now();
        GeoViews.WeatherCard card = weatherService.forAddress(cityOf(userId)).orElse(null);
        writeLog(saved, 0, 0, PlotGrowthLog.SOURCE_PLANT, card);
        return new GardenViews.Action("已种下「" + seed.name() + "」，" + GardenPolicy.slotName(slotNo)
                + "每天来浇浇水吧", view(saved, today), bonus(null, card));
    }

    @Override
    public GardenViews.Action water(UUID userId, int slotNo) {
        LocalDate today = LocalDate.now();
        FlowerPlot plot = requireGrowing(userId, slotNo);
        GardenPolicy.Seed seed = GardenPolicy.seed(plot.getSeedCode()).orElse(null);
        if (GardenPolicy.remainingWaterToday(plot.getWaterTimesToday(), plot.getWateredOn(), today) <= 0) {
            throw new BusinessException("今天已经浇过 " + GardenPolicy.WATER_PER_DAY + " 次了，明天再来");
        }
        int stored = nz(plot.getGrowth());
        int base = GardenPolicy.effectiveGrowth(stored, plot.getWateredOn(),
                plot.getCreatedAt() == null ? null : plot.getCreatedAt().toLocalDate(), today);
        int streak = GardenPolicy.nextStreak(plot.getStreakDays(), plot.getWateredOn(), today);
        String city = cityOf(userId);
        FlowerCarePolicy.Growth bonusInfo = weatherService.growthFor(city);
        GeoViews.WeatherCard card = weatherService.forAddress(city).orElse(null);
        int gain = GardenPolicy.waterGain(streak, bonusInfo.bonus());
        int next = base + gain;
        boolean mature = GardenPolicy.mature(next, seed);
        LocalDateTime now = LocalDateTime.now();

        // 闲置回落先落库并单独记一笔，曲线里才会出现「比昨天矮了一截」那个台阶，而不是被浇水量掩盖掉
        if (stored > base) {
            plotRepository.applyDecay(plot.getId(), base, now);
            writeLog(plot, base, base - stored, PlotGrowthLog.SOURCE_DECAY, null);
        }
        int updated = plotRepository.waterConditional(plot.getId(), userId, next,
                GardenPolicy.stageFor(next, seed),
                mature ? FlowerPlot.STATUS_MATURE : FlowerPlot.STATUS_GROWING,
                today, streak, mature ? now : null, GardenPolicy.WATER_PER_DAY, now);
        if (updated == 0) {
            // 条件更新没命中：要么并发把当日次数用完了，要么这株花已被别的请求养熟
            throw new BusinessException("这一轮浇水没接上，页面已刷新，请再看看");
        }
        FlowerPlot fresh = plotRepository.findById(plot.getId()).orElseThrow();
        writeLog(fresh, nz(fresh.getGrowth()), gain, PlotGrowthLog.SOURCE_WATER, card);
        if (mature) {
            writeLog(fresh, nz(fresh.getGrowth()), 0, PlotGrowthLog.SOURCE_MATURE, card);
        }
        String msg = "浇水成功，成长值 +" + gain + (bonusInfo.note() == null ? "" : "（" + bonusInfo.note() + "）");
        if (mature) {
            msg = "花开了！可以兑换优惠券，或者送给重要的人";
        }
        return new GardenViews.Action(msg, view(fresh, today), bonus(bonusInfo, card));
    }

    @Override
    public GardenViews.Reward redeem(UUID userId, int slotNo) {
        FlowerPlot plot = requireMature(userId, slotNo);
        GardenPolicy.Seed seed = GardenPolicy.seed(plot.getSeedCode())
                .orElseThrow(() -> new BusinessException("花种配置有误，请联系客服"));
        int claimed = plotRepository.claimMature(plot.getId(), userId, LocalDateTime.now());
        if (claimed == 0) {
            throw new BusinessException("这束花已经在兑换了，别重复点");
        }
        UserCoupon issued = issueReward(seed, userId);
        plotRepository.attachRedeemedCoupon(plot.getId(), issued.getId(), LocalDateTime.now());
        PlotExchange exchange = recordExchange(plot, seed, PlotExchange.TYPE_REDEEM, PlotExchange.STATE_ACCEPTED,
                issued, userId, displayName(userId), null);
        writeLog(plot, nz(plot.getGrowth()), 0, PlotGrowthLog.SOURCE_REDEEM, null);
        return reward(seed, issued, "「" + seed.name() + "」已兑换成优惠券，结算时记得带上", exchange.getId());
    }

    @Override
    public GardenViews.Reward gift(UUID userId, int slotNo, String username, String message) {
        FlowerPlot plot = requireMature(userId, slotNo);
        GardenPolicy.Seed seed = GardenPolicy.seed(plot.getSeedCode())
                .orElseThrow(() -> new BusinessException("花种配置有误，请联系客服"));
        String target = username == null ? "" : username.trim();
        if (target.isEmpty()) {
            throw new BusinessException("请填写要送给谁");
        }
        User receiver = userRepository.findByUsername(target)
                .orElseThrow(() -> new BusinessException("没有找到用户「" + target + "」"));
        if (receiver.getId().equals(userId)) {
            throw new BusinessException("花要送给别人，别送给自己");
        }
        int updated = plotRepository.giftConditional(plot.getId(), userId, receiver.getId(),
                cut(message, 200), LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException("这束花正在处理中，先刷新看看状态");
        }
        // 券此刻还不发：等收礼方确认（I13），避免花被婉拒之后券已经躺在别人券包里
        PlotExchange exchange = recordExchange(plot, seed, PlotExchange.TYPE_GIFT, PlotExchange.STATE_PENDING,
                null, receiver.getId(), displayName(receiver), cut(message, 200));
        log.info("花田转赠待确认 seed={} from={} to={}", seed.code(), userId, receiver.getId());
        Coupon coupon = couponRepository.findByCode(seed.couponCode()).orElse(null);
        return new GardenViews.Reward("已把「" + seed.name() + "」寄给 " + displayName(receiver)
                + "，等 TA 确认收下才会发券", coupon == null ? seed.rewardText() : coupon.getName(),
                coupon == null ? null : coupon.getAmount(), coupon == null ? null : coupon.getThreshold(),
                coupon == null ? null : coupon.getValidDays(), exchange.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public GardenViews.GrowthCurve curve(UUID userId, int slotNo) {
        FlowerPlot plot = plotRepository.findFirstByUserIdAndSlotNoAndStatusIn(userId, Math.max(1, slotNo), ACTIVE)
                .orElseGet(() -> plotRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                        .filter(p -> nz(p.getSlotNo()) == Math.max(1, slotNo))
                        .findFirst().orElse(null));
        if (plot == null) {
            return new GardenViews.GrowthCurve(0, 0, 0, List.of(), "这块地还没有种过花");
        }
        return curveOf(plot);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GardenViews.ExchangeRow> exchanges(UUID userId) {
        List<PlotExchange> rows = exchangeRepository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
        List<UUID> couponIds = rows.stream().map(PlotExchange::getUserCouponId).filter(java.util.Objects::nonNull).toList();
        Map<UUID, String> states = new HashMap<>();
        Map<UUID, String> expireAt = new HashMap<>();
        if (!couponIds.isEmpty()) {
            // 券状态现读自券包（I12）：花田不回写券，两处账不一致时以券包为准
            for (Object[] row : mapQueryRepository.couponStates(couponIds)) {
                states.put((UUID) row[0], (String) row[1]);
                // 元组结果里的时间列可能是 LocalDateTime 也可能是驱动给的 Timestamp，交给 text(Object) 判型再格式化
                expireAt.put((UUID) row[0], text(row[2]));
            }
        }
        List<GardenViews.ExchangeRow> list = new ArrayList<>();
        for (PlotExchange row : rows) {
            String state = row.getUserCouponId() == null ? null : states.getOrDefault(row.getUserCouponId(), "missing");
            // 券码只在「自己兑换、券确实在本人券包里」时下发；转赠那笔的券记在收礼方名下，不该出现在送礼人的记录上（I12）
            String couponCode = PlotExchange.TYPE_REDEEM.equals(row.getType())
                    && row.getUserCouponId() != null && states.containsKey(row.getUserCouponId())
                    ? row.getCouponCode() : null;
            list.add(new GardenViews.ExchangeRow(row.getId(), row.getPlotId(), nz(row.getSlotNo()),
                    row.getSeedName() == null ? row.getSeedCode() : row.getSeedName(), row.getType(), row.getState(),
                    row.getCouponName(), couponCode, row.getAmount(), row.getThreshold(), row.getValidDays(), state,
                    row.getUserCouponId() == null ? null : expireAt.get(row.getUserCouponId()),
                    row.getUserCouponId(), row.getReceiverName(), row.getMessage(), text(row.getCreatedAt())));
        }
        return list;
    }

    @Override
    @Transactional(readOnly = true)
    public List<GardenViews.GiftRow> pendingGifts(UUID userId) {
        List<GardenViews.GiftRow> list = new ArrayList<>();
        // 送礼方视角：我送出去、还等对方确认的
        plotRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(p -> FlowerPlot.GIFT_PENDING.equals(p.getGiftState()))
                .forEach(p -> list.add(giftRow(p, false)));
        // 收礼方视角：别人送我的、还没确认，前端按 mine=true 显示「收下 / 婉拒」
        plotRepository.findByGiftUserIdAndGiftStateOrderByUpdatedAtDesc(userId, FlowerPlot.GIFT_PENDING,
                        org.springframework.data.domain.PageRequest.of(0, 10))
                .forEach(p -> list.add(giftRow(p, true)));
        return list;
    }

    /* ---------- 转赠确认（I13） ---------- */

    @Override
    public GardenViews.Reward acceptGift(UUID userId, UUID exchangeId) {
        PlotExchange exchange = exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> BusinessException.notFound("这份转赠不存在"));
        if (!userId.equals(exchange.getReceiverId())) {
            throw new BusinessException(403, "这不是寄给你的花");
        }
        if (!PlotExchange.STATE_PENDING.equals(exchange.getState())) {
            throw new BusinessException("这份花已经处理过了");
        }
        int claimed = exchangeRepository.transition(exchangeId, PlotExchange.STATE_PENDING,
                PlotExchange.STATE_ACCEPTED, LocalDateTime.now());
        if (claimed == 0) {
            throw new BusinessException("这份花已经处理过了");
        }
        GardenPolicy.Seed seed = GardenPolicy.seed(exchange.getSeedCode())
                .orElseThrow(() -> new BusinessException("花种配置有误，请联系客服"));
        UserCoupon issued = issueReward(seed, userId);
        LocalDateTime now = LocalDateTime.now();
        exchangeRepository.attachCoupon(exchangeId, issued.getId(), issued.getCode(), issued.getName(),
                issued.getAmount(), issued.getThreshold(), validDays(seed), PlotExchange.STATE_ACCEPTED, now);
        plotRepository.acceptGift(exchange.getPlotId(), userId, issued.getId(), now);
        // 曲线里给收礼方也留一个点：确认那一刻花的成长值定格在成熟阈值
        FlowerPlot gifted = plotRepository.findById(exchange.getPlotId()).orElse(null);
        writeLog(gifted, gifted == null ? 0 : nz(gifted.getGrowth()), 0, PlotGrowthLog.SOURCE_GIFT, null);
        return new GardenViews.Reward("已收下 " + displayName(exchange.getUserId()) + " 养的「" + seed.name()
                + "」，券已放进你的券包", issued.getName(), issued.getAmount(), issued.getThreshold(),
                validDays(seed), exchangeId);
    }

    @Override
    public GardenViews.Action declineGift(UUID userId, UUID exchangeId) {
        PlotExchange exchange = exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> BusinessException.notFound("这份转赠不存在"));
        if (!userId.equals(exchange.getReceiverId())) {
            throw new BusinessException(403, "这不是寄给你的花");
        }
        int updated = exchangeRepository.transition(exchangeId, PlotExchange.STATE_PENDING,
                PlotExchange.STATE_DECLINED, LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException("这份花已经处理过了");
        }
        LocalDate today = LocalDate.now();
        // 婉拒后花退回送礼人：状态从 gifted 回到 mature，券从未发出，所以没有额外账要平
        plotRepository.declineGift(exchange.getPlotId(), userId, LocalDateTime.now());
        FlowerPlot plot = plotRepository.findById(exchange.getPlotId()).orElse(null);
        return new GardenViews.Action("已婉拒这份花，它会退回给 " + displayName(exchange.getUserId())
                + "，TA 还能自己兑换", view(plot, today), null);
    }

    @Override
    public GardenViews.Action cancelGift(UUID userId, UUID exchangeId) {
        PlotExchange exchange = exchangeRepository.findById(exchangeId)
                .orElseThrow(() -> BusinessException.notFound("这份转赠不存在"));
        if (!userId.equals(exchange.getUserId())) {
            throw new BusinessException(403, "只能撤回自己送出的花");
        }
        int updated = exchangeRepository.transition(exchangeId, PlotExchange.STATE_PENDING,
                PlotExchange.STATE_CANCELED, LocalDateTime.now());
        if (updated == 0) {
            throw new BusinessException("这份花已经处理过了");
        }
        plotRepository.cancelGift(exchange.getPlotId(), userId, LocalDateTime.now());
        FlowerPlot plot = plotRepository.findById(exchange.getPlotId()).orElse(null);
        return new GardenViews.Action("已撤回，花回到你的" + GardenPolicy.slotName(nz(exchange.getSlotNo()))
                + "，可以继续兑换", view(plot, LocalDate.now()), null);
    }

    /* ---------- 内部 ---------- */

    private UserCoupon issueReward(GardenPolicy.Seed seed, UUID receiverId) {
        Coupon coupon = couponRepository.findByCode(seed.couponCode())
                .filter(c -> Coupon.STATUS_ACTIVE.equals(c.getStatus()))
                .orElseThrow(() -> new BusinessException("奖励券暂未配置，请先保留这束花，稍后再来兑换"));
        return couponService.issue(coupon.getId(), receiverId);
    }

    private Integer validDays(GardenPolicy.Seed seed) {
        return couponRepository.findByCode(seed.couponCode()).map(Coupon::getValidDays).orElse(null);
    }

    private GardenViews.Reward reward(GardenPolicy.Seed seed, UserCoupon issued, String msg, UUID exchangeId) {
        Coupon coupon = couponRepository.findByCode(seed.couponCode()).orElse(null);
        return new GardenViews.Reward(msg, issued.getName(), issued.getAmount(), issued.getThreshold(),
                coupon == null ? null : coupon.getValidDays(), exchangeId);
    }

    /** 兑换与转赠流水：条款按发券那一刻的快照存下来，后台改模板不会让历史记录变形 */
    private PlotExchange recordExchange(FlowerPlot plot, GardenPolicy.Seed seed, String type, String state,
                                        UserCoupon issued, UUID receiverId, String receiverName, String message) {
        PlotExchange exchange = new PlotExchange();
        exchange.setPlotId(plot.getId());
        exchange.setUserId(plot.getUserId());
        exchange.setSlotNo(nz(plot.getSlotNo()));
        exchange.setSeedCode(seed.code());
        exchange.setSeedName(seed.name());
        exchange.setType(type);
        exchange.setState(state);
        exchange.setReceiverId(receiverId);
        exchange.setReceiverName(receiverName);
        exchange.setMessage(message);
        if (issued != null) {
            exchange.setUserCouponId(issued.getId());
            exchange.setCouponCode(issued.getCode());
            exchange.setCouponName(issued.getName());
            exchange.setAmount(issued.getAmount());
            exchange.setThreshold(issued.getThreshold());
        }
        exchange.setValidDays(validDays(seed));
        try {
            // 一定要 flush：V33 的部分唯一索引若在提交时才炸，整笔事务会被标脏且页面上只剩一句「系统异常」
            return exchangeRepository.saveAndFlush(exchange);
        } catch (DataIntegrityViolationException e) {
            // 条件 UPDATE 已经拦住了重复兑换，走到这里说明是并发挤进了同一瞬间，给一句人话而不是 500
            throw new BusinessException("这一笔已经处理过了，刷新一下花田就能看到最新状态");
        }
    }

    /** 定位要操作的地块：给了具体编号就按编号找，没给且只有一块地时按那块处理（兼容老前端） */
    private FlowerPlot requireGrowing(UUID userId, int slotNo) {
        FlowerPlot plot = pick(userId, slotNo, FlowerPlot.STATUS_GROWING);
        if (!FlowerPlot.STATUS_GROWING.equals(plot.getStatus())) {
            throw new BusinessException("这束花已经开了，先兑换或送人吧");
        }
        return plot;
    }

    private FlowerPlot requireMature(UUID userId, int slotNo) {
        FlowerPlot plot = pick(userId, slotNo, FlowerPlot.STATUS_MATURE);
        if (!FlowerPlot.STATUS_MATURE.equals(plot.getStatus())) {
            if (FlowerPlot.STATUS_GIFTED.equals(plot.getStatus()) && FlowerPlot.GIFT_PENDING.equals(plot.getGiftState())) {
                throw new BusinessException("这束花正在等对方确认，先处理完再操作");
            }
            throw new BusinessException("花还没开，再浇几天水");
        }
        return plot;
    }

    private FlowerPlot pick(UUID userId, int slotNo, String preferred) {
        int slot = Math.max(1, slotNo);
        FlowerPlot exact = plotRepository.findFirstByUserIdAndSlotNoAndStatusIn(userId, slot, ACTIVE).orElse(null);
        if (exact != null && preferred.equals(exact.getStatus())) {
            return exact;
        }
        if (exact != null) {
            throw new BusinessException(GardenPolicy.slotName(slot) + "上是「" + seedName(exact) + "」，"
                    + ("growing".equals(preferred) ? "已经开花了" : "还在长，没到能兑换的程度"));
        }
        // 该地块空着：若手上只有一块活动地，就按它处理，避免老前端不传 slot 时报「还没有花田」
        List<FlowerPlot> active = plotRepository.findByUserIdAndStatusInOrderBySlotNoAsc(userId, ACTIVE);
        if (active.size() == 1 && preferred.equals(active.get(0).getStatus())) {
            return active.get(0);
        }
        if (active.isEmpty()) {
            throw new BusinessException("还没有花田，先选一种花苗种下");
        }
        throw new BusinessException(GardenPolicy.slotName(slot) + "还空着，先种下一株花苗");
    }

    private GardenViews.GrowthCurve curveOf(FlowerPlot plot) {
        List<PlotGrowthLog> logs = growthLogRepository.findTop200ByPlotIdOrderByCreatedAtDesc(plot.getId());
        List<GardenViews.CurvePoint> points = new ArrayList<>();
        GardenPolicy.Seed seed = GardenPolicy.seed(plot.getSeedCode()).orElse(null);
        int need = seed == null ? 100 : seed.needGrowth();
        for (int i = logs.size() - 1; i >= 0; i--) {
            PlotGrowthLog row = logs.get(i);
            points.add(new GardenViews.CurvePoint(text(row.getLoggedAt()), nz(row.getGrowth()), nz(row.getDelta()),
                    row.getStage(), row.getSource(), row.getWeather(), row.getTemperature()));
        }
        int growth = GardenPolicy.effectiveGrowth(nz(plot.getGrowth()), plot.getWateredOn(),
                plot.getCreatedAt() == null ? null : plot.getCreatedAt().toLocalDate(), LocalDate.now());
        String note = logs.isEmpty() ? "这块地刚开始，还没有留下浇水记录"
                : (growth < nz(plot.getGrowth()) ? "曲线末端已按闲置回落过，实际值比最后一条记录更低" : null);
        return new GardenViews.GrowthCurve(points.size(), growth, need, points, note);
    }

    private void writeLog(FlowerPlot plot, int growth, int delta, String source, GeoViews.WeatherCard card) {
        if (plot == null || plot.getId() == null) {
            return;
        }
        PlotGrowthLog row = new PlotGrowthLog();
        row.setPlotId(plot.getId());
        row.setUserId(plot.getUserId());
        row.setSlotNo(nz(plot.getSlotNo()));
        row.setGrowth(growth);
        row.setDelta(delta);
        row.setStage(plot.getStage() == null ? GardenPolicy.STAGE_SEED : plot.getStage());
        row.setSource(source);
        if (card != null) {
            row.setWeather(cut(card.weather(), 30));
            row.setTemperature(card.temperature());
            FlowerCarePolicy.Growth bonus = FlowerCarePolicy.growth(card.weather(), card.temperature());
            row.setSunBonus(bonus.sun());
            row.setWaterBonus(bonus.water());
            row.setStress(bonus.stress());
        }
        row.setLoggedAt(LocalDateTime.now());
        growthLogRepository.save(row);
    }

    private GardenViews.GiftRow giftRow(FlowerPlot plot, boolean mine) {
        return new GardenViews.GiftRow(
                exchangeRepository.findFirstByPlotIdAndState(plot.getId(), PlotExchange.STATE_PENDING)
                        .map(PlotExchange::getId).orElse(null),
                plot.getId(), nz(plot.getSlotNo()), seedName(plot),
                GardenPolicy.seed(plot.getSeedCode()).map(GardenPolicy.Seed::icon).orElse("fa-leaf"),
                displayName(plot.getUserId()), displayName(plot.getGiftUserId()),
                plot.getGiftMessage(), plot.getGiftState(), text(plot.getUpdatedAt()), mine);
    }

    private boolean isVip(UUID userId) {
        return user(userId).map(User::isVip).orElse(false);
    }

    private Optional<User> user(UUID userId) {
        return userId == null ? Optional.empty() : userRepository.findById(userId);
    }

    private GardenViews.WeatherBonus bonus(FlowerCarePolicy.Growth growth, GeoViews.WeatherCard card) {
        if (growth == null) {
            return new GardenViews.WeatherBonus(null, null, null, null,
                    card == null ? null : card.temperature(), card == null ? null : card.weather());
        }
        return new GardenViews.WeatherBonus(growth.sun(), growth.water(), growth.stress(), growth.note(),
                card == null ? null : card.temperature(), card == null ? null : card.weather());
    }

    private GardenViews.Plot view(FlowerPlot plot, LocalDate today) {
        if (plot == null) {
            return null;
        }
        GardenPolicy.Seed seed = GardenPolicy.seed(plot.getSeedCode()).orElse(null);
        int need = seed == null ? 100 : seed.needGrowth();
        int growth = GardenPolicy.effectiveGrowth(nz(plot.getGrowth()), plot.getWateredOn(),
                plot.getCreatedAt() == null ? null : plot.getCreatedAt().toLocalDate(), today);
        long idle = GardenPolicy.idleDays(plot.getWateredOn(),
                plot.getCreatedAt() == null ? null : plot.getCreatedAt().toLocalDate(), today);
        String warning = idle > GardenPolicy.IDLE_GRACE_DAYS
                ? "已经 " + idle + " 天没浇水，成长值每天回落 " + GardenPolicy.DECAY_PER_DAY + " 点" : null;
        int percent = Math.max(0, Math.min(100, (int) Math.round(growth * 100.0 / need)));
        return new GardenViews.Plot(plot.getId(), nz(plot.getSlotNo()), GardenPolicy.slotName(nz(plot.getSlotNo())),
                plot.getSeedCode(), seed == null ? plot.getSeedCode() : seed.name(),
                seed == null ? "fa-leaf" : seed.icon(), seed == null ? null : seed.originName(),
                growth, need, percent, GardenPolicy.stageFor(growth, seed), plot.getStatus(),
                GardenPolicy.remainingWaterToday(plot.getWaterTimesToday(), plot.getWateredOn(), today),
                nz(plot.getWaterTimes()), nz(plot.getStreakDays()), idle, warning,
                GardenPolicy.estimate(growth, seed), GardenPolicy.mature(growth, seed),
                plot.getGiftState(), GardenPolicy.giftStateLabel(plot.getGiftState()),
                plot.getGiftMessage(), plot.getGiftUserId() == null ? null : displayName(plot.getGiftUserId()),
                text(plot.getCreatedAt()));
    }

    /** 天气按哪个城市查：默认收货地址所在市，没有就按北京（花田是全局玩法，不该被地址卡住） */
    private String cityOf(UUID userId) {
        Address address = addressService.defaultAddress(userId);
        if (address == null) {
            return FALLBACK_CITY;
        }
        if (address.getCity() != null && !address.getCity().isBlank()) {
            return address.getCity();
        }
        return address.getProvince() == null || address.getProvince().isBlank() ? FALLBACK_CITY : address.getProvince();
    }

    private String seedName(FlowerPlot plot) {
        return GardenPolicy.seed(plot.getSeedCode()).map(GardenPolicy.Seed::name).orElse(plot.getSeedCode());
    }

    private String giverName(FlowerPlot plot) {
        return displayName(plot.getUserId());
    }

    private String displayName(UUID userId) {
        return user(userId).map(this::displayName).orElse("花友");
    }

    private String displayName(User user) {
        if (user == null) {
            return "花友";
        }
        return user.getFullName() == null || user.getFullName().isBlank() ? user.getUsername() : user.getFullName();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String text(LocalDateTime value) {
        if (value == null) {
            return "";
        }
        String raw = value.toString().replace('T', ' ');
        return raw.substring(0, Math.min(16, raw.length()));
    }

    private static String cut(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 只用于券状态映射表里的时间列：JPQL 的 Object[] 里时间列类型可能是 LocalDateTime 也可能是 Timestamp */
    private static String text(Object value) {
        return value instanceof LocalDateTime time ? text(time) : value == null ? "" : String.valueOf(value);
    }
}
