-- ============================================================================
-- V11：结构化送达偏好
--   order.delivery_preference  放置位置码（meet 当面签收 / door 放门口 / front 放前台 / locker 放快递柜）
--   order.contact_preference   联系方式码（call 送达前电话联系 / msg 到了发短信 / quiet 请勿联系）
-- 说明：偏好只约束配送员动作，不参与运费与计价；历史订单留空，页面按「当面签收」降级展示。
-- ============================================================================

ALTER TABLE "order" ADD COLUMN IF NOT EXISTS delivery_preference varchar(20);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS contact_preference  varchar(20);
