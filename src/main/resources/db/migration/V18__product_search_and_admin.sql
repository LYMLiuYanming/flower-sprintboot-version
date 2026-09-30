-- ============================================================================
-- V18：商品检索/后台维护所需的数据收敛（A06/A22/A24/A26）
--   1) product.code 的唯一性此前只靠实体注解，这里补一个可辨识的索引，
--      并在存量已有重复号时跳过建索引（宁可不建，也不能把迁移卡死）。
--   2) 列表页新增的排序键（created_at / stock+sales_count）与「无结果兜底」
--      查询都走 is_active 前导索引，缺索引会让筛选页在大表上退化成全表扫描。
--   3) 分类图标收敛成 Font Awesome 类名后缀（去掉 fa- / fas 前缀），
--      前台按 'fa fa-' + icon 拼类名，历史数据里两种写法都有。
-- ============================================================================

-- 1. 商品编码唯一：存量重复时不阻塞迁移，由后台保存时的应用层查重兜住
DO $$
    DECLARE
        has_unique boolean;
        has_dup    boolean;
    BEGIN
        SELECT EXISTS (
                   SELECT 1
                     FROM pg_index i
                     JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                    WHERE i.indrelid = 'product'::regclass
                      AND i.indisunique
                      AND a.attname = 'code'
               )
             INTO has_unique;

        SELECT EXISTS (
                   SELECT 1 FROM product WHERE code IS NOT NULL GROUP BY code HAVING COUNT(*) > 1
               )
             INTO has_dup;

        IF NOT has_unique AND NOT has_dup THEN
            EXECUTE 'CREATE UNIQUE INDEX uq_product_code ON product (code)';
        ELSIF has_dup THEN
            RAISE NOTICE 'product.code 存在重复值，未创建唯一索引，请先在后台纠正编号';
        END IF;
    END
    $$;

-- 2. 排序键与兜底查询索引
CREATE INDEX IF NOT EXISTS idx_product_active_created  ON product (is_active, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_product_active_discount ON product (is_active, original_price DESC NULLS LAST, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_stock_sales     ON product (is_active, stock, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_category_active_sort    ON category (is_active, sort_order);

-- 3. 分类图标：统一成不带前缀的小写类名后缀（历史数据里 'fa-leaf' 与 'fas fa-leaf' 混着写），
--    与 CategoryServiceImpl#normalizeIcon 的入库口径一致，前台才能拼出正确的类名
UPDATE category
   SET icon = regexp_replace(regexp_replace(lower(btrim(icon)), '^(fas|far|fab|fa)[[:space:]]+', ''), '^fa-', ''),
       updated_at = now()
 WHERE icon IS NOT NULL
   AND (icon ~ '^(fas|far|fab|fa)' OR lower(btrim(icon)) LIKE 'fa-%');

UPDATE category
   SET icon = NULL, updated_at = now()
 WHERE btrim(coalesce(icon, '')) = '';

-- 4. 图标补种：只选 Font Awesome 4.7（前台）与 6.4（后台）里同名同形的类名，
--    带 -o 后缀的 v4 类名在 v6 里要靠 shim 才存在，所以一律避开
UPDATE category
   SET icon = CASE
                  WHEN name LIKE '%玫瑰%'   THEN 'heart'
                  WHEN name LIKE '%康乃馨%' THEN 'user'
                  WHEN name LIKE '%百合%'   THEN 'gift'
                  WHEN name LIKE '%郁金香%' THEN 'star'
                  WHEN name LIKE '%向日葵%' THEN 'bolt'
                  WHEN name LIKE '%满天星%' THEN 'circle'
                  WHEN name LIKE '%绿植%' OR name LIKE '%花器%' THEN 'leaf'
                  WHEN name LIKE '%开业%' OR name LIKE '%商务%' THEN 'briefcase'
                  ELSE 'leaf'
                  END,
       updated_at = now()
 WHERE icon IS NULL;

-- 5. 描述补种：分类瓦片第二行显示的是 description，为空时给一句能读懂的定位语
UPDATE category
   SET description = CASE
                         WHEN name LIKE '%玫瑰%'   THEN '爱与告白的固定表达，当日手打雾面包装'
                         WHEN name LIKE '%康乃馨%' THEN '感恩与问候，母亲节与探病的首选'
                         WHEN name LIKE '%百合%'   THEN '百年好合，婚礼与乔迁的经典花材'
                         WHEN name LIKE '%郁金香%' THEN '优雅克制的高级感，适合拜访与桌面'
                         WHEN name LIKE '%向日葵%' THEN '明亮有劲，毕业、开业与鼓励都合适'
                         WHEN name LIKE '%满天星%' THEN '清新浪漫的配花主角，也适合日常插瓶'
                         WHEN name LIKE '%系列%'   THEN '按花种归好的一束心意，可直接下单配送'
                         ELSE '当日到店鲜花，由花艺师现场搭配'
                         END,
       updated_at = now()
 WHERE description IS NULL OR btrim(description) = '';
