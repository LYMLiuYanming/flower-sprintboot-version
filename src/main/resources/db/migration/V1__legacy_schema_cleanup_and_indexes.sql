-- ============================================================================
-- V1：旧版建表遗留清理 + 查询索引补齐
-- 背景：本库早期由 Prisma 生成结构，实体改名后遗留 product_category 死表与
--       product 表的一批无用列；本脚本把这些历史包袱一次性收敛，并补上前台/后台
--       列表查询真正用到的复合索引。脚本幂等，可在已有库上重复执行。
-- ============================================================================

-- 1. 遗留死表：0 行且仅自引用外键，确认无引用后删除
DROP TABLE IF EXISTS product_category;

-- 2. product 遗留列：经核查 8 行数据中全部为 NULL 或仅默认值，删除不丢业务数据
--    （依赖这些列的 CHECK 约束随列一起被移除）
ALTER TABLE product
    DROP COLUMN IF EXISTS detail_images,
    DROP COLUMN IF EXISTS flower_count,
    DROP COLUMN IF EXISTS flower_type,
    DROP COLUMN IF EXISTS fresh_period,
    DROP COLUMN IF EXISTS is_new_arrival,
    DROP COLUMN IF EXISTS is_recommended,
    DROP COLUMN IF EXISTS shelf_time,
    DROP COLUMN IF EXISTS status,
    DROP COLUMN IF EXISTS stock_quantity,
    DROP COLUMN IF EXISTS suitable_occasion;

-- 3. 旧约束：code 曾为 NOT NULL，后台新增商品允许先不填编码、上架后再补
ALTER TABLE product ALTER COLUMN code DROP NOT NULL;

-- 4. 种子数据自愈：历史版本按“表非空即跳过”的守卫在旧实现下被重复写入，
--    这里按业务唯一键保留最早一条，其余清理
DELETE FROM banner
WHERE ctid NOT IN (SELECT min(ctid) FROM banner GROUP BY title, image_url);

DELETE FROM notice
WHERE ctid NOT IN (SELECT min(ctid) FROM notice GROUP BY title, content);

-- 5. 索引：前台列表/搜索、后台筛选、订单看板聚合
CREATE INDEX IF NOT EXISTS idx_product_category_active ON product (category_id, is_active);
CREATE INDEX IF NOT EXISTS idx_product_active_featured ON product (is_active, is_featured);
CREATE INDEX IF NOT EXISTS idx_product_active_sales    ON product (is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_active_price    ON product (is_active, price);
CREATE INDEX IF NOT EXISTS idx_category_parent         ON category (parent_id, sort_order);

CREATE INDEX IF NOT EXISTS idx_order_user_status    ON "order" (user_id, status);
CREATE INDEX IF NOT EXISTS idx_order_status_created ON "order" (status, created_at);
CREATE INDEX IF NOT EXISTS idx_order_pay_time       ON "order" (pay_time);
CREATE INDEX IF NOT EXISTS idx_order_item_order     ON order_item (order_id);
CREATE INDEX IF NOT EXISTS idx_order_item_product   ON order_item (product_id);

CREATE INDEX IF NOT EXISTS idx_cart_item_cart       ON cart_item (cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_item_product    ON cart_item (product_id);
CREATE INDEX IF NOT EXISTS idx_banner_status_sort   ON banner (status, sort_order);
CREATE INDEX IF NOT EXISTS idx_notice_status_top    ON notice (status, is_top, created_at);

-- 唯一约束先验证存量无冲突再建，避免迁移在脏数据库上直接失败
DO $$
BEGIN
    IF NOT EXISTS (SELECT user_id FROM cart GROUP BY user_id HAVING count(*) > 1) THEN
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_cart_user ON cart (user_id)';
    ELSE
        RAISE NOTICE 'cart.user_id 存在重复数据，跳过 uk_cart_user';
    END IF;

    IF NOT EXISTS (SELECT email FROM "user" WHERE email IS NOT NULL GROUP BY email HAVING count(*) > 1) THEN
        EXECUTE 'CREATE UNIQUE INDEX IF NOT EXISTS uk_user_email ON "user" (email) WHERE email IS NOT NULL';
    ELSE
        RAISE NOTICE 'user.email 存在重复数据，跳过 uk_user_email';
    END IF;
END $$;
