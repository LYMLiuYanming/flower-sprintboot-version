package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.SafeUrl;
import org.liuym.flowerv1springboot.common.SearchTokenizer;
import org.liuym.flowerv1springboot.dto.ProductDtos;
import org.liuym.flowerv1springboot.model.Category;
import org.liuym.flowerv1springboot.model.Product;
import org.liuym.flowerv1springboot.repository.CategoryRepository;
import org.liuym.flowerv1springboot.repository.FavoriteRepository;
import org.liuym.flowerv1springboot.repository.OrderItemRepository;
import org.liuym.flowerv1springboot.repository.ProductRepository;
import org.liuym.flowerv1springboot.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class ProductServiceImpl implements ProductService {

    /** LIKE 转义符：与 escapeLike 中的处理配套，防止用户输入 % / _ 变成通配 */
    private static final char LIKE_ESCAPE = '\\';

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Override
    public Product save(Product product) {
        if (product.getStock() == null) {
            product.setStock(0);
        }
        if (product.getSalesCount() == null) {
            product.setSalesCount(0);
        }
        if (product.getReviewCount() == null) {
            product.setReviewCount(0);
        }
        if (product.getIsActive() == null) {
            product.setIsActive(true);
        }
        if (product.getIsFeatured() == null) {
            product.setIsFeatured(false);
        }
        if (product.getIsNew() == null) {
            product.setIsNew(false);
        }
        if (product.getUnit() == null) {
            product.setUnit("束");
        }
        return productRepository.save(product);
    }

    @Override
    public List<Product> saveAll(List<Product> products) {
        return productRepository.saveAll(products);
    }

    @Override
    public Optional<Product> findById(UUID id) {
        return productRepository.findById(id);
    }

    @Override
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    @Override
    public Page<Product> findByPage(Pageable pageable) {
        return productRepository.findAll(pageable);
    }

    @Override
    public Page<Product> findByActivePage(Pageable pageable) {
        return productRepository.findByIsActiveTrue(pageable);
    }

    @Override
    public Page<Product> findByCategoryId(UUID categoryId, Pageable pageable) {
        return productRepository.findByCategoryIdAndIsActiveTrue(categoryId, pageable);
    }

    @Override
    public List<Product> findFeaturedProducts(int limit) {
        return productRepository.findByIsFeaturedTrueAndIsActiveTrue(PageRequest.of(0, limit));
    }

    @Override
    public List<Product> findNewProducts(int limit) {
        return productRepository.findByIsNewTrueAndIsActiveTrue(PageRequest.of(0, limit));
    }

    @Override
    public List<Product> findBestSellers(UUID categoryId, int limit) {
        return productRepository.findBestSellers(categoryId, PageRequest.of(0, limit));
    }

    @Override
    public Page<Product> searchByName(String name, Pageable pageable) {
        return productRepository.findByNameContainingAndIsActiveTrue(name, pageable);
    }

    @Override
    public Page<Product> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable) {
        return productRepository.findByPriceRange(minPrice, maxPrice, pageable);
    }

    @Override
    public Product update(Product product) {
        if (!productRepository.existsById(product.getId())) {
            throw new RuntimeException("商品不存在，更新失败！商品ID：" + product.getId());
        }
        return productRepository.save(product);
    }

    @Override
    public boolean updateActiveStatus(UUID id, Boolean isActive) {
        int affectedRows = productRepository.updateActiveStatus(id, isActive);
        return affectedRows > 0;
    }

    @Override
    public boolean reduceStock(UUID id, Integer quantity) {
        int affectedRows = productRepository.reduceStock(id, quantity);
        return affectedRows > 0;
    }

    @Override
    public boolean increaseStock(UUID id, Integer quantity) {
        int affectedRows = productRepository.increaseStock(id, quantity);
        return affectedRows > 0;
    }

    @Override
    public boolean increaseSalesCount(UUID id, Integer count) {
        int affectedRows = productRepository.increaseSalesCount(id, count);
        return affectedRows > 0;
    }

    @Override
    @Transactional
    public boolean deleteById(UUID id) {
        if (productRepository.existsById(id)) {
            productRepository.deleteById(id);
            return true;
        }
        return false;
    }

    @Override
    public long count() {
        return productRepository.count();
    }

    @Override
    public long countActive() {
        return productRepository.countByIsActiveTrue();
    }

    @Override
    public long countByCategory(UUID categoryId) {
        return productRepository.countByCategoryId(categoryId);
    }

    @Override
    public Page<Product> searchAdmin(UUID categoryId, Boolean isActive, String keyword, Pageable pageable) {
        // 空关键词以空串下传：LIKE 参数为 null 时 PostgreSQL 推断不出类型
        String kw = keyword == null ? "" : keyword.trim();
        return productRepository.searchAdmin(categoryId, isActive, kw, pageable);
    }

    @Override
    public Page<Product> searchActive(String keyword, Pageable pageable) {
        List<String> terms = SearchTokenizer.terms(keyword);
        if (terms.isEmpty()) {
            return productRepository.findByIsActiveTrue(pageable);
        }
        Page<Product> matched = productRepository.findAll(activeMatching(terms, true), pageable);
        // 逐词 AND 命中为空时放宽为「任一词命中」，多词查询不至于直接给空结果
        if (matched.hasContent() || terms.size() == 1) {
            return matched;
        }
        return productRepository.findAll(activeMatching(terms, false), pageable);
    }

    /** 一个词命中名称/描述/标签/分类名任一即算匹配；多词按 requireAll 决定 AND 还是 OR */
    private Specification<Product> activeMatching(List<String> terms, boolean requireAll) {
        return (root, query, cb) -> {
            Join<Product, Category> category = root.join("category", JoinType.LEFT);
            List<Predicate> hits = terms.stream().map(term -> termHit(root, category, cb, term)).toList();
            Predicate[] array = hits.toArray(new Predicate[0]);
            return cb.and(cb.isTrue(root.get("isActive")), requireAll ? cb.and(array) : cb.or(array));
        };
    }

    private Predicate termHit(Root<Product> root, Join<Product, Category> category, CriteriaBuilder cb, String term) {
        String like = "%" + escapeLike(term) + "%";
        return cb.or(
                cb.like(cb.lower(root.get("name")), like, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("description")), like, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("tags")), like, LIKE_ESCAPE),
                cb.like(cb.lower(category.get("name")), like, LIKE_ESCAPE));
    }

    /** 用户输入的 LIKE 通配符必须转义，否则「%」可以当全匹配用 */
    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public Product createByForm(ProductDtos.Form form) {
        Product product = new Product();
        applyForm(product, form);
        product.setSalesCount(0);
        product.setReviewCount(0);
        if (product.getRating() == null) {
            product.setRating(BigDecimal.valueOf(5.0));
        }
        return productRepository.save(product);
    }

    @Override
    public Product updateByForm(UUID id, ProductDtos.Form form) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        applyForm(product, form);
        return productRepository.save(product);
    }

    /**
     * 表单→实体：只有运营可维护的字段能覆盖，stock 仅在明确传入时改动，
     * salesCount / rating / reviewCount 由业务写入，杜绝后台表单伪造销量
     */
    private void applyForm(Product product, ProductDtos.Form form) {
        if (form.categoryId() == null) {
            throw new BusinessException("请选择商品分类");
        }
        Category category = categoryRepository.findById(form.categoryId())
                .orElseThrow(() -> new BusinessException("所选分类不存在"));
        product.setCategory(category);

        if (form.code() != null && !form.code().isBlank()) {
            String code = form.code().trim();
            productRepository.findByCode(code).ifPresent(exist -> {
                if (!exist.getId().equals(product.getId())) {
                    throw new BusinessException("商品编码「" + code + "」已存在");
                }
            });
            product.setCode(code);
        } else {
            product.setCode(null);
        }

        product.setName(form.name().trim());
        product.setDescription(form.description());
        product.setPrice(form.price());
        product.setOriginalPrice(form.originalPrice());
        product.setMainImage(SafeUrl.requireSafe(form.mainImage(), "主图地址"));
        product.setImages(SafeUrl.requireSafeList(form.images(), "图集地址"));
        product.setTags(form.tags());
        product.setUnit(blankTo(form.unit(), "束"));
        product.setWeight(form.weight());
        product.setMaterial(form.material());
        product.setPackaging(form.packaging());
        product.setIsActive(form.isActive());
        product.setIsFeatured(Boolean.TRUE.equals(form.isFeatured()));
        product.setIsNew(Boolean.TRUE.equals(form.isNew()));
        if (form.stock() != null) {
            product.setStock(form.stock());
        }
        if (product.getSalesCount() == null) {
            product.setSalesCount(0);
        }
        if (product.getReviewCount() == null) {
            product.setReviewCount(0);
        }
    }

    private String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public int changeActiveStatus(List<UUID> ids, boolean isActive) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        return productRepository.updateActiveStatusBatch(ids, isActive);
    }

    @Override
    public Product adjustStock(UUID id, ProductDtos.StockRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        int quantity = request.quantity();
        int affected = "reduce".equals(request.type())
                ? productRepository.reduceStock(id, quantity)
                : productRepository.increaseStock(id, quantity);
        if (affected == 0) {
            throw new BusinessException("库存不足，当前库存 " + product.getStock() + " 件");
        }
        product.setStock(product.getStock() + ("reduce".equals(request.type()) ? -quantity : quantity));
        return product;
    }

    @Override
    public String deleteOrArchive(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("商品不存在"));
        if (orderItemRepository.existsByProduct_Id(id)) {
            productRepository.updateActiveStatus(id, false);
            return "archived";
        }
        favoriteRepository.deleteByProductId(id);
        productRepository.delete(product);
        return "deleted";
    }

    @Override
    public Map<String, Integer> batchDeleteOrArchive(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException("请至少选择一条记录");
        }
        int deleted = 0;
        int archived = 0;
        for (UUID id : ids) {
            if (!productRepository.existsById(id)) {
                continue;
            }
            if ("archived".equals(deleteOrArchive(id))) {
                archived++;
            } else {
                deleted++;
            }
        }
        return Map.of("deleted", deleted, "archived", archived);
    }
}