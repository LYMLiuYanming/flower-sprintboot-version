-- ============================================================================
-- V23：地图 / 天气 / 花田批次（I01-I16）
--   flower_origin     补门店到店信息（地址/电话/营业时间/备花时长），I15 自提地图要用
--                     并追加 1 个西南分拨中心 + 3 家门店，让就近推荐真的有多候选
--   flower_plot       slot_no 支持多块地（I11），唯一索引从「一人一条 growing」改成「一人每块地一条」
--                     gift_state 支持转赠待收礼方确认（I13）
--   plot_growth_log   成长曲线历史（I14）：每次浇水/回落/成熟都留一行，画真实折线而不是前端缓存
--   plot_exchange     成熟兑换与转赠流水（I12/I13）：记录发券快照，券包状态由只读查询现算
-- 说明：时间一律写 now()；所有结构变更都带 IF NOT EXISTS，种子数据用 WHERE NOT EXISTS 守卫，重复执行不产生第二行
-- ============================================================================

-- ---------- I15：门店到店信息 ----------
ALTER TABLE flower_origin ADD COLUMN IF NOT EXISTS address             varchar(160);
ALTER TABLE flower_origin ADD COLUMN IF NOT EXISTS phone               varchar(20);
ALTER TABLE flower_origin ADD COLUMN IF NOT EXISTS open_hours          varchar(60);
ALTER TABLE flower_origin ADD COLUMN IF NOT EXISTS pickup_ready_minutes integer;

-- 已有三家门店补齐到店细则（只在为空时回填，不覆盖后台已改过的值）
UPDATE flower_origin SET
    address              = '北京市朝阳区金台夕照路 8 号 1 层',
    phone                = '010-8500-1001',
    open_hours           = '09:00-21:00',
    pickup_ready_minutes = 120
WHERE name = '花语轩·北京朝阳门店' AND address IS NULL;

UPDATE flower_origin SET
    address              = '上海市徐汇区漕溪北路 398 号 1 层',
    phone                = '021-6400-1002',
    open_hours           = '09:30-21:30',
    pickup_ready_minutes = 120
WHERE name = '花语轩·上海徐汇门店' AND address IS NULL;

UPDATE flower_origin SET
    address              = '广州市天河区天河路 208 号东侧 1 层',
    phone                = '020-3830-1003',
    open_hours           = '09:00-21:00',
    pickup_ready_minutes = 90
WHERE name = '花语轩·广州天河门店' AND address IS NULL;

-- 新增自提点：成都 / 杭州 / 西安各一家，让「就近推荐」在同城市也有第二候选
-- 值列表里显式写 ::uuid / ::numeric：VALUES 子查询的列会先被推成 text，而 text 到 uuid 没有隐式转换
INSERT INTO flower_origin (id, name, kind, province, city, adcode, lng, lat, altitude, flowers, feature, story,
                           season, sort_order, is_active, address, phone, open_hours, pickup_ready_minutes,
                           created_at, updated_at)
SELECT v.id, v.name, v.kind, v.province, v.city, v.adcode, v.lng, v.lat, v.altitude, v.flowers, v.feature, v.story,
       v.season, v.sort_order, v.is_active, v.address, v.phone, v.open_hours, v.pickup_ready_minutes, now(), now()
FROM (VALUES
    ('c0000000-0000-0000-0000-000000000004'::uuid, '花语轩·成都锦江门店', 'store', '四川省', '成都市', '510104',
     104.081000::numeric, 30.657000::numeric, 500, '成品花礼', '春熙路商圈',
     '与三圣乡基地同城，下午下单傍晚可自提，店内常驻当季花艺课。', '09:00-21:00', 240, true,
     '成都市锦江区中纱帽街 8 号 1 层', '028-8600-1004', '09:00-21:00', 90),
    ('c0000000-0000-0000-0000-000000000005'::uuid, '花语轩·杭州西湖门店', 'store', '浙江省', '杭州市', '330106',
     120.155000::numeric, 30.259000::numeric, 6, '成品花礼', '延安路步行街',
     '长三角冷链车直达，江浙沪订单当天下单当天可取。', '09:30-21:30', 250, true,
     '杭州市上城区延安路 286 号 1 层', '0571-8700-1005', '09:30-21:30', 120),
    ('c0000000-0000-0000-0000-000000000006'::uuid, '花语轩·西安碑林门店', 'store', '陕西省', '西安市', '610103',
     108.946000::numeric, 34.259000::numeric, 400, '成品花礼', '钟楼商圈',
     '西北区域的自提与婚礼用花备货点，支持当日到店分装。', '09:00-20:30', 260, true,
     '西安市碑林区东大街 58 号 1 层', '029-8700-1006', '09:00-20:30', 150)
     ) AS v(id, name, kind, province, city, adcode, lng, lat, altitude, flowers, feature, story, season,
            sort_order, is_active, address, phone, open_hours, pickup_ready_minutes)
WHERE NOT EXISTS (SELECT 1 FROM flower_origin o WHERE o.id = v.id OR o.name = v.name);

-- 西南分拨中心：成都三圣乡与伊犁方向的干线此前只能连到华北/华东，路线会平白多跑两千公里
INSERT INTO flower_origin (id, name, kind, province, city, adcode, lng, lat, altitude, flowers, feature, story,
                           season, sort_order, is_active, address, phone, open_hours, pickup_ready_minutes,
                           created_at, updated_at)
