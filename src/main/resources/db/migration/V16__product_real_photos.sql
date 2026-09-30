-- ============================================================================
-- V16：商品主图从扁平 SVG 插画换成棚拍实拍图
--   详情页/列表/购物车/订单快照都读 product.main_image 与 images，换地址即可全站生效；
--   SVG 仍留在 /img/products/ 作为兜底资源（新商品未配实拍图时不至于空图）。
--   粉玫瑰与蓝玫瑰是本项目生成的写实图，其余复用首页已在库的棚拍图，全部项目自持、无外链授权负担。
-- ============================================================================

UPDATE product SET main_image = '/img/photos/hero-rose.jpg',
       images = '/img/photos/hero-rose.jpg,/img/photos/story-florist.jpg'
    WHERE name = '红玫瑰花束';

UPDATE product SET main_image = '/img/photos/products/rose-pink.jpg',
       images = '/img/photos/products/rose-pink.jpg,/img/photos/story-florist.jpg'
    WHERE name = '粉玫瑰花束';

UPDATE product SET main_image = '/img/photos/products/rose-blue.jpg',
       images = '/img/photos/products/rose-blue.jpg,/img/photos/story-florist.jpg'
    WHERE name = '蓝玫瑰花束';

UPDATE product SET main_image = '/img/photos/hero-lily.jpg',
       images = '/img/photos/hero-lily.jpg,/img/photos/story-florist.jpg'
    WHERE name = '百合花束';

UPDATE product SET main_image = '/img/photos/hero-carnation.jpg',
       images = '/img/photos/hero-carnation.jpg,/img/photos/story-florist.jpg'
    WHERE name = '康乃馨花束';

UPDATE product SET main_image = '/img/photos/cat-sunflower.jpg',
       images = '/img/photos/cat-sunflower.jpg,/img/photos/cat-mixed.jpg'
    WHERE name = '向日葵花束';

UPDATE product SET main_image = '/img/photos/cat-tulip.jpg',
       images = '/img/photos/cat-tulip.jpg,/img/photos/story-florist.jpg'
    WHERE name = '郁金香花束';

UPDATE product SET main_image = '/img/photos/cat-mixed.jpg',
       images = '/img/photos/cat-mixed.jpg,/img/photos/story-florist.jpg'
    WHERE name = '满天星干花';
