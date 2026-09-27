package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 后台商品/分类写接口入参：切断实体直接入库路径，图片与链接字段做协议白名单校验
 */
public class ProductDtos {

    public record Form(
            @Size(max = 50, message = "商品编码不超过 50 字") String code,
            @NotBlank(message = "请填写商品名称") @Size(max = 100) String name,
            @Size(max = 2000, message = "商品描述过长") String description,
            @NotNull(message = "请填写售价") @DecimalMin(value = "0.01", message = "售价必须大于 0") BigDecimal price,
            @DecimalMin(value = "0", message = "划线价不能为负") BigDecimal originalPrice,
            @Size(max = 500) String mainImage,
            @Size(max = 4000) String images,
            UUID categoryId,
            @NotNull(message = "请填写库存") @Min(value = 0, message = "库存不能为负") @Max(value = 999999) Integer stock,
            @NotNull(message = "请选择上架状态") Boolean isActive,
            Boolean isFeatured,
            Boolean isNew,
            @Size(max = 20) String unit,
            @Size(max = 50) String weight,
            @Size(max = 100) String material,
            @Size(max = 100) String packaging,
            @Size(max = 200) String tags) {
    }

    public record StockRequest(
            @NotNull @Min(value = 1, message = "调整数量至少为 1") Integer quantity,
            @Pattern(regexp = "^(increase|reduce)$", message = "库存调整类型不合法") String type,
            @Size(max = 200) String remark) {
    }

    public record BatchIdsRequest(
            @NotEmpty(message = "请至少选择一条记录") List<UUID> ids) {
    }

    public record BatchStatusRequest(
            @NotEmpty(message = "请至少选择一条记录") List<UUID> ids,
            @NotNull(message = "请指定上架状态") Boolean isActive) {
    }
}
