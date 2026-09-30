package org.liuym.flowerv1springboot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class UserCenterController {

    @GetMapping("/user/profile")
    public String profile() {
        return "user/profile";
    }

    @GetMapping("/user/orders")
    public String orders() {
        return "user/orders";
    }

    @GetMapping("/user/addresses")
    public String addresses() {
        return "user/addresses";
    }

    @GetMapping("/user/favorites")
    public String favorites() {
        return "user/favorites";
    }

    @GetMapping("/user/coupons")
    public String coupons() {
        return "user/coupons";
    }

    /** 积分明细页（D20） */
    @GetMapping("/user/points")
    public String points() {
        return "user/points";
    }

    @GetMapping("/user/settings")
    public String settings() {
        return "user/settings";
    }
}
