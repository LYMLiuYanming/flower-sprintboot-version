package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.DeliverySlotQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DeliverySlotQuotaRepository extends JpaRepository<DeliverySlotQuota, java.util.UUID> {

    List<DeliverySlotQuota> findBySlotDateBetweenOrderBySlotDateAscSlotHourAsc(LocalDate from, LocalDate to);

    /** 行不存在时按默认容量补一行，已存在则原样保留（并发下靠唯一约束兜底） */
    @Modifying
    @Query(value = """
            INSERT INTO delivery_slot_quota (id, slot_date, slot_hour, capacity, used, created_at, updated_at)
            VALUES (gen_random_uuid(), :slotDate, :slotHour, :capacity, 0, :now, :now)
            ON CONFLICT (slot_date, slot_hour) DO NOTHING
            """, nativeQuery = true)
    int ensureRow(@Param("slotDate") LocalDate slotDate, @Param("slotHour") int slotHour,
                  @Param("capacity") int capacity, @Param("now") LocalDateTime now);

    /**
     * 占用一个名额：只有「已用 &lt; 容量」才加得动，返回 0 表示该时段已约满
     */
    @Modifying
    @Query("""
            UPDATE DeliverySlotQuota q SET q.used = q.used + 1, q.updatedAt = :now
            WHERE q.slotDate = :slotDate AND q.slotHour = :slotHour AND q.used < q.capacity
            """)
    int occupy(@Param("slotDate") LocalDate slotDate, @Param("slotHour") int slotHour,
               @Param("now") LocalDateTime now);

    /** 取消/退款释放名额，used 归零后不再往下减 */
    @Modifying
    @Query("""
            UPDATE DeliverySlotQuota q SET q.used = q.used - 1, q.updatedAt = :now
            WHERE q.slotDate = :slotDate AND q.slotHour = :slotHour AND q.used > 0
            """)
    int release(@Param("slotDate") LocalDate slotDate, @Param("slotHour") int slotHour,
                @Param("now") LocalDateTime now);
}