SELECT 'b0000000-0000-0000-0000-000000000003'::uuid, '西南分拨中心（成都双流）', 'hub', '四川省', '成都市', '510116',
       103.930000::numeric, 30.573000::numeric, 490, '全品类', '双流机场旁 3 小时中转圈',
       '云贵川渝的产地夜发件在此质检预冷并二次分拨，空运件赶早班货机，陆运件凌晨发出。',
       '全年运转', 130, true, '成都市双流区机场路 1 号', NULL, '全年 24 小时作业', NULL, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM flower_origin o
                  WHERE o.name = '西南分拨中心（成都双流）' OR o.id = 'b0000000-0000-0000-0000-000000000003'::uuid);

-- ---------- I11：多块地（会员解锁第二块） ----------
ALTER TABLE flower_plot ADD COLUMN IF NOT EXISTS slot_no integer NOT NULL DEFAULT 1;
-- ---------- I13：转赠待确认 ----------
ALTER TABLE flower_plot ADD COLUMN IF NOT EXISTS gift_state varchar(16);

-- 旧索引只允许「一人一块 growing」，多块地要按 (user_id, slot_no) 约束；
-- 先建新再删旧，顺序反了会在半路上让并发开坑失去唯一性保护
CREATE UNIQUE INDEX IF NOT EXISTS uq_flower_plot_growing_slot ON flower_plot (user_id, slot_no) WHERE status = 'growing';
DROP INDEX IF EXISTS uq_flower_plot_growing;
CREATE INDEX IF NOT EXISTS idx_flower_plot_gift_state ON flower_plot (gift_user_id, gift_state) WHERE gift_state IS NOT NULL;

-- 已完成的转赠按老流程就是「对方已收下」，否则历史记录会在新流程里显示成待确认
UPDATE flower_plot SET gift_state = 'accepted'
WHERE status = 'gifted' AND gift_user_id IS NOT NULL AND gift_state IS NULL;

-- ---------- I14：成长曲线历史 ----------
CREATE TABLE IF NOT EXISTS plot_growth_log (
    id          uuid          NOT NULL,
    plot_id     uuid          NOT NULL,
    user_id     uuid          NOT NULL,
    slot_no     integer       NOT NULL DEFAULT 1,
    growth      integer       NOT NULL DEFAULT 0,
    stage       varchar(16)   NOT NULL DEFAULT 'seed',
    /** 本次变化量：浇水为正、闲置回落为负、开花/兑换为 0 */
    delta       integer       NOT NULL DEFAULT 0,
    /** plant 开坑 / water 浇水 / decay 闲置回落 / mature 开花 / redeem 兑换 / gift 转赠 */
    source      varchar(16)   NOT NULL,
    weather     varchar(30),
    temperature integer,
    /** 当日天气给这次浇水的加成拆解，页面用它解释「为什么今天涨了 14 点」 */
    sun_bonus   integer,
    water_bonus integer,
    stress      integer,
    logged_at   timestamp     NOT NULL,
    created_at  timestamp     NOT NULL,
    CONSTRAINT plot_growth_log_pkey PRIMARY KEY (id),
    CONSTRAINT fk_plot_growth_log_plot FOREIGN KEY (plot_id) REFERENCES flower_plot (id) ON DELETE CASCADE,
    CONSTRAINT ck_plot_growth_log_source CHECK (source IN ('plant', 'water', 'decay', 'mature', 'redeem', 'gift'))
);

CREATE INDEX IF NOT EXISTS idx_plot_growth_log_plot ON plot_growth_log (plot_id, created_at);
CREATE INDEX IF NOT EXISTS idx_plot_growth_log_user ON plot_growth_log (user_id, slot_no, created_at);

-- ---------- I12：兑换与转赠流水 ----------
CREATE TABLE IF NOT EXISTS plot_exchange (
    id              uuid          NOT NULL,
    plot_id         uuid          NOT NULL,
    user_id         uuid          NOT NULL,
    slot_no         integer       NOT NULL DEFAULT 1,
    seed_code       varchar(30)   NOT NULL,
    seed_name       varchar(60),
    /** redeem 自留兑换 / gift 转赠他人 */
    type            varchar(16)   NOT NULL,
    /** pending 待收礼方确认 / accepted 已到账 / declined 已婉拒 / canceled 已撤回 */
    state           varchar(16)   NOT NULL DEFAULT 'accepted',
    coupon_code     varchar(30),
    coupon_name     varchar(50),
    amount          numeric(10, 2),
    threshold       numeric(10, 2),
    valid_days      integer,
    user_coupon_id  uuid,
    receiver_id     uuid,
    receiver_name   varchar(100),
    message         varchar(200),
    created_at      timestamp     NOT NULL,
    updated_at      timestamp     NOT NULL,
    CONSTRAINT plot_exchange_pkey PRIMARY KEY (id),
    CONSTRAINT fk_plot_exchange_plot FOREIGN KEY (plot_id) REFERENCES flower_plot (id) ON DELETE CASCADE,
    CONSTRAINT ck_plot_exchange_type CHECK (type IN ('redeem', 'gift')),
    CONSTRAINT ck_plot_exchange_state CHECK (state IN ('pending', 'accepted', 'declined', 'canceled'))
);

CREATE INDEX IF NOT EXISTS idx_plot_exchange_user ON plot_exchange (user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_plot_exchange_state ON plot_exchange (state, created_at) WHERE state = 'pending';
