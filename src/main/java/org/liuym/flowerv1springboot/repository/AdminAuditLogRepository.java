package org.liuym.flowerv1springboot.repository;

import org.liuym.flowerv1springboot.model.AdminAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID>,
        JpaSpecificationExecutor<AdminAuditLog> {

    /**
     * G25：操作人 / 模块 / 动作 / 结果 / 时间区间的组合筛选。
     * 字符串条件一律用空串表示「不过滤」，时间用极端边界——PostgreSQL 推断不出 null 参数的类型，
     * 这是本仓库所有后台查询共用的约定（见 ProductRepository.searchAdmin）。
     * outcome 只认 ok / bad：resultCode 为空的历史记录按成功算，别把没记上结果码的旧日志判成失败
     */
    @Query("""
            SELECT a FROM AdminAuditLog a
            WHERE (:operator = '' OR LOWER(a.operatorName) LIKE LOWER(CONCAT('%', :operator, '%')))
              AND (:module = '' OR a.module = :module)
              AND (:action = '' OR a.action = :action)
              AND (:keyword = '' OR LOWER(a.uri) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(a.action) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(a.resultMsg, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
                OR LOWER(COALESCE(a.detail, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:outcome = ''
                OR (:outcome = 'ok' AND (a.resultCode IS NULL OR a.resultCode = 200))
                OR (:outcome = 'bad' AND a.resultCode IS NOT NULL AND a.resultCode <> 200))
              AND a.createdAt >= :from AND a.createdAt <= :to
            ORDER BY a.createdAt DESC""")
    Page<AdminAuditLog> searchAdvanced(@Param("operator") String operator,
                                       @Param("module") String module,
                                       @Param("action") String action,
                                       @Param("keyword") String keyword,
                                       @Param("outcome") String outcome,
                                       @Param("from") LocalDateTime from,
                                       @Param("to") LocalDateTime to,
                                       Pageable pageable);

    /** 筛选项「操作人」的下拉数据源：只有已被用过的名字才有列出来的价值 */
    @Query("SELECT DISTINCT a.operatorName FROM AdminAuditLog a WHERE a.operatorName IS NOT NULL ORDER BY 1")
    List<String> distinctOperators();

    /** 筛选项「模块」的计数：把哪个模块 busiest 直接标在筛选框上，运营才知道该往哪儿查 */
    @Query("""
            SELECT a.module, COUNT(a.id) FROM AdminAuditLog a
            WHERE a.createdAt >= :from AND a.createdAt <= :to
            GROUP BY a.module ORDER BY COUNT(a.id) DESC""")
    List<Object[]> countByModule(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** 动作清单同上，配合模块一起收敛筛选范围 */
    @Query("SELECT DISTINCT a.action FROM AdminAuditLog a WHERE a.module = :module ORDER BY 1")
    List<String> distinctActions(@Param("module") String module);
}
