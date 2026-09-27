package org.liuym.flowerv1springboot.service;

import org.liuym.flowerv1springboot.dto.ProductDtos;
import org.liuym.flowerv1springboot.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProductService {

    Product save(Product product);

    List<Product> saveAll(List<Product> products);

    Optional<Product> findById(UUID id);

    List<Product> findAll();

    Page<Product> findByPage(Pageable pageable);

    Page<Product> findByActivePage(Pageable pageable);

    Page<Product> findByCategoryId(UUID categoryId, Pageable pageable);

    List<Product> findFeaturedProducts(int limit);

    List<Product> findNewProducts(int limit);

    List<Product> findBestSellers(UUID categoryId, int limit);

    Page<Product> searchByName(String name, Pageable pageable);

    Page<Product> findByPriceRange(BigDecimal minPrice, BigDecimal maxPrice, Pageable pageable);

    Product update(Product product);

    boolean updateActiveStatus(UUID id, Boolean isActive);

    boolean reduceStock(UUID id, Integer quantity);

    boolean increaseStock(UUID id, Integer quantity);

    boolean increaseSalesCount(UUID id, Integer count);

    boolean deleteById(UUID id);

    long count();

    long countActive();

    long countByCategory(UUID categoryId);

    /** 后台组合筛选：分类 + 上架状态 + 关键词 */
    Page<Product> searchAdmin(UUID categoryId, Boolean isActive, String keyword, Pageable pageable);

    /** 关键词检索（仅上架商品），名称/描述/标签命中即可 */
    Page<Product> searchActive(String keyword, Pageable pageable);

    /**
     * 后台新建商品：编码唯一、分类必须存在、图片协议白名单校验
     */
    Product createByForm(ProductDtos.Form form);

    /**
     * 后台编辑商品：只覆盖表单字段，销量/评分/库存等由业务写入的字段不接受前端输入
     */
    Product updateByForm(UUID id, ProductDtos.Form form);

    /** 批量上下架，返回受影响行数 */
    int changeActiveStatus(List<UUID> ids, boolean isActive);

    /**
     * 库存调整：increase 直接加，reduce 走 CAS 防负库存
     */
    Product adjustStock(UUID id, ProductDtos.StockRequest request);

    /**
     * 删除商品：已被订单引用时改为下架（归档），保证历史订单可追溯
     *
     * @return deleted=已物理删除，archived=已下架归档
     */
    String deleteOrArchive(UUID id);

    /**
     * 批量删除，逐条走 deleteOrArchive 规则
     *
     * @return deleted/archived 各自数量
     */
    Map<String, Integer> batchDeleteOrArchive(List<UUID> ids);
}