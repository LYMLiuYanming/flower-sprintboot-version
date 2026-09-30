-- ============================================================================
-- V24：H 组（数据看板与报表）聚合查询索引
--   看板所有指标都走数据库 GROUP BY，时间窗 + 状态是最主要的过滤条件；
--   这里只补索引，不建物化视图：数据量级（千级订单）下刷新逻辑带来的复杂度远大于收益。
-- 说明：全部 IF NOT EXISTS，可在已有库上重复执行。
-- ============================================================================

-- 1. 成交类查询的驱动列：status ∈ DEAL_STATUSES 且按成交时间（pay_time，缺失回落 created_at）取区间。
--    单列索引在多条件筛选下不划算，这里按 (status, pay_time) 建复合索引。
CREATE INDEX IF NOT EXISTS idx_order_status_pay_time    ON "order" (status, pay_time);
--    漏斗类查询（下单量、转化率）按 created_at 过滤，与已有的 (status, created_at) 互补
CREATE INDEX IF NOT EXISTS idx_order_created_at         ON "order" (created_at);

-- 2. 履约时效：分位数只取已发货/已签收的单，ship_time 非空是主要过滤条件（partial index 更小更快）
CREATE INDEX IF NOT EXISTS idx_order_ship_time          ON "order" (ship_time) WHERE ship_time IS NOT NULL;
-- 3. 退款/取消原因分布按关闭时刻聚合，finish_time 仅取消/退款单会写
CREATE INDEX IF NOT EXISTS idx_order_finish_time        ON "order" (finish_time) WHERE finish_time IS NOT NULL;
-- 4. 券带动 GMV：order.user_coupon_id 反查持券，只有用券单有值
CREATE INDEX IF NOT EXISTS idx_order_user_coupon        ON "order" (user_coupon_id) WHERE user_coupon_id IS NOT NULL;

-- 5. 券模板核销率按 coupon_id + status 聚合（已有 (coupon_id, user_id) 覆盖不到 status）
CREATE INDEX IF NOT EXISTS idx_user_coupon_coupon_status ON user_coupon (coupon_id, status);

-- 6. 库存告急清单：在售商品按库存排序/过滤
CREATE INDEX IF NOT EXISTS idx_product_active_stock      ON product (is_active, stock);

-- 7. 口碑卡与退款关联分析按评价时间过滤，visible 是硬过滤条件
CREATE INDEX IF NOT EXISTS idx_review_visible_created    ON review (created_at) WHERE visible = true;
