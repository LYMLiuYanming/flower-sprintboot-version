-- ============================================================================
-- V21：优惠券与营销（E01–E20）
--   coupon          补阶梯满减、多品类、商品白名单、每日限领、新客专享、转赠、停用策略、场景触发
--   user_coupon     同步快照上述条款；补转赠列与发放来源（source_ref 用于返券/邀请幂等）
--   full_reduction  满减活动（无需领券），与券并存时按 stack_with_coupon / priority 定优先级
--   invite_code     一人一码（有效码唯一，撤销后可重新生成）
--   invite_relation 邀请关系：一个被邀请人只能被绑定一次，首单奖励据此发放
--   promotion_slot  促销位（首页/详情页领券条）后台开关与排序
-- 说明：券条款一律在领取时快照到 user_coupon，后台改模板不回溯已发出的券。
-- ============================================================================

-- ---------------------------------------------------------------- coupon 模板
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS ladder_rule          varchar(200);
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS category_ids         varchar(500);
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS product_ids          varchar(1000);
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS per_user_daily_limit integer       NOT NULL DEFAULT 0;
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS new_user_only        boolean       NOT NULL DEFAULT false;
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS allow_transfer       boolean       NOT NULL DEFAULT false;
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS member_only          boolean       NOT NULL DEFAULT false;
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS disable_policy       varchar(10)   NOT NULL DEFAULT 'keep';
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS trigger_scene        varchar(20)   NOT NULL DEFAULT 'claim';
ALTER TABLE coupon ADD COLUMN IF NOT EXISTS grant_min_amount     numeric(10,2) NOT NULL DEFAULT 0;

COMMENT ON COLUMN coupon.ladder_rule IS '满减阶梯 "199:20,399:60"，非空时优先于 threshold/amount';
COMMENT ON COLUMN coupon.per_user_daily_limit IS '每人每日可领张数，0 表示不按天限制';
COMMENT ON COLUMN coupon.disable_policy IS '下架后已领券的处理：keep 保留可用 / void 立即作废';
COMMENT ON COLUMN coupon.trigger_scene IS '发放场景：claim 领券中心 / after_pay 下单返券 / invite 邀请奖励 / admin 仅后台直发';

-- 券码唯一性改用条件唯一索引：同一时刻只允许一张「启用中」的同码券。
-- V7 的全表唯一索引过严，会让 E11 复制新建的历史副本占住券码、下架后无法再启用同码活动；
-- 撤掉全表索引后由服务层查重（countByCode）保证副本另起新码，findByCode 也改成优先取启用中的那张。
DROP INDEX IF EXISTS uk_coupon_code;
CREATE UNIQUE INDEX IF NOT EXISTS uq_coupon_code_active ON coupon (code) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS idx_coupon_scene ON coupon (trigger_scene, status, grant_min_amount);

-- ------------------------------------------------------------- user_coupon
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS ladder_rule      varchar(200);
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS category_ids     varchar(500);
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS product_ids      varchar(1000);
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS allow_transfer   boolean NOT NULL DEFAULT false;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS daily_limited    boolean NOT NULL DEFAULT false;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS claim_date       date;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS source           varchar(20) NOT NULL DEFAULT 'claim';
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS source_ref       uuid;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS transfer_token   varchar(40);
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS transfer_to_user_id uuid;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS transfer_from_user_id uuid;
ALTER TABLE user_coupon ADD COLUMN IF NOT EXISTS transfer_created_at   timestamp;

COMMENT ON COLUMN user_coupon.source_ref IS '来源单据 id（下单返券=订单 id，邀请奖励=关系 id），配合唯一索引保证同一笔业务只发一张';

-- 同一张模板在同一笔来源单据上只能落一张券：下单返券与邀请奖励的重复调用被数据库挡住
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_coupon_source ON user_coupon (coupon_id, user_id, source_ref)
    WHERE source_ref IS NOT NULL;
-- 每人每日限领一次：只有按天限制的模板进入该索引
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_coupon_daily_claim ON user_coupon (coupon_id, user_id, claim_date)
    WHERE daily_limited = true;
-- 转赠凭证唯一，避免两张券共用一个领取码
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_coupon_transfer_token ON user_coupon (transfer_token)
    WHERE transfer_token IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_user_coupon_transfer_inbox ON user_coupon (transfer_to_user_id, status)
    WHERE transfer_to_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_user_coupon_source ON user_coupon (source, coupon_id);

