-- ============================================================================
-- V8：订单接入优惠券
--   order.coupon_amount   本单优惠券实际抵扣额（与 VIP 折扣、积分抵扣分列，便于对账与退款回退）
--   order.user_coupon_id  使用的持券 id，取消/退款时据此把券退回券包
-- ============================================================================

ALTER TABLE "order" ADD COLUMN IF NOT EXISTS coupon_amount  numeric(10,2);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS user_coupon_id uuid;

CREATE INDEX IF NOT EXISTS idx_order_user_coupon ON "order" (user_coupon_id) WHERE user_coupon_id IS NOT NULL;
