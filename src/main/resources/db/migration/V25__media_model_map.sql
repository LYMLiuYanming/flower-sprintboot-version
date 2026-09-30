-- ============================================================================
-- V25：立体展台的「品类 → GLB 模型」映射表（J01）
--   media_model_map.match_type  命中方式：category 按分类名精确命中 / keyword 按商品名·花材关键词命中 / default 全局兜底
--   media_model_map.match_key   命中键；default 行固定为 '*'，每个时刻只允许一条生效兜底
--   media_model_map.is_shared   true 表示这并不为该品类单独建模，只是借通用花束示意体量与层次；
--                               前台会把它写成「通用模型示意」而不是假装这是本款花的三维还原
--   caption/preset/spin_speed   展台标题、默认取景与转速，让运营不调代码就能改展示口径
-- 兜底策略（前端与接口都按这个顺序）：keyword 命中 → category 命中 → default 行 → 都没有则不请求 GLB，
-- 展台自动回落实拍图 2D 图集。因此删掉 default 行就能整体关掉立体视图，不需要改任何代码。
-- 说明：目前仓库只有 1 个 CC0 花束模型，下面几条品类行都指向同一个文件（is_shared = true）。
--       这不是「多模型」，只是把映射链路先跑通；后续往 static/models/ 投放新的 CC0 GLB 后，
--       照抄一行 INSERT 换掉 model_url 与 is_shared 即可，接口与页面都不用动。
-- ============================================================================

CREATE TABLE IF NOT EXISTS media_model_map (
    id         uuid          NOT NULL,
    match_type varchar(16)   NOT NULL DEFAULT 'default',
    match_key  varchar(60)   NOT NULL,
    model_url  varchar(200)  NOT NULL,
    label      varchar(60),
    caption    varchar(200),
    preset     varchar(24)   NOT NULL DEFAULT 'full',
    spin_speed numeric(4, 2) NOT NULL DEFAULT 0.32,
    is_shared  boolean       NOT NULL DEFAULT false,
    sort_order integer       NOT NULL DEFAULT 0,
    is_active  boolean       NOT NULL DEFAULT true,
    created_at timestamp     NOT NULL,
    updated_at timestamp     NOT NULL,
    CONSTRAINT media_model_map_pkey PRIMARY KEY (id),
    CONSTRAINT ck_media_model_map_match CHECK (match_type IN ('category', 'keyword', 'default')),
    CONSTRAINT uk_media_model_map_match UNIQUE (match_type, match_key)
);

CREATE INDEX IF NOT EXISTS idx_media_model_map_active
    ON media_model_map (match_type, is_active, sort_order);

-- 通用兜底：任何没配到专属模型的品类都用这束 CC0 花束示意，标题与说明写清楚是示意
INSERT INTO media_model_map (id, match_type, match_key, model_url, label, caption, preset, spin_speed,
                             is_shared, sort_order, is_active, created_at, updated_at)
SELECT 'f0000000-0000-0000-0000-000000000001', 'default', '*', '/models/GlassVaseFlowers.glb',
       '立体展台', '公共领域（CC0）通用花束模型，用于感受体量、层次与花型；本款花的实际花材与开放度以门店当日为准。',
       'full', 0.32, true, 900, true, now(), now()
WHERE NOT EXISTS (SELECT 1 FROM media_model_map WHERE match_type = 'default' AND match_key = '*');

-- 品类行：同一份 GLB 在若干在售品类下的显式登记，命中后走品类文案（is_shared 仍为 true）
INSERT INTO media_model_map (id, match_type, match_key, model_url, label, caption, preset, spin_speed,
                             is_shared, sort_order, is_active, created_at, updated_at)
SELECT seed.id, 'category', seed.key, '/models/GlassVaseFlowers.glb',
       seed.label, seed.caption, seed.preset, 0.32, true, seed.ord, true, now(), now()
FROM (VALUES
          ('f0000000-0000-0000-0000-000000000011'::uuid, '玫瑰花', '立体展台 · 玫瑰',
           '玫瑰花瓣的层数与开放度在三维视图里最容易看清，模型为通用花束示意。', 'head', 11),
          ('f0000000-0000-0000-0000-000000000012'::uuid, '百合花', '立体展台 · 百合',
           '百合花型偏大，用通用花束感受整体轮廓，花瓣纹理请看实拍图集。', 'full', 12),
          ('f0000000-0000-0000-0000-000000000013'::uuid, '郁金香', '立体展台 · 郁金香',
           '郁金香瓶插期会继续生长，三维视图按采收当日形态呈现，仅作示意。', 'full', 13),
          ('f0000000-0000-0000-0000-000000000014'::uuid, '满天星', '立体展台 · 满天星',
           '满天星以量取胜，通用模型的配花部分最接近其实物观感。', 'leaf', 14)
     ) AS seed(id, key, label, caption, preset, ord)
WHERE NOT EXISTS (SELECT 1 FROM media_model_map m WHERE m.match_type = 'category' AND m.match_key = seed.key);

-- 关键词行留待真实品类专属模型：例如下面这行是「花盒/抱抱桶」到货后照抄的写法，暂不启用
-- INSERT INTO media_model_map (id, match_type, match_key, model_url, label, caption, preset, spin_speed,
--                              is_shared, sort_order, is_active, created_at, updated_at)
-- SELECT 'f0000000-0000-0000-0000-000000000101', 'keyword', '抱抱桶', '/models/FlowerBox.glb',
--        '立体展台 · 抱抱桶', '本品为独立建模。', 'vase', 0.28, false, 10, true, now(), now()
-- WHERE NOT EXISTS (SELECT 1 FROM media_model_map WHERE match_type = 'keyword' AND match_key = '抱抱桶');