-- ------------------------------------------------------------ 满减活动（E14）
CREATE TABLE IF NOT EXISTS full_reduction (
    id                  uuid          NOT NULL,
    name                varchar(60)   NOT NULL,
    scope               varchar(20)   NOT NULL DEFAULT 'all',
    category_ids        varchar(500),
    ladder_rule         varchar(200)  NOT NULL,
    stack_with_coupon   boolean       NOT NULL DEFAULT false,
    priority            integer       NOT NULL DEFAULT 0,
    start_time          timestamp,
    end_time            timestamp,
    status              varchar(20)   NOT NULL DEFAULT 'active',
    created_at          timestamp     NOT NULL,
    updated_at          timestamp     NOT NULL,
    CONSTRAINT full_reduction_pkey PRIMARY KEY (id),
    CONSTRAINT ck_full_reduction_scope CHECK (scope IN ('all', 'category')),
    CONSTRAINT ck_full_reduction_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX IF NOT EXISTS idx_full_reduction_window ON full_reduction (status, start_time, end_time);

-- ------------------------------------------------------------ 邀请有礼（E16）
CREATE TABLE IF NOT EXISTS invite_code (
    id                 uuid      NOT NULL,
    user_id            uuid      NOT NULL,
    code               varchar(16) NOT NULL,
    invitee_coupon_id  uuid,
    inviter_coupon_id  uuid,
    reward_points      integer   NOT NULL DEFAULT 0,
    invited_count      integer   NOT NULL DEFAULT 0,
    rewarded_count     integer   NOT NULL DEFAULT 0,
    revoked_at         timestamp,
    created_at         timestamp NOT NULL,
    updated_at         timestamp NOT NULL,
    CONSTRAINT invite_code_pkey PRIMARY KEY (id),
    CONSTRAINT fk_invite_code_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT fk_invite_code_invitee_coupon FOREIGN KEY (invitee_coupon_id) REFERENCES coupon (id),
    CONSTRAINT fk_invite_code_inviter_coupon FOREIGN KEY (inviter_coupon_id) REFERENCES coupon (id)
);

-- 一人同时只有一张有效邀请码；撤销后可重新生成（条件唯一索引，只对未撤销的行生效）
CREATE UNIQUE INDEX IF NOT EXISTS uq_invite_code_user ON invite_code (user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_invite_code_value ON invite_code (code) WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS invite_relation (
    id                  uuid      NOT NULL,
    invite_code_id      uuid      NOT NULL,
    inviter_id          uuid      NOT NULL,
    invitee_id          uuid      NOT NULL,
    first_order_id      uuid,
    reward_status       varchar(16) NOT NULL DEFAULT 'pending',
    rewarded_at         timestamp,
    created_at          timestamp NOT NULL,
    updated_at          timestamp NOT NULL,
    CONSTRAINT invite_relation_pkey PRIMARY KEY (id),
    CONSTRAINT fk_invite_relation_code FOREIGN KEY (invite_code_id) REFERENCES invite_code (id),
    CONSTRAINT fk_invite_relation_inviter FOREIGN KEY (inviter_id) REFERENCES "user" (id),
    CONSTRAINT fk_invite_relation_invitee FOREIGN KEY (invitee_id) REFERENCES "user" (id),
    CONSTRAINT ck_invite_relation_status CHECK (reward_status IN ('pending', 'rewarded', 'skipped'))
);

-- 一个被邀请人只能被绑定一次，防止刷邀请
CREATE UNIQUE INDEX IF NOT EXISTS uq_invite_relation_invitee ON invite_relation (invitee_id);
CREATE INDEX IF NOT EXISTS idx_invite_relation_inviter ON invite_relation (inviter_id, reward_status);

-- ------------------------------------------------------------ 促销位（E19）
CREATE TABLE IF NOT EXISTS promotion_slot (
    id           uuid         NOT NULL,
    position     varchar(20)  NOT NULL,
    name         varchar(60)  NOT NULL,
    coupon_id    uuid,
    title        varchar(80)  NOT NULL,
    subtitle     varchar(160),
    link_url     varchar(300),
    image_url    varchar(300),
    sort_order   integer      NOT NULL DEFAULT 0,
    status       varchar(20)  NOT NULL DEFAULT 'active',
    start_time   timestamp,
    end_time     timestamp,
    created_at   timestamp    NOT NULL,
    updated_at   timestamp    NOT NULL,
    CONSTRAINT promotion_slot_pkey PRIMARY KEY (id),
    CONSTRAINT fk_promotion_slot_coupon FOREIGN KEY (coupon_id) REFERENCES coupon (id),
    CONSTRAINT ck_promotion_slot_status CHECK (status IN ('active', 'inactive'))
);

CREATE INDEX IF NOT EXISTS idx_promotion_slot_list ON promotion_slot (position, status, sort_order);

-- ------------------------------------------------------- 会员资格（E17/E18）
-- 消费自动升级的会员只落在 user.member_level（由下单链路维护），长期有效；
-- 积分开通/续费的会员落这张表并带到期时间。表里没有未过期行 + member_level='vip'
-- 即「消费达标的长期会员」，两套来源不会互相覆盖。
CREATE TABLE IF NOT EXISTS vip_membership (
    id          uuid         NOT NULL,
    user_id     uuid         NOT NULL,
    level       varchar(20)  NOT NULL DEFAULT 'vip',
    source      varchar(20)  NOT NULL DEFAULT 'points',
    status      varchar(16)  NOT NULL DEFAULT 'active',
    start_at    timestamp    NOT NULL,
    expire_at   timestamp,
    renew_count integer      NOT NULL DEFAULT 0,
    points_cost integer      NOT NULL DEFAULT 0,
    created_at  timestamp    NOT NULL,
    updated_at  timestamp    NOT NULL,
    CONSTRAINT vip_membership_pkey PRIMARY KEY (id),
    CONSTRAINT fk_vip_membership_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT ck_vip_membership_status CHECK (status IN ('active', 'expired'))
);

-- 一人一条会员记录：并发开通时被唯一索引挡住，重复点击只会多扣一次积分也不会多出一条记录
CREATE UNIQUE INDEX IF NOT EXISTS uq_vip_membership_user ON vip_membership (user_id);
CREATE INDEX IF NOT EXISTS idx_vip_membership_expire ON vip_membership (status, expire_at);

-- ============================================================================
-- 种子数据
-- ============================================================================

INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, start_time, end_time, valid_days, valid_end_time, scope, category_id, status,
                    created_at, updated_at,
                    ladder_rule, per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount)
VALUES
    -- E01 满减阶梯：自动取最优档
    ('e1000000-0000-0000-0000-000000000001', 'LADDER-199', '满额阶梯礼 · 越买越省', 'cash', 199.00, 20.00, NULL, NULL,
     2000, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now(),
     '199:20,399:60,599:100', 0, false, false, false, 'keep', 'claim', 0),
    -- E05 新客专享
    ('e1000000-0000-0000-0000-000000000002', 'NEWER-30', '新客见面礼 · 首单立减', 'cash', 99.00, 30.00, NULL, NULL,
     5000, 0, 1, NULL, NULL, 15, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, true, false, false, 'keep', 'claim', 0),
    -- E04 每日限领一次
    ('e1000000-0000-0000-0000-000000000003', 'DAILY-8', '每日鲜花小礼券', 'cash', 59.00, 8.00, NULL, NULL,
     0, 0, 2, NULL, NULL, 1, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 1, false, false, false, 'keep', 'claim', 0),
    -- E07 可转赠
    ('e1000000-0000-0000-0000-000000000004', 'GIFT-25', '花语传情 · 可转赠券', 'cash', 149.00, 25.00, NULL, NULL,
     1000, 0, 1, NULL, NULL, 45, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, false, true, false, 'keep', 'claim', 0),
    -- E18 VIP 专享折扣券
    ('e1000000-0000-0000-0000-000000000005', 'VIP-88', '会员专享 · 92 折封顶券', 'discount', 0.00, NULL, 0.92, 50.00,
     800, 0, 2, NULL, NULL, 60, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, false, false, true, 'keep', 'claim', 0),
    -- E15 下单返券（支付成功后自动发，不进领券中心）
    ('e1000000-0000-0000-0000-000000000006', 'REBATE-15', '回访礼 · 下单返券', 'cash', 129.00, 15.00, NULL, NULL,
     0, 0, 99, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, false, false, false, 'keep', 'after_pay', 99.00),
    -- E16 邀请奖励：被邀请人首单券
    ('e1000000-0000-0000-0000-000000000007', 'INVITEE-20', '邀请有礼 · 新客券', 'cash', 99.00, 20.00, NULL, NULL,
     0, 0, 1, NULL, NULL, 30, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, false, false, false, 'keep', 'invite', 0),
    -- E16 邀请奖励：邀请人券
    ('e1000000-0000-0000-0000-000000000008', 'INVITER-20', '邀请有礼 · 推荐官券', 'cash', 129.00, 20.00, NULL, NULL,
     0, 0, 9, NULL, NULL, 60, NULL, 'all', NULL, 'active', now(), now(),
     NULL, 0, false, false, false, 'keep', 'invite', 0)
ON CONFLICT (id) DO NOTHING;

-- 花田奖励券由服务端按 code 直发，不应出现在领券中心（V15 的口径）
UPDATE coupon SET trigger_scene = 'admin' WHERE code LIKE 'GARDEN-%' AND trigger_scene = 'claim';

-- E02/E03 限品类与商品白名单的示例模板：分类与商品 id 在库里真实存在才挂上去，
-- 找不到就退化成全场券，避免种子脚本在空库上报外键错
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, start_time, end_time, valid_days, valid_end_time, scope, category_id, status,
                    created_at, updated_at, category_ids, product_ids, disable_policy, trigger_scene)
