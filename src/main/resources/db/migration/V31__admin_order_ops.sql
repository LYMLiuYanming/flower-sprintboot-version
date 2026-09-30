-- ============================================================================
-- V31：后台订单操作批次（C11–C14 / C21–C25 / G19–G21）
--   shipping_company      发货物流公司字典 + 单号校验规则 + 外部查询链接（C11/C12）
--   order_trace_audit     被删除轨迹节点的原文快照（C13：删掉的内容必须还能查回来）
--   order_remark_edit     后台改备注的改前/改后留痕（C23）
--   order_freight_adjust  运费微调的改前/改后与调整原因（C24）
-- 说明：不改既有任何表的列——发货公司按 order.express_company 的字典名回认，
--       避免为一个展示字段改动交易主链路的实体映射。全部语句可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS shipping_company (
    id         uuid          NOT NULL,
    code       varchar(30)   NOT NULL,
    name       varchar(50)   NOT NULL,
    no_pattern varchar(160)  NOT NULL,
    no_example varchar(60),
    no_hint    varchar(160),
    track_host varchar(120),
    track_path varchar(200),
    sort_order integer       NOT NULL DEFAULT 0,
    is_active  boolean       NOT NULL DEFAULT true,
    created_at timestamp     NOT NULL,
    updated_at timestamp     NOT NULL,
    CONSTRAINT shipping_company_pkey PRIMARY KEY (id),
    CONSTRAINT uk_shipping_company_code UNIQUE (code),
    CONSTRAINT uk_shipping_company_name UNIQUE (name)
);

CREATE INDEX IF NOT EXISTS idx_shipping_company_active ON shipping_company (is_active, sort_order);

-- 单号规则按「常见位数」放宽一档：宁可放过一位校验也不要把真实单号挡在发货按钮外，
-- 但位数错、混进中文或空格这类明显笔误必须当场拦下。track_host 为空的表示无外部查询入口。
INSERT INTO shipping_company (id, code, name, no_pattern, no_example, no_hint, track_host, track_path, sort_order, is_active, created_at, updated_at)
VALUES
    ('b1000000-0000-0000-0000-000000000001', 'sf', '顺丰速运', '^(?:SF)?\d{12}$', 'SF123456789012',
     'SF 开头或不带前缀 + 12 位数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=shunfeng', 10, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000002', 'zto', '中通快递', '^\d{12}$', '751234567890',
     '12 位纯数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=zhongtong', 20, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000003', 'yto', '圆通速递', '^(?:YT)?\d{10}$', 'YT1234567890',
     'YT 开头或不带前缀 + 10 位数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=yuantong', 30, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000004', 'yunda', '韵达速递', '^(?:YD)?\d{13}$', 'YD1234567890123',
     'YD 开头或不带前缀 + 13 位数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=yunda', 40, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000005', 'sto', '申通快递', '^(?:66|77|88)?\d{10,12}$', '661234567890',
     '10 到 12 位数字，可带 66/77/88 前缀', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=shentong', 50, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000006', 'jt', '极兔速递', '^JT\d{13}$', 'JT1234567890123',
     'JT + 13 位数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=jitu', 60, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000007', 'jd', '京东物流', '^JD[A-Z0-9]{11,13}$', 'JD001234567890',
     'JD + 11 到 13 位大写字母或数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=jd', 70, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000008', 'deppon', '德邦快递', '^(?:DPK|DTD)?\d{10,14}$', 'DPK1234567890',
     '可选 DPK/DTD 前缀 + 10 到 14 位数字', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=debangwuliu', 80, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000009', 'ems', '邮政 EMS', '^[A-Z]{2}\d{9}CN$', 'EA123456789CN',
     '两位字母 + 9 位数字 + CN', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=ems', 90, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000010', 'cainiao', '菜鸟直送', '^[A-Z]{0,2}\d{10,14}$', '6112345678901',
     '10 到 14 位数字，可带两位字母前缀', 'www.kuaidi100.com', '/chaxun?nu=__NO__&com=cainiao', 100, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000011', 'shansong', '同城闪送', '^\d{10,16}$', '123456789012',
     '10 到 16 位纯数字', NULL, NULL, 110, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000012', 'selfsend', '门店自送', '^ZS\d{6,12}$', 'ZS20260930',
     'ZS + 6 到 12 位数字（店内配送单号）', NULL, NULL, 120, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000013', 'pickup', '客户自提', '^ZT\d{6,12}$', 'ZT20260930',
     'ZT + 6 到 12 位数字（取货码）', NULL, NULL, 130, true, now(), now()),
    ('b1000000-0000-0000-0000-000000000014', 'other', '其他快递公司', '^[A-Za-z0-9-]{6,60}$', 'QP123456789',
     '6 到 60 位字母、数字或连字符', 'www.kuaidi100.com', '/chaxun?nu=__NO__', 140, true, now(), now())
