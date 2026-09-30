-- ============================================================================
-- V22：评价与内容（F01–F16）的结构与内容底座
--   review       扩列：匿名发布（F02）、评价标签（F05）、买家追评与商家回复（F03/F04）、
--                后台审核留痕字段与 updated_at（F08/F09 重算时间戳）
--   notice       扩列：定时上下线时间窗（F11）、登录用户已读去重统计（F12）
--   notice_read  已读明细：一人一行，重复阅读只累加次数，是「已读人数/已读次数」的唯一来源
--   banner       扩列：跳转类型与目标（F13）、预览缩略图（F14）
--   article      花语/养护知识库（F15）：文章 + 分类 + 关联花材/商品，详情页侧栏按花材推荐（F16）
-- 约定：全部语句幂等（IF [NOT] EXISTS / WHERE NOT EXISTS），dev 下 Hibernate update 与
--       prod 下 validate 看到的结构一致；时间统一写 now()，与实体 CURRENT_TIMESTAMP 同值。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. review：评价维度扩展
--    图片沿用既有 images TEXT（逗号分隔站内路径），与 product.images 同口径，
--    不再拆 review_image 表——避免同一份数据两处落地后校验口径分叉。
-- ---------------------------------------------------------------------------
ALTER TABLE review
    ADD COLUMN IF NOT EXISTS is_anonymous    boolean     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS tags            varchar(200),
    ADD COLUMN IF NOT EXISTS append_content  text,
    ADD COLUMN IF NOT EXISTS append_images   text,
    ADD COLUMN IF NOT EXISTS append_at       timestamp,
    ADD COLUMN IF NOT EXISTS reply           text,
    ADD COLUMN IF NOT EXISTS reply_at        timestamp,
    ADD COLUMN IF NOT EXISTS reply_by        uuid,
    ADD COLUMN IF NOT EXISTS reply_by_name   varchar(50),
    ADD COLUMN IF NOT EXISTS hidden_reason   varchar(200),
    ADD COLUMN IF NOT EXISTS updated_at      timestamp   NOT NULL DEFAULT now();

-- 前台只看得到 visible 的评价，筛选/排序全部以 (product_id, visible) 打头
CREATE INDEX IF NOT EXISTS idx_review_visible_product ON review (product_id, visible, created_at DESC);
-- F07 防刷：按用户回溯短时间窗内的提交频次
CREATE INDEX IF NOT EXISTS idx_review_user_created    ON review (user_id, created_at);
-- F06 星级筛选
CREATE INDEX IF NOT EXISTS idx_review_product_rating  ON review (product_id, rating);
-- F06「只看有图」：部分索引，只覆盖真正带图的评价
CREATE INDEX IF NOT EXISTS idx_review_product_image   ON review (product_id) WHERE images IS NOT NULL AND images <> '';

COMMENT ON COLUMN review.tags IS '评价标签编码，逗号分隔，取值见 ReviewService#TAGS（F05）';
COMMENT ON COLUMN review.hidden_reason IS '后台隐藏/删除该评价时填写的理由，与 admin_audit_log 互为佐证（F08）';

-- ---------------------------------------------------------------------------
-- 2. notice：定时上下线 + 已读统计
--    publish_at/offline_at 为空分别表示「立即上线」「永不下线」，
--    上下线由查询侧时间窗判定，不依赖定时任务，改完即刻生效。
-- ---------------------------------------------------------------------------
ALTER TABLE notice
    ADD COLUMN IF NOT EXISTS publish_at      timestamp,
    ADD COLUMN IF NOT EXISTS offline_at      timestamp,
    ADD COLUMN IF NOT EXISTS cover_image     varchar(500),
    ADD COLUMN IF NOT EXISTS read_user_count integer     NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS read_times      integer     NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_notice_window ON notice (status, publish_at, offline_at);

-- view_count 是历史列（含匿名浏览），回填后两个口径才不会被误读成同一件事
UPDATE notice
   SET publish_at = created_at
 WHERE publish_at IS NULL;

CREATE TABLE IF NOT EXISTS notice_read (
    id            uuid      NOT NULL,
    notice_id     uuid      NOT NULL,
    user_id       uuid      NOT NULL,
    read_times    integer   NOT NULL DEFAULT 1,
    first_read_at timestamp NOT NULL,
    last_read_at  timestamp NOT NULL,
    CONSTRAINT notice_read_pkey PRIMARY KEY (id),
    CONSTRAINT uk_notice_read UNIQUE (notice_id, user_id),
    CONSTRAINT fk_notice_read_notice FOREIGN KEY (notice_id) REFERENCES notice (id) ON DELETE CASCADE,
    CONSTRAINT fk_notice_read_user   FOREIGN KEY (user_id)   REFERENCES "user" (id)
);

