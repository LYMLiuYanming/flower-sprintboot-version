-- ============================================================================
-- V20：用户与账户批次（D01–D20）结构收敛
--   1) D16 注销：user 增加 deletion_requested_at 软删除冷静期时间戳；status 复用既有列，
--      新增终态取值 'deleted'（应用层约束，不建 CHECK 以免影响历史数据与并行批次）。
--   2) D18 登录设备：新建 user_session 台账表，记录每次登录的 IP/UA/记住我标记与撤销位。
--   3) 检索索引：设备列表按「用户 + 未撤销 + 最近活动」取数，注销扫描按申请时间过滤。
--   迁移只做加法（ADD COLUMN IF NOT EXISTS / CREATE TABLE IF NOT EXISTS），可安全重放。
-- ============================================================================

-- 1. 注销冷静期时间戳（软删除锚点，绝不物理删行）
ALTER TABLE "user"
    ADD COLUMN IF NOT EXISTS deletion_requested_at timestamp;

COMMENT ON COLUMN "user".deletion_requested_at IS '注销申请时间；非空表示处于冷静期，期满由定时任务匿名化（D16）';

-- 注销扫描：只捞已申请且已过冷静期、尚未落终态的账号
CREATE INDEX IF NOT EXISTS idx_user_deletion_pending
    ON "user" (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL;

-- 2. 登录设备会话台账（D18）
CREATE TABLE IF NOT EXISTS user_session (
    token            uuid        NOT NULL,
    user_id          uuid        NOT NULL,
    client_ip        varchar(64),
    user_agent       varchar(255),
    http_session_id  varchar(80),
    remember_me      boolean     NOT NULL DEFAULT false,
    revoked          boolean     NOT NULL DEFAULT false,
    created_at       timestamp   NOT NULL DEFAULT now(),
    last_access_time timestamp   NOT NULL DEFAULT now(),
    CONSTRAINT user_session_pkey PRIMARY KEY (token),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES "user" (id) ON DELETE CASCADE
);

-- 设备列表：按用户取未撤销会话并按最近活动倒序
CREATE INDEX IF NOT EXISTS idx_user_session_user
    ON user_session (user_id, revoked, last_access_time DESC);