SELECT 'e1000000-0000-0000-0000-000000000009', 'ROSE-40', '玫瑰系列专享券', 'cash', 199.00, 40.00, NULL, NULL,
       1000, 0, 1, NULL, NULL, 30, NULL, 'category',
       (SELECT id FROM category WHERE name LIKE '%玫瑰%' ORDER BY sort_order LIMIT 1), 'active', now(), now(),
       COALESCE((SELECT string_agg(c.id::text, ',' ORDER BY c.sort_order)
                   FROM category c
                  WHERE c.parent_id IS NULL
                    AND (c.name LIKE '%玫瑰%' OR c.name LIKE '%花礼%' OR c.name LIKE '%永生花%')),
                 (SELECT id::text FROM category WHERE name LIKE '%玫瑰%' ORDER BY sort_order LIMIT 1)),
       COALESCE((SELECT string_agg(p.id::text, ',' ORDER BY p.sales_count DESC)
                   FROM product p WHERE p.is_active = true AND p.category_id IN
                   (SELECT id FROM category WHERE name LIKE '%玫瑰%' LIMIT 5)), NULL),
       'keep', 'claim'
WHERE EXISTS (SELECT 1 FROM category WHERE name LIKE '%玫瑰%')
ON CONFLICT (id) DO NOTHING;