CREATE INDEX IF NOT EXISTS idx_notice_read_notice ON notice_read (notice_id, last_read_at DESC);

-- ---------------------------------------------------------------------------
-- 3. banner：跳转类型与缩略图
--    link_target 存字符串（商品/分类 UUID、页面路径），与既有 link_url 并存：
--    link_url 由服务端按类型解析后回填，老页面继续读 link_url 不受影响。
-- ---------------------------------------------------------------------------
ALTER TABLE banner
    ADD COLUMN IF NOT EXISTS link_type   varchar(20)  NOT NULL DEFAULT 'none',
    ADD COLUMN IF NOT EXISTS link_target varchar(100),
    ADD COLUMN IF NOT EXISTS thumb_url   varchar(500);

CREATE INDEX IF NOT EXISTS idx_banner_window ON banner (status, start_time, end_time, sort_order DESC);

-- 历史数据归位：从既有 link_url 反推跳转类型与目标，避免运营逐条重填
UPDATE banner
   SET link_type = CASE
                       WHEN link_url ~ '^/product/[0-9a-fA-F-]{36}'                        THEN 'product'
                       WHEN link_url ~* '^/products?\?categoryId=[0-9a-fA-F-]{36}'          THEN 'category'
                       WHEN link_url ~ '^https?://'                                        THEN 'url'
                       WHEN coalesce(btrim(link_url), '') = ''                             THEN 'none'
                       ELSE 'page'
                   END,
       link_target = CASE
                       WHEN link_url ~ '^/product/[0-9a-fA-F-]{36}'                        THEN substring(link_url from '([0-9a-fA-F-]{36})')
                       WHEN link_url ~* '^/products?\?categoryId=[0-9a-fA-F-]{36}'          THEN substring(link_url from 'categoryId=([0-9a-fA-F-]{36})')
                       ELSE NULL
                   END
 WHERE link_type = 'none'
   AND link_url IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. article：知识库内容模型（F15）
--    materials 是「花材关键词」逗号串，详情页按 product.material 分词命中（F16）；
--    related_product_ids 存 UUID 串，文章页据此带出可直接下单的花礼。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS article (
    id                  uuid          NOT NULL,
    title               varchar(200)  NOT NULL,
    summary             varchar(300),
    cover_image         varchar(500),
    content             text          NOT NULL,
    category            varchar(30)   NOT NULL DEFAULT 'care',
    tags                varchar(200),
    materials           varchar(300),
    related_product_ids varchar(600),
    status              varchar(20)   NOT NULL DEFAULT 'draft',
    publish_at          timestamp,
    offline_at          timestamp,
    view_count          integer       NOT NULL DEFAULT 0,
    is_top              boolean       NOT NULL DEFAULT false,
    sort_order          integer       NOT NULL DEFAULT 0,
    author_name         varchar(50),
    created_at          timestamp     NOT NULL,
    updated_at          timestamp     NOT NULL,
    CONSTRAINT article_pkey PRIMARY KEY (id),
    CONSTRAINT ck_article_category CHECK (category IN ('care', 'language', 'story', 'guide', 'festival')),
    CONSTRAINT ck_article_status   CHECK (status IN ('draft', 'published', 'offline'))
);

CREATE INDEX IF NOT EXISTS idx_article_list      ON article (status, category, publish_at DESC);
CREATE INDEX IF NOT EXISTS idx_article_top_sort  ON article (status, is_top DESC, sort_order DESC);