ON CONFLICT (code) DO NOTHING;

-- 误删的轨迹节点原文：节点本身从 order_trace 物理删除，快照与删除人留在这里
CREATE TABLE IF NOT EXISTS order_trace_audit (
    id             uuid          NOT NULL,
    order_id       uuid          NOT NULL,
    trace_id       uuid          NOT NULL,
    code           varchar(30)   NOT NULL,
    title          varchar(60)   NOT NULL,
    description    varchar(255),
    node_operator  varchar(60),
    node_created_at timestamp,
    reason         varchar(200),
    deleted_by     varchar(50),
    deleted_by_id  uuid,
    deleted_at     timestamp     NOT NULL,
    CONSTRAINT order_trace_audit_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_trace_audit_order FOREIGN KEY (order_id) REFERENCES "order" (id)
);

CREATE INDEX IF NOT EXISTS idx_order_trace_audit_order ON order_trace_audit (order_id, deleted_at);

-- 后台改备注：改前改后都存原文，订单表只留最新值
CREATE TABLE IF NOT EXISTS order_remark_edit (
    id            uuid          NOT NULL,
    order_id      uuid          NOT NULL,
    remark_before varchar(500),
    remark_after  varchar(500),
    reason        varchar(200),
    operator_name varchar(50),
    operator_id   uuid,
    created_at    timestamp     NOT NULL,
    CONSTRAINT order_remark_edit_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_remark_edit_order FOREIGN KEY (order_id) REFERENCES "order" (id)
);

CREATE INDEX IF NOT EXISTS idx_order_remark_edit_order ON order_remark_edit (order_id, created_at);

-- 运费微调：同时记下实付金额的改前改后，对账时不必再按公式反推
CREATE TABLE IF NOT EXISTS order_freight_adjust (
    id              uuid          NOT NULL,
    order_id        uuid          NOT NULL,
    freight_before  numeric(10,2) NOT NULL,
    freight_after   numeric(10,2) NOT NULL,
    pay_before      numeric(10,2) NOT NULL,
    pay_after       numeric(10,2) NOT NULL,
    reason          varchar(200)  NOT NULL,
    operator_name   varchar(50),
    operator_id     uuid,
    created_at      timestamp     NOT NULL,
    CONSTRAINT order_freight_adjust_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_freight_adjust_order FOREIGN KEY (order_id) REFERENCES "order" (id)
);

CREATE INDEX IF NOT EXISTS idx_order_freight_adjust_order ON order_freight_adjust (order_id, created_at);

-- 异常看板（C25/G21）按状态 + 时间窗筛选。超时未发货走的 (status, pay_time) 已由 V24 建好，
-- 长时间未付款走的 (status, created_at) 已由 V1 建好，这里只补退款跟进要用的 updated_at。
CREATE INDEX IF NOT EXISTS idx_order_status_updated ON "order" (status, updated_at);
