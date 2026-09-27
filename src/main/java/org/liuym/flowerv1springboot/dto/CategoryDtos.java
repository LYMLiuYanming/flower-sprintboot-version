package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.*;

import java.util.UUID;

public class CategoryDtos {

    public record Form(
            @NotBlank(message = "请填写分类名称") @Size(max = 50) String name,
            @Size(max = 200, message = "描述不超过 200 字") String description,
            @Size(max = 100) String icon,
            @NotNull(message = "请填写排序值") @Min(0) @Max(9999) Integer sortOrder,
            UUID parentId,
            @NotNull(message = "请选择启用状态") Boolean isActive) {
    }
}