-- ---------------------------------------------------------------------------
-- 5. 知识库种子内容（F15/F16）
--    正文是真实养护结论与花语释义，按 HtmlSanitizer 白名单标签书写，
--    关联商品按商品编码取，编码不在库里时相关商品自动为空（不会写出假 UUID）。
--    守卫条件用 title，运营改名后也不会被脚本覆盖。
-- ---------------------------------------------------------------------------
INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000001',
       '玫瑰收到后怎么养：让花期从 3 天变成 8 天的五个动作',
       '斜剪根、深水醒花、去叶、换水、避风口——花艺师按门店实操写的玫瑰养护步骤',
       '/img/photos/hero-rose.jpg',
       '<p>玫瑰最怕的不是热，而是<strong>吸水断档</strong>。切花离枝后木质部导管里会进空气，一旦气栓堵住，花瓣几个小时就会发软下垂。下面五步就是把吸水重新打通。</p><h3>1. 先醒花再修剪</h3><p>拆掉外包装后，保留扎带，把花枝底部斜剪 2-3 厘米，然后整枝（花头除外）泡进清水 1 小时。深水带来的压强能帮导管排出气栓，这一步做完花瓣会重新硬挺。</p><h3>2. 去掉水位线以下的叶子</h3><blockquote>泡在水里的叶子会在一夜之间腐烂发臭，把细菌送到整瓶水中。</blockquote><p>只保留花头下方 2-3 片叶子做层次，其余摘净。</p><h3>3. 每天换水，剪根 1 厘米</h3><p>换水时顺手再剪一次根，切口是新的吸水口。附赠的营养剂按半包放就够，浓度过高反而烧根。</p><h3>4. 位置决定花期</h3><p>离空调出风口、果盘、阳光直射窗台远一点。成熟的水果会释放乙烯，玫瑰对乙烯特别敏感，花瓣会加速外翻脱落。</p><h3>5. 抢救垂头玫瑰</h3><p>用报纸把花头包紧，斜剪根后深水浸泡 2 小时，靠纸的支撑让花颈重新挺起来；仍无改善说明导管已彻底堵塞，建议拆头做干花或浮水插花。</p>',
       'care', '养护,瓶插期,鲜花处理', '玫瑰',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('ROSE-R11', 'ROSE-P19', 'ROSE-BLUE')),
       'published', now(), 0, true, 60, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '玫瑰收到后怎么养：让花期从 3 天变成 8 天的五个动作');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000002',
       '康乃馨的花语与送法：为什么母亲节之外也值得送',
       '粉色感恩、白色怀念、红色敬爱——康乃馨的颜色语义与搭配分寸',
       '/img/photos/hero-carnation.jpg',
       '<p>康乃馨的花语核心是<strong>「感恩与牵挂」</strong>，而不是「爱情」。它的朵型饱满、瓶插期长达 10-14 天，是所有切花里最耐放的主流花材之一。</p><h3>颜色语义</h3><ul><li>粉色：感念与温柔，最常见的母亲节选择</li><li>红色：敬爱与赞许，适合送师长、领导</li><li>白色：怀念与纯粹，多用于追思</li><li>复色镶边：活泼，适合探病与生日</li></ul><h3>搭配分寸</h3><p>单色 11 支表达郑重；与满天星、尤加利搭配会柔化边界，适合同事、朋友之间。探病避免纯白配纯绿，加一支粉色或香槟色更像问候。</p><blockquote>给长辈送花，宁可花材耐放、颜色稳定，也不要为了新奇选当天就蔫的品种。</blockquote>',
       'language', '花语,母亲节,送礼', '康乃馨,满天星',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('CAR-PINK', 'BABYDRY')),
       'published', now(), 0, false, 50, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '康乃馨的花语与送法：为什么母亲节之外也值得送');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000003',
       '百合开花全过程：从铁蛋到满屋香，中间该做什么',
       '百合花苞硬得像子弹不用慌，教你把它养到逐朵打开且不染裙摆',
       '/img/photos/hero-lily.jpg',
       '<p>百合到货时花苞硬邦邦、像一颗颗铁蛋，这是<strong>正常状态</strong>。开放顺序是从下往上，最下面一朵先开，上面依次跟进，整束能撑 7-12 天。</p><h3>到货处理</h3><ul><li>斜剪根 2-3 厘米，去除浸泡水位以下的叶子</li><li>清水加营养剂，放在阴凉处，不要晒</li><li>只剥最外层的破损护瓣，别撕整片</li></ul><h3>开花后必做的一件事</h3><p>花瓣刚展开时，用纸巾把花蕊（带花粉的花药）摘掉。花粉落到裙子、桌布、花瓣上都极难清洗，还会缩短瓶插期。有宠物或花粉敏感的家庭建议戴指套操作。</p><h3>香味管理</h3><blockquote>东方百合香气浓，卧室和会议室慎放；想淡一点就把它挪到通风处、离人远一点。</blockquote>',
       'care', '养护,百合,花粉', '百合',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('LILY-WHITE')),
       'published', now(), 0, false, 45, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '百合开花全过程：从铁蛋到满屋香，中间该做什么');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000004',
       '郁金香会接着长：买回来「变高变弯」不是不新鲜',
       '郁金香切花离枝后仍会生长，理解了这一点就不会以为买到了次品',
       NULL,
       '<p>郁金香是所有常见切花里唯一<strong>采摘后还继续生长</strong>的品种。瓶插期间它会再长 2-5 厘米，花头会因为向光性朝窗户方向弯曲，还会在花苞里继续开放、最终散开成碗状。</p><h3>怎么办</h3><ul><li>水位只留 5-8 厘米，水多会让茎继续徒长、软到撑不住花头</li><li>用不透光的细颈瓶，或把花束扎紧固定形态</li><li>每隔半天转一次瓶口方向，让弯曲均匀而不是单边倒</li></ul><h3>什么时候该收尾</h3><p>花瓣完全摊平、边缘开始外卷时，把它取出做干花倒挂，仍能保留颜色。这不是养护失误，是郁金香的自然生命周期。</p>',
       'care', '养护,花材特性', '郁金香',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('TULIP-MIX')),
       'published', now(), 0, false, 40, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '郁金香会接着长：买回来「变高变弯」不是不新鲜');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000005',
       '开业、商务与贺礼：送花不出错的三条场合规则',
       '商务场合看体量与颜色，庆典看站位与高度，探病看气味与花粉',
       '/img/photos/moment-business.jpg',
       '<p>商务与庆典送花，对方收到的不是「一束花」，而是摆在门口能不能撑住场面的<strong>体量</strong>。</p><h3>开业贺礼</h3><ul><li>优先高架空花篮或大抱抱桶，站在 3 米外仍看得清</li><li>颜色取红、金、香槟，避开纯白纯绿的悼念配色</li><li>条幅写「生意兴隆」而不是「百年好合」，贺卡落款留公司名</li></ul><h3>会议与桌花</h3><p>高度控制在视线以下（30-40 厘米），香气选淡的，洋桔梗、小菊、满天星比东方百合更合适。</p><h3>探病</h3><blockquote>病房通常禁止浓香与花粉多的花材，向日葵、康乃馨、满天星这类稳妥；数量按 6-9 支的小束送，不打扰同病房。</blockquote>',
       'guide', '场合,商务,开业', '向日葵,康乃馨,满天星',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('SUNFLOWER', 'CAR-PINK', 'BABYDRY')),
       'published', now(), 0, false, 35, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '开业、商务与贺礼：送花不出错的三条场合规则');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000006',
       '向日葵的朝向与寓意：为什么毕业季它比玫瑰更合适',
       '向日葵花语是「信念与光辉」，送给正在往前走的人比送爱情更贴切',
       NULL,
       '<p>向日葵的花语核心是<strong>「信念、光辉、忠诚」</strong>，在中国式送礼语境里更接近「祝你去更亮的地方」，因此毕业、升职、康复、开业都比玫瑰合适。</p><h3>挑选</h3><ul><li>看花盘：中心管状花未完全展开的更新鲜，能再开几天</li><li>看花瓣：边缘无褐斑、不脱落</li><li>看茎秆：粗短挺直的耐运，细长发软的容易垂头</li></ul><h3>摆放</h3><p>切开的向日葵已失去向光性转动能力，朝哪摆都不会自己转。放光线明亮的地方颜色更饱和，水位 10 厘米左右，每天换水。</p>',
       'language', '花语,毕业,送礼', '向日葵,满天星',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('SUNFLOWER', 'BABYDRY')),
       'published', now(), 0, false, 30, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '向日葵的朝向与寓意：为什么毕业季它比玫瑰更合适');

