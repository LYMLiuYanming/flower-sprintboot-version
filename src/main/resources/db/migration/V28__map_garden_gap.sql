-- ============================================================================
-- V28：地图 / 天气 / 花田收尾批次（I01 / I02 / I15 / I16）
--   flower_origin.image_url  产地卡片的配图（I01 后台维护的三项能力里此前只有坐标与启停落了地）
--                            只存站内相对路径，前台 <img> 直接引用，外链与伪协议在服务层就被拒掉
--   flower_origin 名称唯一索引 后台保存按名称查重（findFirstByName），一旦库里出现同名节点，
--                            那次查询会抛 IncorrectResultSize 而不是给出友好提示，索引把这扇门关上
--   flower_origin 城市索引   自提页按城市筛选门店、就近推荐先取同城（I15），此前只有 (kind, sort_order)
-- 说明：V23 已执行，本批次一律 ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS；
--       回填只在 image_url IS NULL 时发生，重复执行不会覆盖后台改过的值，时间一律写 now()
-- ============================================================================

ALTER TABLE flower_origin ADD COLUMN IF NOT EXISTS image_url varchar(255);

-- 配图用站内已有的实拍图（见 docs/资产来源与授权.md），一类一图，宁缺勿假：
-- 分拨中心与门店没有实拍素材，就留空让卡片不占位，而不是拿商品图凑一张「门店照片」
UPDATE flower_origin SET image_url = CASE name
    WHEN '昆明斗南花卉基地' THEN '/img/photos/hero-rose.jpg'
    WHEN '广州岭南花卉基地' THEN '/img/photos/cat-mixed.jpg'
    WHEN '成都三圣乡花卉基地' THEN '/img/photos/products/tulip.svg'
    WHEN '新疆伊犁郁金香基地' THEN '/img/photos/cat-tulip.jpg'
    WHEN '河南洛阳牡丹基地' THEN '/img/photos/hero-carnation.jpg'
    WHEN '山东青州蝴蝶兰基地' THEN '/img/photos/hero-lily.jpg'
    END,
    updated_at = now()
WHERE kind = 'origin' AND image_url IS NULL
  AND name IN ('昆明斗南花卉基地', '广州岭南花卉基地', '成都三圣乡花卉基地',
               '新疆伊犁郁金香基地', '河南洛阳牡丹基地', '山东青州蝴蝶兰基地');

-- 同名节点会让后台「已有同名节点，请直接编辑」这个分支变成一次 500，先确认没有历史重名再建索引
CREATE UNIQUE INDEX IF NOT EXISTS uq_flower_origin_name ON flower_origin (name);

-- I15：/api/stores?city= 与就近推荐的同城优先都按城市过滤，停用节点不参与，所以建成 partial index
CREATE INDEX IF NOT EXISTS idx_flower_origin_city_active ON flower_origin (city) WHERE is_active = true;

-- I12：兑换记录面板按「我的花田券」核账，user_coupon.code 上没有可用索引（只有 coupon_id 组合索引）
CREATE INDEX IF NOT EXISTS idx_user_coupon_user_code ON user_coupon (user_id, code);
