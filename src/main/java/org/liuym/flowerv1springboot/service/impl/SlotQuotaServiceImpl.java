package org.liuym.flowerv1springboot.service.impl;

import org.liuym.flowerv1springboot.common.BusinessException;
import org.liuym.flowerv1springboot.common.ShippingPolicy;
import org.liuym.flowerv1springboot.model.DeliverySlotQuota;
import org.liuym.flowerv1springboot.repository.DeliverySlotQuotaRepository;
import org.liuym.flowerv1springboot.service.SlotQuotaService;
import org.liuym.flowerv1springboot.vo.ShippingViews;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 运力以「日期 + 整点」为桶，容量读配置（默认每时段 12 单）。
 *
 * <p>行是懒建的：日历只做只读查询，缺失行按满容量展示；只有真正占用时才 INSERT，
 * 避免每天无脑生成上百行空数据。
 */
@Service
public class SlotQuotaServiceImpl implements SlotQuotaService {

    private final DeliverySlotQuotaRepository quotaRepository;

    @Value("${order.slot-capacity:12}")
    private int slotCapacity;

    public SlotQuotaServiceImpl(DeliverySlotQuotaRepository quotaRepository) {
        this.quotaRepository = quotaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShippingViews.SlotView> calendar(LocalDateTime now, int days) {
        LocalDate first = now.toLocalDate();
        LocalDate last = first.plusDays(Math.max(days, 1));
        Map<String, DeliverySlotQuota> rows = new HashMap<>();
        for (DeliverySlotQuota row : quotaRepository.findBySlotDateBetweenOrderBySlotDateAscSlotHourAsc(first, last)) {
            rows.put(key(row.getSlotDate(), row.getSlotHour()), row);
        }
        LocalDateTime earliest = now.plusMinutes(ShippingPolicy.MIN_SLOT_LEAD_MINUTES);
        List<ShippingViews.SlotView> slots = new ArrayList<>();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            for (Integer hour : ShippingPolicy.slotHours()) {
                DeliverySlotQuota row = rows.get(key(date, hour));
                int capacity = row == null ? slotCapacity : row.getCapacity();
                int used = row == null ? 0 : row.getUsed();
                slots.add(ShippingViews.SlotView.of(date, hour, capacity, used,
                        date.atTime(hour, 0).isBefore(earliest)));
            }
        }
        return slots;
    }

    @Override
    @Transactional
    public void occupy(LocalDateTime slot) {
        if (slot == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        quotaRepository.ensureRow(slot.toLocalDate(), slot.getHour(), slotCapacity, now);
        if (quotaRepository.occupy(slot.toLocalDate(), slot.getHour(), now) == 0) {
            throw new BusinessException(ShippingPolicy.slotDateText(slot) + " "
                    + ShippingPolicy.slotHourText(slot.getHour()) + " 已约满，请换个时段或改选「次日达」");
        }
    }

    @Override
    @Transactional
    public void release(LocalDateTime slot) {
        if (slot != null) {
            quotaRepository.release(slot.toLocalDate(), slot.getHour(), LocalDateTime.now());
        }
    }

    private static String key(LocalDate date, int hour) {
        return date + "#" + hour;
    }
}
