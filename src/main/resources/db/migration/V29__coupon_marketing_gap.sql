-- ============================================================================
-- V29：优惠券与营销补齐（E01–E20 缺口），V21 已执行，本文件只做增量且可重复执行
--   1) 索引：E13 按模板聚合统计、E10 领券中心按「claim 场景 + 领取截止」取数
--   2) 种子：阶梯+多品类（E01/E02）、商品白名单（E03）、每日限领+可转赠（E04/E07）、
--      会员专享阶梯（E18/E01）、分类满减（E14）、购物车与领券中心促销位（E19）
--   3) 说明：券条款在领取时快照到 user_coupon，这里改的都是模板与投放位
-- 约定：新增列一律 ADD COLUMN IF NOT EXISTS，索引 IF NOT EXISTS，种子 WHERE NOT EXISTS，时间用 now()
-- ============================================================================

-- ------------------------------------------------------------------- 索引
-- E13：后台列表一次聚合「领取数 / 核销数 / 核销金额」，按模板与状态取数
CREATE INDEX IF NOT EXISTS idx_user_coupon_stats ON user_coupon (coupon_id, status);
-- E10：领券中心的固定形状是「启用 + claim 场景 + 按截止时间优先」，partial 索引比 V21 的通用索引更贴合
CREATE INDEX IF NOT EXISTS idx_coupon_center ON coupon (end_time, created_at DESC)
    WHERE status = 'active' AND trigger_scene = 'claim';
-- E06：到期提醒条按「本人 + 未使用 + 到期时间窗」扫描，V7 的 idx_user_coupon_user 少了一层排序
CREATE INDEX IF NOT EXISTS idx_user_coupon_remind ON user_coupon (user_id, expire_at)
    WHERE status = 'unused';

-- ---------------------------------------------------------------- 券模板种子
-- E01 + E02：满减阶梯 + 限多个品类（阶梯自动取最优档，分类取前三个大类）
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, valid_days, scope, category_id, category_ids, status,
                    ladder_rule, per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount, created_at, updated_at)
SELECT 'e1000000-0000-0000-0000-0000000000a1', 'LADDER-SHOP', '满额阶梯 · 大类花礼通用', 'cash', 199.00, 20.00, NULL, NULL,
       1200, 0, 1, 30, 'category',
       (SELECT c.id FROM category c WHERE c.parent_id IS NULL ORDER BY c.sort_order LIMIT 1),
       (SELECT string_agg(x.id::text, ',' ORDER BY x.sort_order)
          FROM (SELECT c.id, c.sort_order FROM category c WHERE c.parent_id IS NULL
                 ORDER BY c.sort_order LIMIT 3) x),
       'active', '199:20,399:60,599:100', 0, false, false, false, 'keep', 'claim', 0, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM coupon WHERE code = 'LADDER-SHOP')
  AND EXISTS (SELECT 1 FROM category WHERE parent_id IS NULL);

-- E03：商品白名单券，只覆盖在售人气款；白名单非空时只有名单内的行参与算价
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, valid_days, scope, product_ids, status,
                    per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount, created_at, updated_at)
SELECT 'e1000000-0000-0000-0000-0000000000a2', 'WHITE-TOP', '人气花礼专享券 · 指定款可用', 'cash', 150.00, 30.00, NULL, NULL,
       600, 0, 1, 20, 'all',
       (SELECT string_agg(x.id::text, ',')
          FROM (SELECT p.id FROM product p WHERE p.is_active = true
                 ORDER BY p.sales_count DESC, p.id LIMIT 6) x),
       'active', 0, false, false, false, 'keep', 'claim', 0, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM coupon WHERE code = 'WHITE-TOP')
  AND EXISTS (SELECT 1 FROM product WHERE is_active = true);

-- E04 + E07：每人每日限领一张 + 允许转赠，配合 uq_user_coupon_daily_claim 挡住重复领取
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, valid_days, scope, status,
                    ladder_rule, per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount, created_at, updated_at)
VALUES
    ('e1000000-0000-0000-0000-0000000000a3', 'DAILY-GIFT', '每日一张 · 可转赠小礼券', 'cash', 79.00, 10.00, NULL, NULL,
     0, 0, 3, 7, 'all', 'active',
     NULL, 1, false, true, false, 'keep', 'claim', 0, now(), now())
ON CONFLICT (id) DO NOTHING;

-- E18 + E01：会员专享的阶梯折扣券（member_only 与阶梯同时生效，验证两处判定不互相覆盖）
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, valid_days, scope, status,
                    ladder_rule, per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount, created_at, updated_at)
VALUES
    ('e1000000-0000-0000-0000-0000000000a4', 'VIP-LADDER', '会员专享 · 阶梯折上折', 'discount', 299.00, NULL, 0.90, 80.00,
     500, 0, 2, 60, 'all', 'active',
     NULL, 0, false, false, true, 'keep', 'claim', 0, now(), now())
