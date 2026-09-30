-- ============================================================================
-- V12：预约时段运力
--   delivery_slot_quota  一行 = 某天某小时可接的预约单量，used 由条件 UPDATE 占用/释放，
--                        两个客户抢最后一个名额时数据库层保证只有一人成功。
--   行按需懒建（首次占用或后台改容量时写入），因此不预生成日历数据。
-- ============================================================================

CREATE TABLE IF NOT EXISTS delivery_slot_quota (
    id         uuid      NOT NULL,
    slot_date  date      NOT NULL,
    slot_hour  smallint  NOT NULL,
    capacity   integer   NOT NULL DEFAULT 0,
    used       integer   NOT NULL DEFAULT 0,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    CONSTRAINT delivery_slot_quota_pkey PRIMARY KEY (id),
    CONSTRAINT uq_delivery_slot UNIQUE (slot_date, slot_hour),
    CONSTRAINT ck_delivery_slot_hour CHECK (slot_hour BETWEEN 0 AND 23),
    CONSTRAINT ck_delivery_slot_used CHECK (used >= 0)
);
