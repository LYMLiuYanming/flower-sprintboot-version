package org.liuym.flowerv1springboot.controller;

import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.CheckoutPolicy;
import org.liuym.flowerv1springboot.common.DeliveryPolicy;
import org.liuym.flowerv1springboot.common.GreetingCardPolicy;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.dto.OrderDtos;
import org.liuym.flowerv1springboot.dto.ShippingDtos;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.service.SlotQuotaService;
import org.liuym.flowerv1springboot.vo.ShippingViews;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 结算页配送试算：一次请求返回可选配送方式的运费、时效与贺卡样式字典。
 * 运费按服务端读到的商品重量与价格算，前端传什么都只当展示用
 */
@RestController
@RequestMapping("/api/shipping")
@Tag(name = "前台 · 配送与贺卡")
public class ShippingController {

    private final ProductService productService;
    private final SlotQuotaService slotQuotaService;

    public ShippingController(ProductService productService, SlotQuotaService slotQuotaService) {
        this.productService = productService;
        this.slotQuotaService = slotQuotaService;
    }

    @PostMapping("/quote")
    public Result<ShippingViews.Quote> quote(@Valid @RequestBody ShippingDtos.QuoteRequest request) {
        BigDecimal goodsAmount = BigDecimal.ZERO;
        BigDecimal lineWeight = BigDecimal.ZERO;
        for (OrderDtos.ItemRequest item : request.items()) {
            Product product = productService.findById(item.productId())
                    .orElseThrow(() -> new BusinessException("商品不存在或已下架"));
            BigDecimal quantity = BigDecimal.valueOf(item.quantity());
            goodsAmount = goodsAmount.add(product.getPrice().multiply(quantity));
            lineWeight = lineWeight.add(ShippingPolicy.unitWeightKg(product.getWeight()).multiply(quantity));
        }
        final BigDecimal total = goodsAmount.setScale(2, RoundingMode.HALF_UP);
        final BigDecimal weight = lineWeight.setScale(2, RoundingMode.HALF_UP);

        LocalDateTime now = LocalDateTime.now();
        ShippingPolicy.Method selected = ShippingPolicy.resolve(request.deliveryMethod());
        // 试算阶段不因时段未填而报错：只把合法时段带入计算，让用户先把配送方式选出来
        LocalDateTime picked = ShippingPolicy.parseSlot(request.deliverySlot());
        LocalDateTime slot = selected.slotAware() && picked != null
                ? ShippingPolicy.requireSlot(selected, now, picked) : null;

        List<ShippingViews.MethodView> options = ShippingPolicy.METHODS.stream()
                .map(method -> ShippingViews.MethodView.of(method, total, weight, now,
                        method.equals(selected) ? slot : null))
                .toList();
        ShippingViews.MethodView chosen = options.stream()
                .filter(o -> o.code().equals(selected.code()))
                .findFirst()
                .orElse(options.get(0));

        return Result.ok(new ShippingViews.Quote(total, weight, selected.code(), chosen.freight(),
                chosen.expectedArriveAt(), options, GreetingCardPolicy.STYLES,
                DeliveryPolicy.PLACEMENTS, DeliveryPolicy.CONTACTS,
                selected.slotAware() ? slotQuotaService.calendar(now, ShippingPolicy.MAX_SLOT_DAYS) : List.of()))
                // 包装费率随试算下发，购物车与结算页都读这一处，避免页面各自硬编码 12 元（B01）
                .with("giftWrapFee", CheckoutPolicy.GIFT_WRAP_FEE);
    }
}
