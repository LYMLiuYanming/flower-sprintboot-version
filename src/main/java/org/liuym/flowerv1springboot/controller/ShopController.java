package org.liuym.flowerv1springboot.controller;

import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Notice;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.service.CategoryService;
import org.liuym.flowerv1springboot.service.NoticeService;
import org.liuym.flowerv1springboot.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Controller
public class ShopController {

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private NoticeService noticeService;

    @GetMapping("/products")
    public String productsPage(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int limit,
            Model model,
            HttpSession session) {
        Object loginUser = session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("keyword", keyword);
        model.addAttribute("currentPage", page);
        return "shop/products";
    }

    @GetMapping("/product/{id}")
    public String productDetailPage(
            @PathVariable UUID id,
            Model model,
            HttpSession session) {
        Object loginUser = session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("selectedCategoryId", null);
        return productService.findById(id)
                .map(product -> {
                    model.addAttribute("product", product);
                    model.addAttribute("relatedProducts", relatedProducts(product));
                    model.addAttribute("categoryChainIds", categoryChainIds(product));
                    model.addAttribute("metaTitle", product.getName() + " · 花语轩 HUAYUXUAN");
                    model.addAttribute("metaDescription", product.getDescription());
                    return "shop/product-detail";
                })
                .orElse("redirect:/products");
    }

    /** 分类专享券可能绑在父分类上：把商品自身分类与其父分类一并交给前端做营销位过滤 */
    private List<UUID> categoryChainIds(Product product) {
        List<UUID> ids = new ArrayList<>();
        if (product.getCategory() == null) {
            return ids;
        }
        UUID categoryId = product.getCategory().getId();
        ids.add(categoryId);
        categoryService.findById(categoryId)
                .map(Category::getParentId)
                .filter(parentId -> parentId != null && !parentId.equals(categoryId))
                .ifPresent(ids::add);
        return ids;
    }

    /** 搭配灵感：同类畅销优先，不足 4 件时用全站畅销补齐，始终排除当前商品 */
    private List<Product> relatedProducts(Product product) {
        UUID categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        List<Product> related = new ArrayList<>(productService.findBestSellers(categoryId, 5).stream()
                .filter(p -> !p.getId().equals(product.getId()))
                .toList());
        if (related.size() < 4) {
            productService.findBestSellers(null, 8).stream()
                    .filter(p -> !p.getId().equals(product.getId()))
                    .filter(p -> related.stream().noneMatch(r -> r.getId().equals(p.getId())))
                    .forEach(related::add);
        }
        return related.size() > 4 ? related.subList(0, 4) : related;
    }

    /** 公告详情：正文入库时已按白名单清洗，页面直接以 HTML 渲染 */
    @GetMapping("/notice/{id}")
    public String noticeDetailPage(@PathVariable UUID id, Model model, HttpSession session) {
        model.addAttribute("loginUser", session.getAttribute("loginUser"));
        return noticeService.findById(id)
                .filter(notice -> Notice.STATUS_ACTIVE.equals(notice.getStatus()))
                .map(notice -> {
                    noticeService.incrementViewCount(id);
                    model.addAttribute("notice", notice);
                    model.addAttribute("metaTitle", notice.getTitle() + " · 花语轩 HUAYUXUAN");
                    return "shop/notice-detail";
                })
                .orElse("redirect:/index");
    }

    @GetMapping("/cart")
    public String cartPage(Model model, HttpSession session) {
        Object loginUser = session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);
        return "shop/cart";
    }

    @GetMapping("/order/confirm")
    public String orderConfirmPage(Model model, HttpSession session) {
        Object loginUser = session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);
        return "shop/order-confirm";
    }

    /** 旧地址兼容：订单列表已统一到个人中心 */
    @GetMapping("/order/list")
    public String orderListPage() {
        return "redirect:/user/orders";
    }

    @GetMapping("/order/{id}")
    public String orderDetailPage(@PathVariable UUID id, Model model, HttpSession session) {
        Object loginUser = session.getAttribute("loginUser");
        model.addAttribute("loginUser", loginUser);
        model.addAttribute("orderId", id);
        return "shop/order-detail";
    }

    /** 鲜花地图：产地溯源 / 销量分布 / 我的订单路线，游客可看前两层 */
    @GetMapping("/flower-map")
    public String flowerMapPage(Model model, HttpSession session) {
        model.addAttribute("loginUser", session.getAttribute("loginUser"));
        return "shop/flower-map";
    }

    /** 我的花田：天气联动的养花养成，需登录 */
    @GetMapping("/garden")
    public String gardenPage(Model model, HttpSession session) {
        model.addAttribute("loginUser", session.getAttribute("loginUser"));
        return "shop/garden";
    }
}