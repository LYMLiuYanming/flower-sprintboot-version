-- ============================================================================
-- V3：种子图片本地化
-- 原种子数据全部指向 picsum.photos / unsplash 外链，断网或图床变更时前台白屏。
-- 本地静态图已生成于 static/img 下，这里把历史行的外链一次性改写为站内路径。
-- ============================================================================

UPDATE product SET main_image = '/img/products/' || m.local || '.svg'
FROM (VALUES
          ('ROSE-R11', 'rose-red'), ('ROSE-P19', 'rose-pink'), ('CAR-PINK', 'carnation'),
          ('LILY-WHITE', 'lily'), ('TULIP-MIX', 'tulip'), ('SUNFLOWER', 'sunflower'),
          ('BABYDRY', 'babysbreath'), ('ROSE-BLUE', 'rose-blue')
     ) AS m(code, local)
WHERE product.code = m.code
  AND (product.main_image LIKE 'http://picsum.%' OR product.main_image LIKE 'https://picsum.%'
    OR product.main_image LIKE 'http://unsplash.%' OR product.main_image LIKE 'https://unsplash.%'
    OR product.main_image LIKE '%images.unsplash.com%');

UPDATE product SET images = main_image || ',/img/placeholder.svg'
WHERE main_image LIKE '/img/products/%'
  AND (images IS NULL OR images LIKE 'http%');

-- 未能按编码对应的历史图床外链（仅 picsum / unsplash）落到站内占位图，
-- 运营在后台自行填写的其它外链不做改动
UPDATE product
SET main_image = '/img/placeholder.svg'
WHERE main_image ~* '^https?://(picsum\.(photos|seed)|[^/]*unsplash\.com)';

UPDATE product
SET images = NULL
WHERE images IS NOT NULL AND images LIKE 'http%picsum%';

UPDATE banner SET image_url = CASE title
                                  WHEN '情人节特惠' THEN '/img/banners/banner-valentine.svg'
                                  WHEN '新品上市' THEN '/img/banners/banner-new.svg'
                                  WHEN '母亲节感恩' THEN '/img/banners/banner-mother.svg'
                                  ELSE image_url END
WHERE image_url LIKE '%picsum.%' OR image_url LIKE '%unsplash.com%';

UPDATE banner SET image_url = '/img/placeholder.svg'
WHERE image_url ~* '^https?://(picsum\.(photos|seed)|[^/]*unsplash\.com)';
