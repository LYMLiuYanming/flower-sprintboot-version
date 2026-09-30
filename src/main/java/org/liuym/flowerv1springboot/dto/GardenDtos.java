package org.liuym.flowerv1springboot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 花田入参：只有种子码、地块序号与收花人用户名是必要的，成长值一律由服务端算，不接受前端上报
 */
public final class GardenDtos {

    private GardenDtos() {
    }

    /**
     * @param slotNo 地块序号（I11），默认第 1 块；第 2 块只有会员能开
     */
    public record Plant(@NotBlank(message = "请选择一种花苗") String seedCode,
                        @Min(value = 1, message = "地块编号从 1 开始")
                        @Max(value = 4, message = "地块编号超出范围")
                        Integer slotNo) {

        public int slot() {
            return slotNo == null ? 1 : slotNo;
        }
    }

    /**
     * @param slotNo 要浇哪块地；不传时服务端按「唯一在培育的那块」处理，兼容只有一个地块的老前端
     */
    public record Water(Integer slotNo) {
        public int slotOrDefault() {
            return slotNo == null || slotNo < 1 ? 1 : slotNo;
        }
    }

    public record Gift(@NotBlank(message = "请填写要送给谁") String username,
                       @Size(max = 200, message = "寄语不超过 200 字") String message,
                       Integer slotNo) {

        public int slotOrDefault() {
            return slotNo == null || slotNo < 1 ? 1 : slotNo;
        }
    }

    /** 转赠确认（I13）：按流水 id 定位，避免同一账号多笔待确认时确认错对象 */
    public record GiftDecision(@jakarta.validation.constraints.NotNull(message = "缺少要处理的花") UUID exchangeId) {
    }

    /** 成熟兑换（I11 多块地）：不传按唯一成熟的地处理 */
    public record Redeem(Integer slotNo) {
        public int slotOrDefault() {
            return slotNo == null || slotNo < 1 ? 1 : slotNo;
        }
    }
}
