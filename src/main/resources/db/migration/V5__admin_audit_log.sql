-- ============================================================================
-- V5：后台操作审计日志（谁在何时改了哪个模块的哪条数据、结果如何）
-- 说明：operator_id 不建外键——账号删除后留痕仍须可查；脚本幂等，兼容 Hibernate 先建表的场景。
-- ============================================================================

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id            uuid         NOT NULL,
    operator_id   uuid,
    operator_name varchar(50),
    module        varchar(30)  NOT NULL,
    action        varchar(30)  NOT NULL,
    method        varchar(10)  NOT NULL,
    uri           varchar(300) NOT NULL,
    detail        varchar(2000),
    result_code   integer,
    result_msg    varchar(200),
    ip            varchar(64),
    created_at    timestamp    NOT NULL,
    CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_audit_created   ON admin_audit_log (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operator  ON admin_audit_log (operator_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_module    ON admin_audit_log (module, created_at DESC);
