-- ============================================================================
-- V19：购物车与结算（B 组）
--   cart_item.gift_wrap    礼品包装开关（B01），费用按整单收一次
--   cart_item.note         每束一句话（B02），下单时快照进 order_item.item_note
--   cart_item.added_price  加购价快照（B07），降价提示的唯一基准
--   cart_item.added_at     加购时刻，失效分组排序用，与"更新时间"解耦
--   order_item.item_note   行备注快照：改购物车不影响已下单花束的说明
--   order_item.gift_wrap   行级包装标记，供花艺师分束包扎
-- 说明：dev 下 ddl-auto=update 会自动补列，这里显式建列是给 validate 模式兜底；
--       新增列都带默认值，存量行不会因为 NOT NULL 补列而失败。
-- ============================================================================

ALTER TABLE cart_item  ADD COLUMN IF NOT EXISTS gift_wrap   boolean  NOT NULL DEFAULT false;
ALTER TABLE cart_item  ADD COLUMN IF NOT EXISTS note        varchar(200);
ALTER TABLE cart_item  ADD COLUMN IF NOT EXISTS added_price numeric(10,2);
ALTER TABLE cart_item  ADD COLUMN IF NOT EXISTS added_at    timestamp;

ALTER TABLE order_item ADD COLUMN IF NOT EXISTS item_note   varchar(200);
ALTER TABLE order_item ADD COLUMN IF NOT EXISTS gift_wrap   boolean  NOT NULL DEFAULT false;

-- 存量购物车行没有价格快照，用当前行价回填；否则上线当天所有行都显示"无降价"
UPDATE cart_item SET added_price = ROUND(price::numeric, 2) WHERE added_price IS NULL;
UPDATE cart_item SET added_at = now() WHERE added_at IS NULL;

-- 券面板（B09）：结算页每次试算都按「本人 + 未使用 + 未过期」取券
CREATE INDEX IF NOT EXISTS idx_user_coupon_checkout ON user_coupon (user_id, status, expire_at);
-- 再次购买（B24）：按订单取明细
CREATE INDEX IF NOT EXISTS idx_order_item_order ON order_item (order_id);
-- 分类专享券适用范围展开（券绑父分类 + 直接子分类）
CREATE INDEX IF NOT EXISTS idx_category_parent ON category (parent_id);
