package org.liuym.flowerv1springboot;

import org.liuym.flowerv1springboot.model.*;
import org.liuym.flowerv1springboot.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * 启动期种子数据。守卫一律按“业务唯一键是否存在”判断，而非“表是否为空”：
 * 表级空判断在 devtools 热重启/多实例并发启动下会重复写入，历史库中已出现过 11 份重复轮播。
 * 结构维护已交给 Flyway（db/migration），此处不再执行任何 DDL。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository userRepository;
    private final BannerRepository bannerRepository;
    private final NoticeRepository noticeRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository,
                           BannerRepository bannerRepository,
                           NoticeRepository noticeRepository,
                           CategoryRepository categoryRepository,
                           ProductRepository productRepository,
                           PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.bannerRepository = bannerRepository;
        this.noticeRepository = noticeRepository;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        safeRun(this::migratePlainPasswords, "密码迁移");
        safeRun(this::initUsers, "用户种子");
        safeRun(this::initCategories, "分类种子");
        safeRun(this::initProducts, "商品种子");
        safeRun(this::initBanners, "轮播图种子");
        safeRun(this::initNotices, "公告种子");
    }

    private void safeRun(ThrowingInit action, String name) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("{}失败，不影响应用启动: {}", name, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ThrowingInit {
        void run() throws Exception;
    }

    /**
     * 存量明文密码一次性升级为 BCrypt 哈希（历史库中 password 无 $2 前缀者）
     */
    private void migratePlainPasswords() {
        int upgraded = 0;
        for (User user : userRepository.findAll()) {
            String pwd = user.getPassword();
            if (pwd != null && !pwd.startsWith("$2")) {
                user.setPassword(passwordEncoder.encode(pwd));
                userRepository.save(user);
                upgraded++;
            }
        }
        if (upgraded > 0) {
            log.info("已将 {} 个明文密码升级为 BCrypt 哈希", upgraded);
        }
    }

    private void initUsers() {
        if (userRepository.findByUsername("admin").isEmpty()) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setFullName("管理员");
            admin.setPhone("13800138000");
            admin.setEmail("admin@huayuxuan.com");
            admin.setUserType(User.TYPE_ADMIN);
            admin.setMemberLevel(User.MEMBER_VIP);
            admin.setStatus(User.STATUS_ACTIVE);
            userRepository.save(admin);
        }

        if (userRepository.findByUsername("testuser").isEmpty()) {
            User customer = new User();
            customer.setUsername("testuser");
            customer.setPassword(passwordEncoder.encode("123456"));
            customer.setFullName("测试用户");
            customer.setPhone("13900139000");
            customer.setEmail("test@huayuxuan.com");
            customer.setUserType(User.TYPE_CUSTOMER);
            customer.setMemberLevel(User.MEMBER_ORDINARY);
            customer.setStatus(User.STATUS_ACTIVE);
            customer.setPoints(100);
            userRepository.save(customer);
        }

        log.info("演示账号就绪：admin/admin123（管理员）、testuser/123456（顾客）");
    }

    private void initCategories() {
        String[][] seeds = {
                {"玫瑰花", "浪漫玫瑰，传递爱意", "1"},
                {"康乃馨", "感恩母爱，温馨祝福", "2"},
                {"百合花", "百年好合，美好祝福", "3"},
                {"郁金香", "高贵典雅，爱的表白", "4"},
                {"向日葵", "阳光积极，活力满满", "5"},
                {"满天星", "清新浪漫，永恒守候", "6"},
        };
        int created = 0;
        for (String[] seed : seeds) {
            if (categoryRepository.existsByName(seed[0])) {
                continue;
            }
            Category category = new Category();
            category.setName(seed[0]);
            category.setDescription(seed[1]);
            category.setSortOrder(Integer.parseInt(seed[2]));
            category.setIsActive(true);
            categoryRepository.save(category);
            created++;
        }
        categoryRepository.flush();
        if (created > 0) {
            log.info("新增分类 {} 个", created);
        }
    }

    private void initProducts() {
        Map<String, Category> categoryMap = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getName, c -> c, (a, b) -> a));
        if (categoryMap.isEmpty()) {
            return;
        }
        var categoryList = new java.util.ArrayList<>(categoryMap.values());

        // 精确名 -> 模糊包含 -> 轮询兜底，保证 category 永不为 null（兼容库中历史分类名）
        BiFunction<String, Integer, Category> pickCategory = (name, idx) -> {
            Category exact = categoryMap.get(name);
            if (exact != null) {
                return exact;
            }
            return categoryMap.entrySet().stream()
                    .filter(e -> e.getKey().contains(name) || name.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(categoryList.get(idx % categoryList.size()));
        };

        String[][] seeds = {
                // code, name, description, price, originalPrice, stock, salesCount, category, image, featured, isNew, tags
                {"ROSE-R11", "红玫瑰花束", "11朵红玫瑰，象征一心一意的爱", "99.00", "129.00", "100", "50", "玫瑰花", "rose-red", "1", "0", "热恋,表白,经典"},
                {"ROSE-P19", "粉玫瑰花束", "19朵粉玫瑰，浪漫温馨", "129.00", "169.00", "80", "30", "玫瑰花", "rose-pink", "0", "1", "初恋,生日,温馨"},
                {"CAR-PINK", "康乃馨花束", "粉色康乃馨，送给母亲的爱", "79.00", "99.00", "120", "80", "康乃馨", "carnation", "1", "0", "母亲节,感恩,温情"},
                {"LILY-WHITE", "百合花束", "白色百合，百年好合", "149.00", "189.00", "60", "25", "百合花", "lily", "0", "0", "祝福,婚礼,清雅"},
                {"TULIP-MIX", "郁金香花束", "彩色郁金香，高贵典雅", "169.00", "199.00", "50", "15", "郁金香", "tulip", "1", "1", "高雅,商务,庆祝"},
                {"SUNFLOWER", "向日葵花束", "向日葵，阳光般的温暖", "89.00", "119.00", "90", "40", "向日葵", "sunflower", "0", "1", "开业,祝福,阳光"},
                {"BABYDRY", "满天星干花", "满天星干花，永恒的守候", "59.00", "79.00", "200", "100", "满天星", "babysbreath", "0", "0", "干花,家居,永恒"},
                {"ROSE-BLUE", "蓝玫瑰花束", "蓝色妖姬，独特的爱", "199.00", "239.00", "40", "20", "玫瑰花", "rose-blue", "1", "0", "稀有,浪漫,个性"},
        };

        int idx = 0;
        int created = 0;
        for (String[] seed : seeds) {
            idx++;
            if (productRepository.findByCode(seed[0]).isPresent()) {
                continue;
            }
            Product product = new Product();
            product.setCode(seed[0]);
            product.setName(seed[1]);
            product.setDescription(seed[2]);
            product.setPrice(new BigDecimal(seed[3]));
            product.setOriginalPrice(new BigDecimal(seed[4]));
            product.setStock(Integer.parseInt(seed[5]));
            product.setSalesCount(Integer.parseInt(seed[6]));
            product.setCategory(pickCategory.apply(seed[7], idx));
            product.setMainImage("/img/products/" + seed[8] + ".svg");
            product.setImages("/img/products/" + seed[8] + ".svg,/img/products/" + seed[8] + "-2.svg");
            product.setTags(seed[11]);
            product.setIsFeatured("1".equals(seed[9]));
            product.setIsNew("1".equals(seed[10]));
            product.setIsActive(true);
            product.setUnit("束");
            product.setMaterial("当季鲜切花 + 进口雾面包装");
            product.setPackaging("手提礼盒装，含营养剂一小包");
            product.setWeight("约 1.2kg");
            product.setRating(BigDecimal.valueOf(5.0));
            product.setReviewCount(0);
            productRepository.save(product);
            created++;
        }
        productRepository.flush();
        if (created > 0) {
            log.info("新增商品 {} 个", created);
        }
    }

    private void initBanners() {
        String[][] seeds = {
                {"情人节特惠", "/img/banners/banner-valentine.svg", "/products?tag=情人节", "情人节鲜花限时优惠，浪漫玫瑰超值购", "1"},
                {"新品上市", "/img/banners/banner-new.svg", "/products?sort=new", "精选新品花束，引领时尚潮流", "2"},
                {"母亲节感恩", "/img/banners/banner-mother.svg", "/products?tag=母亲节", "母亲节专属花束，感恩母亲的爱", "3"},
        };
        int created = 0;
        for (String[] seed : seeds) {
            if (bannerRepository.findByTitle(seed[0]).isPresent()) {
                continue;
            }
            Banner banner = new Banner();
            banner.setTitle(seed[0]);
            banner.setImageUrl(seed[1]);
            banner.setLinkUrl(seed[2]);
            banner.setDescription(seed[3]);
            banner.setSortOrder(Integer.parseInt(seed[4]));
            banner.setStatus(Banner.STATUS_ACTIVE);
            bannerRepository.save(banner);
            created++;
        }
        if (created > 0) {
            log.info("新增轮播图 {} 张", created);
        }
    }

    private void initNotices() {
        String[][] seeds = {
                {"情人节鲜花预订开始啦！", "情人节将至，提前预订鲜花享受专属优惠！订花热线：400-888-9999", "activity", "1"},
                {"新品上架：蝴蝶兰精品系列", "新品上架，高贵典雅的蝴蝶兰系列，适合送领导、送长辈、开业贺喜", "general", "0"},
                {"配送服务调整通知", "为提升服务质量，即日起配送范围进行调整，具体请咨询客服", "system", "0"},
        };
        int created = 0;
        for (String[] seed : seeds) {
            if (noticeRepository.findByTitle(seed[0]).isPresent()) {
                continue;
            }
            Notice notice = new Notice();
            notice.setTitle(seed[0]);
            notice.setContent(seed[1]);
            notice.setNoticeType(seed[2]);
            notice.setIsTop("1".equals(seed[3]));
            notice.setViewCount(0);
            notice.setStatus(Notice.STATUS_ACTIVE);
            noticeRepository.save(notice);
            created++;
        }
        if (created > 0) {
            log.info("新增公告 {} 条", created);
        }
    }
}
