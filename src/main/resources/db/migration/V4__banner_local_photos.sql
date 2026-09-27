-- V4：首页轮播改用本地实拍图（/img/banners/*.svg 内嵌烘焙文字，与新视觉体系不符）
UPDATE banner
SET image_url = CASE title
                    WHEN '情人节特惠' THEN '/img/photos/hero-rose.jpg'
                    WHEN '新品上市' THEN '/img/photos/hero-lily.jpg'
                    WHEN '母亲节感恩' THEN '/img/photos/hero-carnation.jpg'
                    ELSE image_url END
WHERE image_url LIKE '/img/banners/%'
   OR image_url LIKE '%picsum.%'
   OR image_url LIKE '%unsplash.com%';

-- 兜底：任何仍指向外链的横幅统一换成本地图
UPDATE banner
SET image_url = '/img/photos/cat-mixed.jpg'
WHERE image_url ~* '^https?://(picsum\.(photos|seed)|[^/]*unsplash\.com)';