ON CONFLICT (id) DO NOTHING;

-- E15：实付满 199 返一张 20 元回访券（after_pay 场景，不进领券中心，由下单链路支付成功后发放）
INSERT INTO coupon (id, code, name, type, threshold, amount, discount_rate, max_discount, total, issued,
                    per_user_limit, valid_days, scope, status,
                    per_user_daily_limit, new_user_only, allow_transfer, member_only,
                    disable_policy, trigger_scene, grant_min_amount, created_at, updated_at)
VALUES
    ('e1000000-0000-0000-0000-0000000000a5', 'REBATE-20', '回访礼 · 满 199 返 20', 'cash', 109.00, 20.00, NULL, NULL,
     0, 0, 99, 30, 'all', 'active',
     0, false, false, false, 'keep', 'after_pay', 199.00, now(), now())
ON CONFLICT (id) DO NOTHING;

-- E14：分类满减（无需领券自动生效，与券不叠加时取较优的一条），验证满减也能限品类
INSERT INTO full_reduction (id, name, scope, category_ids, ladder_rule, stack_with_coupon, priority,
                            start_time, end_time, status, created_at, updated_at)
SELECT 'e2000000-0000-0000-0000-0000000000a1', '大类满额立减 · 可与券叠加', 'category',
       (SELECT string_agg(x.id::text, ',' ORDER BY x.sort_order)
          FROM (SELECT c.id, c.sort_order FROM category c WHERE c.parent_id IS NULL
                 ORDER BY c.sort_order LIMIT 2) x),
       '299:30,499:60', true, 15, NULL, NULL, 'active', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM full_reduction WHERE id = 'e2000000-0000-0000-0000-0000000000a1')
  AND EXISTS (SELECT 1 FROM category WHERE parent_id IS NULL);

-- ---------------------------------------------------------------- 促销位（E19）
-- 购物车凑单位：B 系列购物车页读 position=cart 的位，开关关掉即隐藏
INSERT INTO promotion_slot (id, position, name, coupon_id, title, subtitle, link_url, image_url,
                            sort_order, status, start_time, end_time, created_at, updated_at)
SELECT 'e3000000-0000-0000-0000-0000000000a1', 'cart', '购物车 · 阶梯满减提示',
       (SELECT id FROM coupon WHERE code = 'LADDER-SHOP'),
       '再凑一点就够下一档，系统自动取最优档', '满 199 减 20、满 399 减 60、满 599 减 100',
       '/coupon-center', NULL, 1, 'active', NULL, NULL, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM promotion_slot WHERE id = 'e3000000-0000-0000-0000-0000000000a1')
  AND EXISTS (SELECT 1 FROM coupon WHERE code = 'LADDER-SHOP');

-- 领券中心顶部条：E10 页面顶部说明位，后台可关
INSERT INTO promotion_slot (id, position, name, coupon_id, title, subtitle, link_url, image_url,
                            sort_order, status, start_time, end_time, created_at, updated_at)
SELECT 'e3000000-0000-0000-0000-0000000000a2', 'center', '领券中心 · 新客与会员说明',
       (SELECT id FROM coupon WHERE code = 'VIP-LADDER'),
       '先看清适用范围再领', '新客专享、会员专享与指定花礼券都会在券面标注适用范围',
       '/user/coupons', NULL, 1, 'active', NULL, NULL, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM promotion_slot WHERE id = 'e3000000-0000-0000-0000-0000000000a2')
  AND EXISTS (SELECT 1 FROM coupon WHERE code = 'VIP-LADDER');

-- 详情页促销位补一张白名单券：验证 E03 的券在详情页领券条也能出现
INSERT INTO promotion_slot (id, position, name, coupon_id, title, subtitle, link_url, image_url,
                            sort_order, status, start_time, end_time, created_at, updated_at)
SELECT 'e3000000-0000-0000-0000-0000000000a3', 'pdp', '详情页 · 人气款专享券',
       (SELECT id FROM coupon WHERE code = 'WHITE-TOP'),
       '这款在专享券名单里', '券只覆盖指定花礼，适用范围写在券面上',
       '/coupon-center', NULL, 2, 'active', NULL, NULL, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM promotion_slot WHERE id = 'e3000000-0000-0000-0000-0000000000a3')
  AND EXISTS (SELECT 1 FROM coupon WHERE code = 'WHITE-TOP');

-- V21 的促销位种子把入口写成了 /user/coupon-center，而 /user/** 被鉴权拦截器要求登录；
-- 领券中心要游客可逛，统一改到公开的 /coupon-center（只改写这一条历史值，第二次执行不再命中）
UPDATE promotion_slot SET link_url = '/coupon-center', updated_at = now()
WHERE link_url = '/user/coupon-center';
