package org.liuym.flowerv1springboot.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 配送试算与轨迹补记入参：金额、重量、时效全部由服务端按明细重算
 */
public final class ShippingDtos {

    private ShippingDtos() {
    }

    public record QuoteRequest(
            @Valid @NotEmpty(message = "请选择要购买的商品") List<OrderDtos.ItemRequest> items,
            /** 结算页当前选中的配送方式，仅用于回填默认值 */
            @Size(max = 20, message = "不支持的配送方式") String deliveryMethod,
            /** 预约定时达所选时段，ISO 本地时间文本 */
            @Size(max = 20, message = "预约时间格式不正确") String deliverySlot) {
    }

    /** 后台补记轨迹节点：花材到港、花艺师开始包扎等门店侧动作 */
    public record TraceRequest(
            @NotBlank(message = "请填写节点标题") @Size(max = 60, message = "节点标题不超过 60 字") String title,
            @Size(max = 255, message = "节点说明不超过 255 字") String description) {
    }
}
