package org.liuym.flowerv1springboot.controller;

import jakarta.servlet.http.HttpSession;
import org.liuym.flowerv1springboot.model.User;
import org.liuym.flowerv1springboot.service.BannerService;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {

    private final BannerService bannerService;
    private final NoticeService noticeService;
    private final ProductService productService;
    private final CategoryService categoryService;

    public IndexController(BannerService bannerService, NoticeService noticeService,
                        ProductService productService, CategoryService categoryService) {
        this.bannerService = bannerService;
        this.noticeService = noticeService;
        this.productService = productService;
        this.categoryService = categoryService;
    }

    @GetMapping({"/", "/index"})
    public String indexPage(HttpSession session, Model model) {
        User loginUser = (User) session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);

        /* 首页轮播同样受投放时间窗约束，过期活动图不再展示 */
        model.addAttribute("banners", bannerService.findDisplayableBanners());
        model.addAttribute("notices", noticeService.findActiveNotices());

        return "index";
    }
}