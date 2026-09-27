package org.liuym.flowerv1springboot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminController {

    @GetMapping("/admin")
    public String adminIndex() {
        return "admin/index";
    }

    @GetMapping("/admin/category-list")
    public String categoryList() {
        return "admin/category-list";
    }

    /**
     * 后台弹窗页（Layui layer type:2 iframe），新增与编辑共用同一页面，靠 id 参数区分
     */
    @GetMapping("/admin/category-edit")
    public String categoryEdit() {
        return "admin/category-edit";
    }

    @GetMapping("/admin/product-edit")
    public String productEdit() {
        return "admin/product-edit";
    }

    @GetMapping("/admin/product-list")
    public String productList() {
        return "admin/product-list";
    }

    @GetMapping("/admin/order-list")
    public String orderList() {
        return "admin/order-list";
    }

    @GetMapping("/admin/order-detail/{id}")
    public String orderDetail() {
        return "admin/order-detail";
    }

    @GetMapping("/admin/user-list")
    public String userList() {
        return "admin/user-list";
    }

    @GetMapping("/admin/user-detail/{id}")
    public String userDetail() {
        return "admin/user-detail";
    }

    @GetMapping("/admin/banner-list")
    public String bannerList() {
        return "admin/banner-list";
    }

    @GetMapping("/admin/notice-list")
    public String noticeList() {
        return "admin/notice-list";
    }

    @GetMapping("/admin/coupon-list")
    public String couponList() {
        return "admin/coupon-list";
    }

    @GetMapping("/admin/audit-list")
    public String auditList() {
        return "admin/audit-list";
    }
}