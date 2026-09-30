-- ============================================================================
-- V34 · L 组（安全/性能/工程）索引补建
--
-- 口径：
--   1) 全部 IF NOT EXISTS + DO 块守卫，重复执行不报错、不改数据；
--   2) 只补「已有索引覆盖不到」的热点访问路径，不重复建同列同序的索引
--      （order/user_coupon/review/notice/banner/article 的主干索引在 V1、V5、V7、V17、V21、V22、V24、V29 已建齐）；
--   3) 大量使用**部分索引**：这些表的绝大多数行是「已完成/已使用/正常返回」，
--      扫描类任务与后台筛选只碰其中很小一撮（未付款、待处理、失败留痕），
--      部分索引只索引那一撮，体积小、更新开销低、命中率高，比给全表建复合索引划算得多；
--   4) 三列以上的复合索引把「等值列」放前面、「排序列」放最后，这样 Filter + Sort 走同一个索引，
--      否则后台列表会在正确取到行之后再做一次全量 sort。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 后台操作审计：模块 + 动作 + 时间 的组合筛选（L02 之后审计条目更多，这里先把读侧撑住）
--    现有 idx_audit_module(module, created_at) 少了 action 这一层，
--    运营「只看订单的发货记录」这类筛选会退化成按模块扫完再过滤。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_audit_module_action_time
    ON admin_audit_log (module, action, created_at DESC);

-- 「只看失败」是排查用最高频的一筛：失败行是极少数，用部分索引只存它们
CREATE INDEX IF NOT EXISTS idx_audit_bad_result
    ON admin_audit_log (created_at DESC)
    WHERE result_code IS NOT NULL AND result_code <> 200;

-- 操作人按姓名模糊查（人名不是等值条件，btree 帮不上），只在 pg_trgm 可用时建
DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
            -- 只给 uri 建，不给 detail 建：detail 是 2000 字的请求摘要，
            -- 它进 trigram 索引会让每一次后台写操作多写几十倍的索引项，
            -- 换来的是「少扫一段本来也不大的时间窗」，不划算（当前量级顺序扫描在毫秒级）。
            CREATE INDEX IF NOT EXISTS idx_audit_uri_trgm ON admin_audit_log USING gin (uri gin_trgm_ops);
        ELSE
            RAISE NOTICE 'pg_trgm 未安装，跳过审计 uri 模糊查询索引（V6 的探测逻辑同样会跳过）';
        END IF;
    END
$$;

-- ---------------------------------------------------------------------------
-- 2. 订单：「我的订单」按状态筛选 + 时间倒序
--    idx_order_user_status(user_id, status) 取行是对的，但页面固定 ORDER BY created_at DESC，
--    少这一层排序就会每次排序；加上第三列后 Filter 与 Sort 同一个索引搞定。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_order_user_status_time
    ON "order" (user_id, status, created_at DESC);

-- 超时未付款关单扫描：每 60 秒跑一次，条件恒为 status='pending' AND pay_time IS NULL。
-- 全站绝大多数订单都已付款或已取消，部分索引只覆盖待扫描那一小撮，扫描成本与订单总量脱钩。
CREATE INDEX IF NOT EXISTS idx_order_pay_timeout_scan
    ON "order" (created_at)
    WHERE status = 'pending' AND pay_time IS NULL;

-- 后台按收货人姓名/手机号找单（客服场景）。等值与手机号前缀查已有唯一约束帮忙，
-- 中段模糊只能靠 trigram；只在扩展可用时建。
DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
            CREATE INDEX IF NOT EXISTS idx_order_receiver_name_trgm
                ON "order" USING gin (receiver_name gin_trgm_ops);
            CREATE INDEX IF NOT EXISTS idx_order_receiver_phone_trgm
                ON "order" USING gin (receiver_phone gin_trgm_ops);
        END IF;
    END
$$;

-- ---------------------------------------------------------------------------
-- 3. 后台用户列表：状态/等级筛选 + 注册时间排序，以及关键词模糊查
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_user_status_created
    ON "user" (status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_level_created
    ON "user" (member_level, created_at DESC);

DO
$$
    BEGIN
        IF EXISTS(SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
            -- 表达式索引：后台的关键词是一次 LIKE 同时命中账号与姓名，
            -- 分两个索引会变成 BitmapOr + 回表两次，合成一个表达式反而更省
            CREATE INDEX IF NOT EXISTS idx_user_keyword_trgm
                ON "user" USING gin ((username || ' ' || full_name) gin_trgm_ops);
        END IF;
    END
$$;

-- ---------------------------------------------------------------------------
-- 4. 券包：「我的券」按领取时间倒序（idx_user_coupon_user 的第三列是 expire_at，
--    只能用于到期提醒那条路径，钱包页排序用不上）
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_user_coupon_wallet
    ON user_coupon (user_id, status, received_at DESC);

-- ---------------------------------------------------------------------------
-- 5. 评价：后台「待处理」队列只看被隐藏的那几条。
--    隐藏行只占极小比例，用部分索引服务这一侧就够；
--    前台 visible 那条路径 V22 的 idx_review_visible_product / idx_review_visible_created 已经覆盖，不再重复建。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_review_hidden_time
    ON review (created_at DESC)
    WHERE visible = FALSE;

-- ---------------------------------------------------------------------------
-- 6. 下单幂等凭证：签发时会顺手清理过期行（DELETE ... WHERE created_at < ?），
--    现有 idx_idempotency_token_created 已覆盖；这里只补「按人查在途凭证」的一条。
-- ---------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_idempotency_token_user
    ON idempotency_token (user_id, created_at DESC);
