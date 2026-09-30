package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.common.CurrentUser;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.CouponService;
import org.liuym.flowerv1springboot.service.InviteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 营销页面的路由出口：券与营销功能只在 @RestController 里出接口（全局异常处理只认 @RestController），
 * 页面路由必须留在普通 @Controller，这里统一承接 E10/E16/E18/E19 新增的三个页面。
 */
@Controller
public class MarketingPageController {

    @Autowired
    private CouponService couponService;

    @Autowired
    private InviteService inviteService;

    /**
     * E10 领券中心：游客也能浏览，领取时才要求登录。
     * V21 的促销位种子把入口写成了 /user/coupon-center，这里一起挂上，后台改链接也不会点出 404
     */
    @GetMapping({"/coupon-center", "/user/coupon-center"})
    public String couponCenter(Model model, HttpSession session) {
        User loginUser = CurrentUser.of(session);
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("newCustomer", loginUser == null || couponService.isNewCustomer(loginUser.getId()));
        return "user/coupon-center";
    }

    /** E16 我的邀请 */
    @GetMapping("/user/invite")
    public String invite(Model model, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("inviteCode", inviteService.myCode(loginUser.getId()).getCode());
        return "user/invite";
    }

    /** E18 会员权益与开通/续费：等级、折扣、权益清单都来自 /api/promotions/member，页面不写死 */
    @GetMapping("/user/member")
    public String member(Model model, HttpSession session) {
        User loginUser = CurrentUser.require(session);
        model.addAttribute("loginUser", loginUser);
        return "user/member";
    }

    /** E19 促销位后台 */
    @GetMapping("/admin/promotion-list")
    public String promotionList() {
        return "admin/promotion-list";
    }
}
