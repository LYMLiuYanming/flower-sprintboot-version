package org.liuym.flowerv1springboot.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.common.Result;
import org.liuym.flowerv1springboot.dto.GardenDtos;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.GardenService;
import org.liuym.flowerv1springboot.vo.GardenViews;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 我的花田接口：浇水/兑换/转赠都由服务端判定，成长值与每日次数不信任前端。
 *
 * <p>写操作都带地块序号（I11 多块地），服务端用条件 UPDATE 判定，重复提交不会双发成长值或券
 */
@RestController
@RequestMapping("/api/garden")
@Tag(name = "前台 · 我的花田")
public class GardenController {

    private final GardenService gardenService;

    public GardenController(GardenService gardenService) {
        this.gardenService = gardenService;
    }

    @GetMapping("/home")
    @Operation(summary = "花田总览", description = "各地块成长态、可种花苗字典、当日天气加成、成长曲线、兑换与待确认转赠")
    public Result<GardenViews.Home> home(@RequestParam(defaultValue = "1") int slot, HttpSession session) {
        return Result.ok(gardenService.home(me(session).getId(), slot));
    }

    @PostMapping("/plant")
    @Operation(summary = "种下花苗", description = "一块地同时只能培育一种；第 2 块地需会员")
    public Result<GardenViews.Action> plant(@Valid @RequestBody GardenDtos.Plant request, HttpSession session) {
        return Result.ok(gardenService.plant(me(session).getId(), request.seedCode(), request.slot()));
    }

    @PostMapping("/water")
    @Operation(summary = "浇水", description = "每日上限 3 次，成长值叠加连续培育与当日天气加成")
    public Result<GardenViews.Action> water(@RequestBody(required = false) GardenDtos.Water request,
                                            HttpSession session) {
        int slot = request == null ? 1 : request.slotOrDefault();
        return Result.ok(gardenService.water(me(session).getId(), slot));
    }

    @PostMapping("/redeem")
    @Operation(summary = "成熟兑换", description = "把养成的花兑换成一张定向优惠券，进本人券包并留下兑换记录（I12）")
    public Result<GardenViews.Reward> redeem(@RequestBody(required = false) GardenDtos.Redeem request,
                                             HttpSession session) {
        int slot = request == null ? 1 : request.slotOrDefault();
        return Result.ok(gardenService.redeem(me(session).getId(), slot));
    }

    @PostMapping("/gift")
    @Operation(summary = "成熟转赠", description = "按用户名送给好友并留寄语；对方确认收下才发券（I13）")
    public Result<GardenViews.Reward> gift(@Valid @RequestBody GardenDtos.Gift request, HttpSession session) {
        return Result.ok(gardenService.gift(me(session).getId(), request.slotOrDefault(),
                request.username(), request.message()));
    }

    @PostMapping("/gift/accept")
    @Operation(summary = "收下转赠", description = "收礼方确认：奖励券进入本人券包，重复点击只生效一次")
    public Result<GardenViews.Reward> acceptGift(@Valid @RequestBody GardenDtos.GiftDecision request,
                                                 HttpSession session) {
        return Result.ok(gardenService.acceptGift(me(session).getId(), request.exchangeId()));
    }

    @PostMapping("/gift/decline")
    @Operation(summary = "婉拒转赠", description = "花退回送礼人的花田，仍可继续兑换")
    public Result<GardenViews.Action> declineGift(@Valid @RequestBody GardenDtos.GiftDecision request,
                                                  HttpSession session) {
        return Result.ok(gardenService.declineGift(me(session).getId(), request.exchangeId()));
    }

    @PostMapping("/gift/cancel")
    @Operation(summary = "撤回转赠", description = "对方还没确认时，送礼人可把花撤回继续兑换")
    public Result<GardenViews.Action> cancelGift(@Valid @RequestBody GardenDtos.GiftDecision request,
                                                 HttpSession session) {
        return Result.ok(gardenService.cancelGift(me(session).getId(), request.exchangeId()));
    }

    @GetMapping("/exchanges")
    @Operation(summary = "兑换与转赠记录（I12）", description = "含券的当前状态（未使用/已用/已过期），状态现读自券包")
    public Result<List<GardenViews.ExchangeRow>> exchanges(HttpSession session) {
        return Result.ok(gardenService.exchanges(me(session).getId()));
    }

    @GetMapping("/gifts/pending")
    @Operation(summary = "待确认的转赠（I13）", description = "我送出的待确认 + 别人送我的待收下")
    public Result<List<GardenViews.GiftRow>> pendingGifts(HttpSession session) {
        return Result.ok(gardenService.pendingGifts(me(session).getId()));
    }

    @GetMapping("/curve")
    @Operation(summary = "成长曲线（I14）", description = "按地块返回历史成长值与每次变化的成因")
    public Result<GardenViews.GrowthCurve> curve(@RequestParam(defaultValue = "1") int slot, HttpSession session) {
        return Result.ok(gardenService.curve(me(session).getId(), slot));
    }

    /** 兑换记录里的某一笔对应的券（给「去看看这张券」链接用，只读） */
    @GetMapping("/exchange/{id}")
    @Operation(summary = "单笔兑换记录", description = "本人记录才可见，返回该笔流水")
    public Result<GardenViews.ExchangeRow> exchange(@PathVariable UUID id, HttpSession session) {
        User me = me(session);
        return gardenService.exchanges(me.getId()).stream()
                .filter(row -> row.id().equals(id)).findFirst()
                .map(Result::ok)
                .orElseGet(() -> Result.notFound("没有这条兑换记录"));
    }

    private User me(HttpSession session) {
        return CurrentUser.require(session);
    }
}