-- E14 满减活动：无需领券自动生效，默认与券不叠加（取较优者）
INSERT INTO full_reduction (id, name, scope, category_ids, ladder_rule, stack_with_coupon, priority,
                            start_time, end_time, status, created_at, updated_at)
VALUES
    ('e2000000-0000-0000-0000-000000000001', '全场满额立减 · 日常档', 'all', NULL, '199:15,399:40', false, 10,
     NULL, NULL, 'active', now(), now()),
    ('e2000000-0000-0000-0000-000000000002', '大客户满额立减 · 可与券叠加', 'all', NULL, '799:120', true, 20,
     NULL, NULL, 'active', now(), now())
ON CONFLICT (id) DO NOTHING;

-- E19 促销位：首页与商品详情页领券条、领券中心顶部条
INSERT INTO promotion_slot (id, position, name, coupon_id, title, subtitle, link_url, image_url,
                            sort_order, status, start_time, end_time, created_at, updated_at)
VALUES
    ('e3000000-0000-0000-0000-000000000001', 'home', '首页 · 阶梯礼领券条',
     'e1000000-0000-0000-0000-000000000001', '满 199 减 20，满 399 直接减 60', '系统自动取最优档，无需凑单试算',
     '/user/coupon-center', NULL, 1, 'active', NULL, NULL, now(), now()),
    ('e3000000-0000-0000-0000-000000000002', 'pdp', '详情页 · 新客礼领券条',
     'e1000000-0000-0000-0000-000000000002', '第一次下单？先领 30 元见面礼', '仅未成交账号可领',
     '/user/coupon-center', NULL, 1, 'active', NULL, NULL, now(), now()),
    ('e3000000-0000-0000-0000-000000000003', 'home', '首页 · 会员专享位',
     'e1000000-0000-0000-0000-000000000005', '会员专享 92 折', '开通会员后可领，单笔封顶抵 50 元',
     '/user/coupons', NULL, 2, 'active', NULL, NULL, now(), now()),
    ('e3000000-0000-0000-0000-000000000004', 'center', '领券中心 · 转赠玩法说明',
     'e1000000-0000-0000-0000-000000000004', '领到的券可以送给 TA', '生成转赠码，对方凭码一键领取',
     '/user/coupons', NULL, 1, 'inactive', NULL, NULL, now(), now())
ON CONFLICT (id) DO NOTHING;
