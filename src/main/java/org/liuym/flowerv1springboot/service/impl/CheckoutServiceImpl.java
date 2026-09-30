package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.CouponPolicy;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.common.PointsPolicy;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.model.Coupon;
import org.liuym.flowerv1springboot.model.Cart;
import org.liuym.flowerv1springboot.model.CartItem;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.model.UserCoupon;
import org.liuym.flowerv1springboot.repository.CartRepository;
import org.liuym.flowerv1springboot.repository.CheckoutQueryRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.repository.UserRepository;
import org.liuym.flowerv1springboot.service.CartService;
import org.liuym.flowerv1springboot.service.CheckoutService;
import org.liuym.flowerv1springboot.service.SlotQuotaService;
import org.liuym.flowerv1springboot.service.WeatherService;
import org.liuym.flowerv1springboot.vo.CartView;
import org.liuym.flowerv1springboot.vo.CheckoutViews;
import org.liuym.flowerv1springboot.vo.ShippingViews;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 结算试算与再次购买（B08-B16、B21-B24）。
 *
 * <p>这里算出的每个数字都必须与 OrderServiceImpl 建单时一致，所以两侧共用 CheckoutPolicy；
 * 任何"前端自己再算一遍"的字段都不下发，避免页面与订单两套账。
 */
@Service
@Transactional
public class CheckoutServiceImpl implements CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutServiceImpl.class);

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CheckoutQueryRepository checkoutQueryRepository;
    private final SlotQuotaService slotQuotaService;
    private final WeatherService weatherService;
    private final CartService cartService;

    public CheckoutServiceImpl(CartRepository cartRepository,
                               ProductRepository productRepository,
                               UserRepository userRepository,
                               CheckoutQueryRepository checkoutQueryRepository,
                               SlotQuotaService slotQuotaService,
                               WeatherService weatherService,
                               CartService cartService) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.checkoutQueryRepository = checkoutQueryRepository;
        this.slotQuotaService = slotQuotaService;
        this.weatherService = weatherService;
        this.cartService = cartService;
    }

    /** 结算行：价格/库存/分类都是服务端读到的当前值，issue 非空表示该行不能成交（B19） */
    private record PricedLine(UUID cartItemId, UUID productId, String productName, String productImage,
                              String unit, String weight, BigDecimal price, int quantity, BigDecimal subtotal,
                              Integer stock, int maxQuantity, boolean giftWrap, String note,
                              BigDecimal addedPrice, BigDecimal priceDrop, UUID categoryId, String issue) {

        CouponPolicy.Line couponLine() {
            return new CouponPolicy.Line(categoryId, subtotal);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public CheckoutViews.Summary summary(UUID userId, OrderDtos.CheckoutRequest request) {
        LocalDateTime now = LocalDateTime.now();
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        List<PricedLine> lines = resolveLines(user, request);

        List<PricedLine> sellable = lines.stream().filter(l -> l.issue() == null).toList();
        BigDecimal goods = sellable.stream().map(PricedLine::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        BigDecimal weight = ShippingPolicy.totalWeightKg(sellable.stream()
                .map(l -> ShippingPolicy.unitWeightKg(l.weight()).multiply(BigDecimal.valueOf(l.quantity())))
                .toList());

        // B01 礼品包装：行上的标记只说明要包，费用按整单收一次
        long wrapKinds = sellable.stream().filter(PricedLine::giftWrap).count();
        if (sellable.isEmpty()) {
            wrapKinds = 0;
        }
        BigDecimal giftWrapFee = CheckoutPolicy.giftWrapFee(wrapKinds);

        ShippingPolicy.Method method = ShippingPolicy.resolve(request.deliveryMethod());
        LocalDateTime picked = ShippingPolicy.parseSlot(request.deliverySlot());
        // 试算不因时段越界直接报错：先把金额算出来，越界只影响该方式下的时效展示（B22 在下单时才硬校验）
        LocalDateTime slot = method.slotAware() && picked != null ? softSlot(method, now, picked) : null;
        BigDecimal freight = ShippingPolicy.freightOf(method, goods, weight);

        BigDecimal vipDiscount = user != null && user.isVip() ? PointsPolicy.vipDiscount(goods) : BigDecimal.ZERO;

        List<CheckoutViews.CouponOption> coupons = user == null
                ? List.of()
                : couponPanel(user.getId(), sellable, request.userCouponId());
        CheckoutViews.CouponOption chosen = pickCoupon(coupons, request.userCouponId());
        BigDecimal couponDiscount = chosen == null || chosen.discount() == null
                ? BigDecimal.ZERO : chosen.discount();

        // B10/B21：滑杆给的是用户意图，真正抵扣额由服务端按折后 20% 与持有积分收敛
        BigDecimal payableBeforePoints = goods.subtract(vipDiscount).subtract(couponDiscount);
        CheckoutPolicy.PointsClamp points = user == null
                ? new CheckoutPolicy.PointsClamp(BigDecimal.ZERO.setScale(2), 0,
                CheckoutPolicy.pointsCap(payableBeforePoints), false)
                : CheckoutPolicy.clampPoints(request.pointsDeduction(), payableBeforePoints, user.getPoints());

        CheckoutPolicy.Amounts amounts = CheckoutPolicy.price(goods, giftWrapFee, freight,
                vipDiscount, couponDiscount, points.deduction());

        List<ShippingViews.MethodView> options = ShippingPolicy.METHODS.stream()
                .map(m -> ShippingViews.MethodView.of(m, goods, weight, now, m == method ? slot : null))
                .toList();

        return new CheckoutViews.Summary(
                user == null,
                user == null ? "登录后即可使用优惠券与积分抵扣并提交订单" : null,
                lines.stream().map(this::toViewLine).toList(),
                (int) lines.stream().filter(l -> l.issue() != null).count(),
                sellable.stream().map(l -> l.priceDrop() == null ? BigDecimal.ZERO : l.priceDrop())
                        .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP),
                amounts,
                goods,
                giftWrapFee,
                (int) wrapKinds,
                weight,
                method.code(),
                method.name(),
                ShippingPolicy.arriveAt(method, now, slot),
                options,
                coupons,
                chosen == null ? null : chosen.id(),
                chosen == null ? null : chosen.name(),
                new CheckoutViews.PointsState(
                        user == null ? 0 : Optional.ofNullable(user.getPoints()).orElse(0),
                        org.liuym.flowerv1springboot.common.PointsPolicy
                                .yuanFor(user == null ? 0 : Optional.ofNullable(user.getPoints()).orElse(0)),
                        points.cap(),
                        points.deduction(),
                        points.points(),
                        points.truncated(),
                        user == null ? "登录后再选择积分抵扣" : CheckoutPolicy.POINTS_CAP_TEXT),
                earliestSlot(now),
                advisory(request.receiverAddress(), method, slot),
                CheckoutPolicy.REMARK_PRESETS,
                new CheckoutViews.CardRules(CheckoutPolicy.CARD_MESSAGE_LIMIT, CheckoutPolicy.CARD_MESSAGE_OVER),
                messages());
    }

    /** 文案与校验同源（B19-B23）：页面提示不再自己编句子 */
    private static Map<String, String> messages() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("lines", "商品上架状态与库存逐行校验，不通过会整单退回并列出具体花礼");
        map.put("giftWrap", "礼品包装每单收取 ¥" + CheckoutPolicy.GIFT_WRAP_FEE.toPlainString() + "，同单多束不叠加");
        map.put("coupon", "优惠券门槛按本单实际成交行计算，指定分类券不覆盖其它分类花礼");
        map.put("points", CheckoutPolicy.POINTS_CAP_TEXT);
        map.put("minPay", CheckoutPolicy.minPayText());
        map.put("slot", "预约时段需晚于当前时间 1 小时、落在 08:00–21:00 且未约满");
        map.put("card", "贺卡留言上限 " + CheckoutPolicy.CARD_MESSAGE_LIMIT + " 字");
        return Map.copyOf(map);
    }

    /**
     * 结算行来源三选一：勾选的购物车行 > 显式明细（游客试算/立即购买） > 购物车全部可购行
     */
    private List<PricedLine> resolveLines(User user, OrderDtos.CheckoutRequest request) {
        Cart cart = user == null ? null
                : cartRepository.findByUserIdWithItems(user.getId()).orElse(null);
        Map<UUID, CartItem> byId = new LinkedHashMap<>();
        if (cart != null && cart.getItems() != null) {
            cart.getItems().forEach(item -> {
                if (item.getId() != null) {
                    byId.put(item.getId(), item);
                }
            });
        }

        List<UUID> pickedIds = request.cartItemIds() == null ? List.of()
                : request.cartItemIds().stream().filter(Objects::nonNull).toList();
        if (!pickedIds.isEmpty()) {
            return pickedIds.stream().map(byId::get).filter(Objects::nonNull)
                    .map(item -> fromCartItem(item, request.giftWrap())).toList();
        }

        List<OrderDtos.ItemRequest> items = request.items() == null ? List.of() : request.items();
        if (!items.isEmpty()) {
            List<PricedLine> lines = new ArrayList<>();
            for (OrderDtos.ItemRequest item : items) {
                CartItem row = item.cartItemId() == null ? null : byId.get(item.cartItemId());
                lines.add(fromRequest(item, row, request.giftWrap()));
            }
            return lines;
        }

        if (cart == null || cart.getItems() == null) {
            return List.of();
        }
        return cart.getItems().stream().map(item -> fromCartItem(item, request.giftWrap())).toList();
    }

    private PricedLine fromCartItem(CartItem item, Boolean overrideWrap) {
        Product product = item.getProduct();
        int quantity = Optional.ofNullable(item.getQuantity()).orElse(1);
        BigDecimal price = product != null && product.getPrice() != null
                ? product.getPrice()
                : BigDecimal.valueOf(Optional.ofNullable(item.getPrice()).orElse(0.0))
                .setScale(2, RoundingMode.HALF_UP);
        Integer stock = product == null ? null : product.getStock();
        boolean purchasable = item.isPurchasable();
        String issue = purchasable ? null : issueText(product, quantity, stock);
        int maxQuantity = Math.min(Optional.ofNullable(stock).orElse(0), CheckoutPolicy.MAX_QUANTITY_PER_ITEM);
        boolean wrap = overrideWrap != null ? overrideWrap : item.isGiftWrapped();
        return new PricedLine(item.getId(), product == null ? null : product.getId(),
                product == null ? "商品已失效" : product.getName(),
                product == null ? null : product.getMainImage(),
                product == null ? "束" : product.getUnit(),
                product == null ? null : product.getWeight(),
                price, quantity, price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP),
                stock, maxQuantity, wrap, item.getNote(), item.getAddedPrice(), item.priceDrop(),
                product == null || product.getCategory() == null ? null : product.getCategory().getId(),
                issue);
    }

    /** 显式明细（含游客试算）：没有购物车行时按商品当前值建线，包装取整单开关 */
    private PricedLine fromRequest(OrderDtos.ItemRequest item, CartItem row, Boolean overrideWrap) {
        if (row != null) {
            return fromCartItem(row, overrideWrap);
        }
        Product product = item.productId() == null ? null
                : productRepository.findById(item.productId()).orElse(null);
        int quantity = Optional.ofNullable(item.quantity()).orElse(1);
        Integer stock = product == null ? null : product.getStock();
        String issue = product == null ? "商品不存在或已下架" : issueText(product, quantity, stock);
        BigDecimal price = product == null || product.getPrice() == null
                ? BigDecimal.ZERO.setScale(2) : product.getPrice();
        int maxQuantity = Math.min(Optional.ofNullable(stock).orElse(0), CheckoutPolicy.MAX_QUANTITY_PER_ITEM);
        return new PricedLine(null, product == null ? item.productId() : product.getId(),
                product == null ? "商品已失效" : product.getName(),
                product == null ? null : product.getMainImage(),
                product == null ? "束" : product.getUnit(),
                product == null ? null : product.getWeight(),
                price, quantity, price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP),
                stock, maxQuantity, Boolean.TRUE.equals(overrideWrap), CheckoutPolicy.cleanNote(item.note()),
                null, BigDecimal.ZERO,
                product == null || product.getCategory() == null ? null : product.getCategory().getId(),
                issue);
    }

    private static String issueText(Product product, int quantity, Integer stock) {
        if (product == null || !Boolean.TRUE.equals(product.getIsActive())) {
            return "已下架";
        }
        int available = stock == null ? 0 : stock;
        if (available <= 0) {
            return "已售罄";
        }
        if (quantity > available) {
            return "库存不足，最多可购 " + available + " 件";
        }
        if (quantity > CheckoutPolicy.MAX_QUANTITY_PER_ITEM) {
            return "单个花礼最多购买 " + CheckoutPolicy.MAX_QUANTITY_PER_ITEM + " 件";
        }
        return null;
    }

    private CheckoutViews.Line toViewLine(PricedLine line) {
        return new CheckoutViews.Line(line.cartItemId(), line.productId(), line.productName(), line.productImage(),
                line.unit(), line.price(), line.quantity(), line.subtotal(), line.stock(), line.maxQuantity(),
                line.giftWrap(), line.note(), line.addedPrice(), line.priceDrop(), line.issue());
    }

    /**
     * 券面板（B09）：券条款读 user_coupon（只走本批次的 CheckoutQueryRepository），
     * 可抵金额按本单实际成交行算，最后按省额倒序并标出「本单最优券」。
     */
    private List<CheckoutViews.CouponOption> couponPanel(UUID userId, List<PricedLine> sellable, UUID requestedId) {
        List<UserCoupon> held = checkoutQueryRepository.findUsableCoupons(userId, LocalDateTime.now());
        if (held.isEmpty()) {
            return List.of();
        }
        List<CouponPolicy.Line> lines = sellable.stream().map(PricedLine::couponLine).toList();
        List<CheckoutViews.CouponOption> scored = new ArrayList<>();
        for (UserCoupon coupon : held) {
            boolean categoryScope = Coupon.SCOPE_CATEGORY.equals(coupon.getScope()) && coupon.getCategoryId() != null;
            List<UUID> scopeIds = categoryScope
                    ? checkoutQueryRepository.categoryScopeIds(coupon.getCategoryId()) : List.of();
            long matched = categoryScope
                    ? lines.stream().filter(l -> l.categoryId() != null && scopeIds.contains(l.categoryId())).count()
                    : lines.size();
            BigDecimal base = CouponPolicy.baseAmount(coupon, scopeIds, lines);
            BigDecimal discount = CouponPolicy.discountOf(coupon, base);
            String reason = discount == null ? couponReason(coupon, categoryScope, matched, base) : null;
            scored.add(new CheckoutViews.CouponOption(coupon.getId(), coupon.getName(), ruleText(coupon),
                    coupon.getThreshold(), coupon.getAmount(), coupon.getDiscountRate(), coupon.getMaxDiscount(),
                    coupon.getScope(), coupon.getCategoryId(), coupon.getExpireAt(), discount, false,
                    discount == null ? BigDecimal.ZERO.setScale(2) : discount, reason));
        }
        List<CheckoutViews.CouponOption> usable = scored.stream()
                .filter(c -> c.discount() != null)
                .sorted(Comparator.comparing(CheckoutViews.CouponOption::discount).reversed())
                .toList();
        if (usable.isEmpty()) {
            return scored;
        }
        BigDecimal second = usable.size() > 1 ? usable.get(1).discount() : BigDecimal.ZERO;
        List<CheckoutViews.CouponOption> result = new ArrayList<>(scored.size());
        for (CheckoutViews.CouponOption option : scored) {
            boolean best = option.id().equals(usable.get(0).id());
            BigDecimal saving = option.discount() == null ? BigDecimal.ZERO.setScale(2)
                    : best ? option.discount().subtract(second).setScale(2, RoundingMode.HALF_UP) : option.discount();
            result.add(new CheckoutViews.CouponOption(option.id(), option.name(), option.ruleText(),
                    option.threshold(), option.amount(), option.discountRate(), option.maxDiscount(),
                    option.scope(), option.categoryId(), option.expireAt(), option.discount(), best, saving,
                    option.reason()));
        }
        return result;
    }

    /** 选券：显式选的券必须在面板里，否则回落到最优券（B09） */
    private static CheckoutViews.CouponOption pickCoupon(List<CheckoutViews.CouponOption> coupons, UUID requestedId) {
        if (coupons.isEmpty()) {
            return null;
        }
        if (requestedId != null) {
            return coupons.stream()
                    .filter(c -> requestedId.equals(c.id()) && c.discount() != null)
                    .findFirst().orElse(null);
        }
        return coupons.stream().filter(CheckoutViews.CouponOption::best).findFirst().orElse(null);
    }

    /** 券不可用的原因要说清是「差多少」还是「分类不沾边」，只写"不满足门槛"用户会反复加购 */
    private static String couponReason(UserCoupon coupon, boolean categoryScope, long matched, BigDecimal base) {
        if (categoryScope && matched <= 0) {
            return CheckoutPolicy.couponMismatch(coupon.getName(), true, matched).orElse("本券不适用当前花礼分类");
        }
        BigDecimal threshold = coupon.getThreshold() == null ? BigDecimal.ZERO : coupon.getThreshold();
        if (base.compareTo(threshold) < 0) {
            return "还差 ¥" + threshold.subtract(base).setScale(2, RoundingMode.HALF_UP).toPlainString() + " 可用";
        }
        return "本券当前不可用";
    }

    private static String ruleText(UserCoupon coupon) {
        boolean hasThreshold = coupon.getThreshold() != null && coupon.getThreshold().compareTo(BigDecimal.ZERO) > 0;
        String threshold = hasThreshold ? "满" + plain(coupon.getThreshold()) : "";
        if (Coupon.TYPE_CASH.equals(coupon.getType()) && coupon.getAmount() != null) {
            return threshold + "减" + plain(coupon.getAmount());
        }
        if (Coupon.TYPE_DISCOUNT.equals(coupon.getType()) && coupon.getDiscountRate() != null) {
            String text = threshold + "打" + plain(coupon.getDiscountRate().multiply(BigDecimal.TEN)) + "折";
            return coupon.getMaxDiscount() != null && coupon.getMaxDiscount().compareTo(BigDecimal.ZERO) > 0
                    ? text + "（封顶" + plain(coupon.getMaxDiscount()) + "）" : text;
        }
        return hasThreshold ? threshold + "可用" : coupon.getName();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 最快可约（B13）：第一个既没过备花提前量也没约满的时段 */
    private CheckoutViews.SlotPick earliestSlot(LocalDateTime now) {
        List<ShippingViews.SlotView> calendar = slotQuotaService.calendar(now, ShippingPolicy.MAX_SLOT_DAYS);
        List<CheckoutPolicy.SlotCandidate> candidates = calendar.stream()
                .map(s -> new CheckoutPolicy.SlotCandidate(s.date(), s.hour(), !s.closed() && s.remaining() > 0))
                .toList();
        return CheckoutPolicy.firstBookable(candidates)
                .map(slot -> new CheckoutViews.SlotPick(slot.date().toString(), slot.hour(),
                        ShippingPolicy.slotHourText(slot.hour()), slot.date().atTime(slot.hour(), 0)))
                .orElse(null);
    }

    /** 天气改约建议（B15）：外部天气不可用就整块不出现，绝不阻断试算 */
    private CheckoutViews.Advisory advisory(String address, ShippingPolicy.Method method, LocalDateTime slot) {
        if (address == null || address.isBlank()) {
            return null;
        }
        try {
            return weatherService.forAddress(address)
                    .flatMap(card -> CheckoutPolicy.weatherAdvisory(card.level(), card.weather(),
                            card.temperature(), method.code(), slot))
                    .map(a -> new CheckoutViews.Advisory(a.level(), a.text(), a.suggestDeliveryMethod(),
                            a.suggestSlotAfter() == null ? null : new CheckoutViews.SlotPick(
                                    a.suggestSlotAfter().substring(0, 10),
                                    Integer.parseInt(a.suggestSlotAfter().substring(11, 13)),
                                    ShippingPolicy.slotHourText(Integer.parseInt(a.suggestSlotAfter().substring(11, 13))),
                                    LocalDateTime.parse(a.suggestSlotAfter()))))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("结算页天气建议获取失败：{}", e.getMessage());
            return null;
        }
    }

    /** 试算阶段的时段宽松校验：越界只丢掉时段，不让整页金额出不来 */
    private static LocalDateTime softSlot(ShippingPolicy.Method method, LocalDateTime now, LocalDateTime picked) {
        try {
            return ShippingPolicy.requireSlot(method, now, picked);
        } catch (BusinessException e) {
            return null;
        }
    }

    @Override
    public CheckoutViews.ReorderReport reorder(UUID userId, UUID orderId) {
        UUID owner = checkoutQueryRepository.findOrderOwnerId(orderId);
        if (owner == null) {
            throw BusinessException.notFound("订单不存在");
        }
        if (!owner.equals(userId)) {
            throw BusinessException.forbidden("无权再次购买该订单");
        }
        List<CheckoutQueryRepository.ReorderLine> history = checkoutQueryRepository.findReorderLines(orderId);
        if (history.isEmpty()) {
            throw new BusinessException("该订单没有可再次购买的花礼");
        }

        List<String> adjusted = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int kinds = 0;
        int units = 0;
        for (CheckoutQueryRepository.ReorderLine line : history) {
            int wanted = Optional.ofNullable(line.getQuantity()).orElse(1);
            int stock = Optional.ofNullable(line.getStock()).orElse(0);
            if (!Boolean.TRUE.equals(line.getActive())) {
                skipped.add("「" + line.getProductName() + "」已下架");
                continue;
            }
            if (stock <= 0) {
                skipped.add("「" + line.getProductName() + "」暂时缺货");
                continue;
            }
            int addable = Math.min(wanted, Math.min(stock, CheckoutPolicy.MAX_QUANTITY_PER_ITEM));
            if (addable < wanted) {
                adjusted.add("「" + line.getProductName() + "」库存仅 " + stock + " 件，已按 " + addable + " 件加回");
            }
            try {
                cartService.addItem(userId, line.getProductId(), addable);
                kinds++;
                units += addable;
            } catch (BusinessException e) {
                skipped.add("「" + line.getProductName() + "」" + e.getMessage());
            }
        }
        if (kinds == 0) {
            throw new BusinessException("这单里的花礼都不可再购：" + String.join("；", skipped));
        }
        Cart cart = cartService.getCartByUserId(userId);
        String notice = "已加回 " + kinds + " 种花礼（共 " + units + " 件）"
                + (skipped.isEmpty() ? "" : "，" + skipped.size() + " 种不可再购已跳过");
        return new CheckoutViews.ReorderReport(kinds, units, adjusted, skipped, notice, CartView.from(cart));
    }
}
