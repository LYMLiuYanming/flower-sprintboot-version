-- ============================================================================
-- V7：优惠券 / 满减活动
--   coupon        券模板（后台维护：满减或折扣、门槛、有效期、发行量、领取限制、适用范畴）
--   user_coupon   用户持券：领取/直发时把模板条款快照进来，之后改模板不影响已发券
-- 说明：金额相关列统一 numeric(10,2)，与 product.price / order.total_amount 保持一致。
-- ============================================================================

CREATE TABLE IF NOT EXISTS coupon (
    id             uuid          NOT NULL,
    code           varchar(30)   NOT NULL,
    name           varchar(50)   NOT NULL,
    type           varchar(20)   NOT NULL,
    threshold      numeric(10,2) NOT NULL DEFAULT 0,
    amount         numeric(10,2),
    discount_rate  numeric(4,2),
    max_discount   numeric(10,2),
    total          integer       NOT NULL DEFAULT 0,
    issued         integer       NOT NULL DEFAULT 0,
    per_user_limit integer       NOT NULL DEFAULT 1,
    start_time     timestamp,
    end_time       timestamp,
    valid_days     integer       NOT NULL DEFAULT 0,
    valid_end_time timestamp,
    scope          varchar(20)   NOT NULL DEFAULT 'all',
    category_id    uuid,
    status         varchar(20)   NOT NULL DEFAULT 'active',
    created_at     timestamp     NOT NULL,
    updated_at     timestamp     NOT NULL,
    CONSTRAINT coupon_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_coupon_code ON coupon (code);
CREATE INDEX IF NOT EXISTS idx_coupon_status ON coupon (status, start_time, end_time);

CREATE TABLE IF NOT EXISTS user_coupon (
    id            uuid          NOT NULL,
    user_id       uuid          NOT NULL,
    coupon_id     uuid          NOT NULL,
    code          varchar(30)   NOT NULL,
    name          varchar(50)   NOT NULL,
    type          varchar(20)   NOT NULL,
    threshold     numeric(10,2) NOT NULL DEFAULT 0,
    amount        numeric(10,2),
    discount_rate numeric(4,2),
    max_discount  numeric(10,2),
    scope         varchar(20)   NOT NULL DEFAULT 'all',
    category_id   uuid,
    status        varchar(20)   NOT NULL DEFAULT 'unused',
    expire_at     timestamp     NOT NULL,
    order_id      uuid,
    received_at   timestamp     NOT NULL,
    used_at       timestamp,
    CONSTRAINT user_coupon_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_user_coupon_user   ON user_coupon (user_id, status, expire_at);
CREATE INDEX IF NOT EXISTS idx_user_coupon_coupon ON user_coupon (coupon_id, user_id);
CREATE INDEX IF NOT EXISTS idx_user_coupon_order  ON user_coupon (order_id);
