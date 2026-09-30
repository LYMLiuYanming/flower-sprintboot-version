-- ============================================================================
-- V15：我的花田（天气联动的养花养成）
--   flower_plot        一人同时只有一块地在培育（部分唯一索引兜住，重复开坑只会有一条 growing）
--                      growth 0-100 由浇水与当日天气累加，mature 后兑换优惠券或转赠好友
--   coupon(GARDEN-*)   四种花苗对应的成熟奖励券，服务端按 code 发放，不开放到领券中心
-- ============================================================================

CREATE TABLE IF NOT EXISTS flower_plot (
    id                  uuid      NOT NULL,
    user_id             uuid      NOT NULL,
    seed_code           varchar(30) NOT NULL,
    origin_id           uuid,
    growth              integer   NOT NULL DEFAULT 0,
    stage               varchar(16) NOT NULL DEFAULT 'seed',
    status              varchar(16) NOT NULL DEFAULT 'growing',
    water_times         integer   NOT NULL DEFAULT 0,
    water_times_today   integer   NOT NULL DEFAULT 0,
    watered_on          date,
    streak_days         integer   NOT NULL DEFAULT 0,
    mature_on           timestamp,
    redeemed_coupon_id  uuid,
    gift_user_id        uuid,
    gift_message        varchar(200),
    created_at          timestamp NOT NULL,
    updated_at          timestamp NOT NULL,
    CONSTRAINT flower_plot_pkey PRIMARY KEY (id),
    CONSTRAINT fk_flower_plot_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT fk_flower_plot_origin FOREIGN KEY (origin_id) REFERENCES flower_origin (id),
    CONSTRAINT ck_flower_plot_status CHECK (status IN ('growing', 'mature', 'redeemed', 'gifted')),
    CONSTRAINT ck_flower_plot_growth CHECK (growth >= 0)
);

-- 一人一块地：兑换或转赠后 growing 结束，才能再开新坑
CREATE UNIQUE INDEX IF NOT EXISTS uq_flower_plot_growing ON flower_plot (user_id) WHERE status = 'growing';
CREATE INDEX IF NOT EXISTS idx_flower_plot_user ON flower_plot (user_id, status);

INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, start_time, end_time, valid_days, valid_end_time, scope, category_id, status,
                    created_at, updated_at)
VALUES
    ('d0000000-0000-0000-0000-000000000001', 'GARDEN-ROSE', '花田养成礼 · 玫瑰专享', 'cash', 99.00, 20.00, NULL, NULL,
     5000, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now()),
    ('d0000000-0000-0000-0000-000000000002', 'GARDEN-TULIP', '花田养成礼 · 郁金香专享', 'cash', 129.00, 25.00, NULL, NULL,
     5000, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now()),
    ('d0000000-0000-0000-0000-000000000003', 'GARDEN-HYDRA', '花田养成礼 · 绣球专享', 'cash', 159.00, 30.00, NULL, NULL,
     5000, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now()),
    ('d0000000-0000-0000-0000-000000000004', 'GARDEN-SUN', '花田养成礼 · 向日葵专享', 'cash', 79.00, 15.00, NULL, NULL,
     5000, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now())
ON CONFLICT (id) DO NOTHING;
