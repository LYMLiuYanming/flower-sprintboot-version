-- ============================================================================
-- V9：分类树归位 + 商品归类纠错
--   背景：种子数据里「玫瑰花 / 百合花」与「玫瑰系列 / 百合系列」是两套平行分类，
--         且多个商品挂错分类（百合花束挂在向日葵下、粉玫瑰挂在康乃馨下…），
--         导致「玫瑰系列」的分类专享券覆盖不到挂在「玫瑰花」下的蓝玫瑰花束。
--   处理：把花种分类收编为系列分类的子分类（父=系列、子=花种），再把商品指回正确分类。
--   配套：分类筛选与分类券均按「本分类 + 直接子分类」取数（CategoryRepository#idsWithChildren）。
-- 说明：全部语句按名称定位、可重复执行。
-- ============================================================================

UPDATE category ch
   SET parent_id = pa.id, updated_at = CURRENT_TIMESTAMP
  FROM category pa
 WHERE ch.name = '玫瑰花'
   AND pa.name = '玫瑰系列'
   AND ch.parent_id IS NULL
   AND ch.id <> pa.id;

UPDATE category ch
   SET parent_id = pa.id, updated_at = CURRENT_TIMESTAMP
  FROM category pa
 WHERE ch.name = '百合花'
   AND pa.name = '百合系列'
   AND ch.parent_id IS NULL
   AND ch.id <> pa.id;

-- 商品归类纠正：按商品名指向其真正的分类
UPDATE product p
   SET category_id = c.id, updated_at = CURRENT_TIMESTAMP
  FROM category c
 WHERE p.name = '百合花束'
   AND c.name = '百合花'
   AND p.category_id IS DISTINCT FROM c.id;

UPDATE product p
   SET category_id = c.id, updated_at = CURRENT_TIMESTAMP
  FROM category c
 WHERE p.name IN ('红玫瑰花束', '粉玫瑰花束', '蓝玫瑰花束')
   AND c.name = '玫瑰花'
   AND p.category_id IS DISTINCT FROM c.id;

UPDATE product p
   SET category_id = c.id, updated_at = CURRENT_TIMESTAMP
  FROM category c
 WHERE p.name = '满天星干花'
   AND c.name = '满天星'
   AND p.category_id IS DISTINCT FROM c.id;

UPDATE product p
   SET category_id = c.id, updated_at = CURRENT_TIMESTAMP
  FROM category c
 WHERE p.name = '郁金香花束'
   AND c.name = '郁金香'
   AND p.category_id IS DISTINCT FROM c.id;
