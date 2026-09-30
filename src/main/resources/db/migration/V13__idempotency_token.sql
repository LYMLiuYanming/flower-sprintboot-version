-- ============================================================================
-- V13：下单幂等凭证
--   idempotency_token.token        一次性凭证（服务端签发，前端只透传）
--   idempotency_token.user_id      绑定下单人，防止拿别人的凭证下单
--   idempotency_token.claimed_at   被某次提交抢占的时刻（与建单同事务，回滚即释放）
--   idempotency_token.order_id     凭证最终生成的订单，重放时按它回吐首次结果
-- 说明：不依赖 Redis，抢占走条件 UPDATE，行锁天然挡住双击与网络重试的重复下单。
-- ============================================================================

CREATE TABLE IF NOT EXISTS idempotency_token (
    id         uuid          NOT NULL,
    token      varchar(64)   NOT NULL,
    user_id    uuid          NOT NULL,
    order_id   uuid,
    claimed_at timestamp,
    created_at timestamp     NOT NULL,
    CONSTRAINT idempotency_token_pkey PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_token UNIQUE (token),
    CONSTRAINT fk_idempotency_token_user FOREIGN KEY (user_id) REFERENCES "user" (id)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_token_created ON idempotency_token (created_at);
