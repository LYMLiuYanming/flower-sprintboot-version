-- ============================================================================
-- V17：商品内容维度补全（花语 / 养护 / 场景 / 副标题）与检索热点索引
--   这四个字段支撑详情页的「花语」「养护贴士」「适用场景」三段内容，
--   suitable_for 同时是列表页的场景筛选来源（A18）。
--   索引补建：列表页默认按上架 + 价格/销量排序，此前是全表扫描。
-- ============================================================================

ALTER TABLE product ADD COLUMN IF NOT EXISTS subtitle        varchar(120);
ALTER TABLE product ADD COLUMN IF NOT EXISTS care_tip        varchar(500);
ALTER TABLE product ADD COLUMN IF NOT EXISTS flower_language varchar(200);
ALTER TABLE product ADD COLUMN IF NOT EXISTS suitable_for    varchar(200);

CREATE INDEX IF NOT EXISTS idx_product_active_price  ON product (is_active, price);
CREATE INDEX IF NOT EXISTS idx_product_active_sales  ON product (is_active, sales_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_active_rating ON product (is_active, rating DESC, review_count DESC);
CREATE INDEX IF NOT EXISTS idx_product_code_lower    ON product (LOWER(code));

-- 内容回填：按分类给一版可直接展示的文案，后台可再改；已有值不覆盖
UPDATE product SET subtitle = COALESCE(subtitle, material || ' · 当日手作') WHERE material IS NOT NULL AND subtitle IS NULL;
UPDATE product SET care_tip = COALESCE(care_tip,
    '收到后斜剪根 2-3 厘米，深水醒花 1 小时；每 2 天换水并去除浸水叶片，夏季避阳、冬季防冻，花期约 5-7 天。') WHERE care_tip IS NULL;
UPDATE product SET flower_language = COALESCE(flower_language, CASE
    WHEN name LIKE '%玫瑰%' THEN '玫瑰：爱与告白，数量不同含义不同，11 枝一心一意，99 枝长相厮守。'
    WHEN name LIKE '%百合%' THEN '百合：百年好合、纯洁无瑕，婚礼与乔迁的经典花材。'
    WHEN name LIKE '%康乃馨%' THEN '康乃馨：母爱与感恩，母亲节的固定语言。'
    WHEN name LIKE '%向日葵%' THEN '向日葵：沉默的爱与信念，朝着光的方向。'
    WHEN name LIKE '%郁金香%' THEN '郁金香：优雅的告白，花色不同寓意各异。'
    WHEN name LIKE '%满天星%' THEN '满天星：甘做配角的思念，也代表清纯与关怀。'
    ELSE '花语：把说不出口的心意，交给一束花。' END)
    WHERE flower_language IS NULL;
UPDATE product SET suitable_for = COALESCE(suitable_for, CASE
    WHEN name LIKE '%玫瑰%' THEN '告白,纪念日,求婚,生日'
    WHEN name LIKE '%康乃馨%' THEN '母亲节,探病,感恩,长辈'
    WHEN name LIKE '%向日葵%' THEN '毕业,开业,鼓励,探病'
    WHEN name LIKE '%百合%' THEN '婚礼,乔迁,探病,长辈'
    WHEN name LIKE '%郁金香%' THEN '生日,拜访,商务,桌面'
    ELSE '商务,开业,家居,拜访' END)
    WHERE suitable_for IS NULL;
