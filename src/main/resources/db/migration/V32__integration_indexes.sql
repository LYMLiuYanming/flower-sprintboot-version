-- ============================================================================
-- V32：集成期补建的两条热点索引（由各批次实测诉求汇总，不重复 V17/V21/V24 已有的）
--
-- 1) admin_audit_log(module, action, created_at desc)
--    后台用户列表要把「最近一次禁用原因+操作人」回填到每行（G17），现有 idx_audit_module
--    与 idx_audit_operator 都是单列，筛选+排序要回表扫；审计量上来后会明显变慢。
-- 2) "order"(status) WHERE ship_time IS NULL
--    部分索引：后台待发货计数与异常单筛选（G27 / C25）只关心「已付款但没发货」这一小撮，
--    全表 idx_order_status_created 会把已签收的历史单也拖进索引扫描。
--
-- 与 V24 同为索引类迁移，重复执行安全。
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_audit_module_action_created
    ON admin_audit_log (module, action, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_order_pending_ship
    ON "order" (status)
    WHERE ship_time IS NULL;
