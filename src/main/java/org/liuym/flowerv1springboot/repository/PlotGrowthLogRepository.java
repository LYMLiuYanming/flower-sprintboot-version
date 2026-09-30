package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.PlotGrowthLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PlotGrowthLogRepository extends JpaRepository<PlotGrowthLog, UUID> {

    /** 曲线取最近 N 行后由服务层反序成时间正序；块地之间互不串线，所以按 plotId 查 */
    List<PlotGrowthLog> findTop200ByPlotIdOrderByCreatedAtDesc(UUID plotId);

    @Query("SELECT l FROM PlotGrowthLog l WHERE l.userId = :userId AND l.slotNo = :slot ORDER BY l.createdAt ASC")
    List<PlotGrowthLog> findSeries(@Param("userId") UUID userId, @Param("slot") int slot);

    long countByPlotId(UUID plotId);
}
