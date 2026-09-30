-- ============================================================================
-- V10：多方式配送 + 订单轨迹 + 鲜花贺卡
--   order.delivery_method    配送方式码（同城闪送/次日达/预约定时达/空运冷链/海运保鲜/门店自提）
--   order.delivery_weight    服务端按商品重量累加的整单估算重量，运费复核用
--   order.delivery_slot      预约定时达所选时段（ISO 文本）
--   order.expected_arrive_at 预计送达时间，由配送方式时效或所选时段推出
--   order.card_*             贺卡快照：下单即定稿，改模板不影响历史单
--   order_trace              物流与履约轨迹，状态流转自动写入 + 后台补记
-- 说明：全部为增量列且允许为空，历史订单不回填，页面按"未选择"降级展示。
-- ============================================================================

ALTER TABLE "order" ADD COLUMN IF NOT EXISTS delivery_method    varchar(20);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS delivery_weight    numeric(6,2);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS delivery_slot      varchar(20);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS expected_arrive_at timestamp;
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS card_style         varchar(20);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS card_recipient     varchar(50);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS card_signature     varchar(50);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS card_message       varchar(200);

CREATE INDEX IF NOT EXISTS idx_order_expected_arrive ON "order" (expected_arrive_at) WHERE expected_arrive_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS order_trace (
    id          uuid          NOT NULL,
    order_id    uuid          NOT NULL,
    code        varchar(30)   NOT NULL,
    title       varchar(60)   NOT NULL,
    description varchar(255),
    operator    varchar(60),
    created_at  timestamp     NOT NULL,
    CONSTRAINT order_trace_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_trace_order FOREIGN KEY (order_id) REFERENCES "order" (id)
);

CREATE INDEX IF NOT EXISTS idx_order_trace_order ON order_trace (order_id, created_at);