INSERT INTO article (id, title, summary, cover_image, content, category, tags, materials,
                     related_product_ids, status, publish_at, view_count, is_top, sort_order,
                     author_name, created_at, updated_at)
SELECT 'b1000000-0000-0000-0000-000000000007',
       '鲜花为什么要在下午 3 点后采收：一朵花的采后生理',
       '从斗南的含水量曲线，讲清为什么同日到达的花会有不同状态',
       '/img/photos/story-florist.jpg',
       '<p>云南斗南的采收窗口在下午到夜间，这不是习惯，而是采后生理：白天植株蒸腾强、体内有气泡，此时剪切容易让导管进气；夜间气温低、花茎含水量高，剪切后吸水连续，瓶插期更长。</p><h3>预冷比运输更快</h3><ul><li>采后 2 小时内进预冷库降到 2-4℃，让呼吸速率降下来</li><li>带花苞的品种需要保鲜液做碳水化合物补充，否则开放到一半就停住</li><li>干线运输全程 0-4℃，中途断冷一次，瓶插期少两天</li></ul><blockquote>所以同一批花，到货当天状态有差异是冷链环节决定的，不是花农「挑次品」。</blockquote><h3>门店端</h3><p>到店后先深水醒花再入库，上架前去除失水外瓣，顾客拿到的才是一整天里状态最好的那一束。</p>',
       'story', '产地,冷链,采后', '玫瑰,百合,康乃馨',
       (SELECT string_agg(id::text, ',' ORDER BY code) FROM product WHERE code IN ('ROSE-R11', 'LILY-WHITE', 'CAR-PINK')),
       'published', now(), 0, false, 25, '花艺组', now(), now()
WHERE NOT EXISTS (SELECT 1 FROM article a WHERE a.title = '鲜花为什么要在下午 3 点后采收：一朵花的采后生理');
