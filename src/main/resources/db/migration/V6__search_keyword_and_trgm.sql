-- ============================================================================
-- V6：站内搜索增强
--   1) search_keyword：搜索词累计表，驱动「热门搜索词」与输入联想（历史词在前端 localStorage）
--   2) pg_trgm + GIN 索引：为 LIKE '%kw%' 提供索引支撑（待办清单 B11 的评估结论，见文档）
-- 说明：pg_trgm 属于 contrib 扩展，个别环境可能未安装，故用 DO 块探测后再建，缺失时静默跳过。
-- ============================================================================

CREATE TABLE IF NOT EXISTS search_keyword (
    id               uuid         NOT NULL,
    keyword          varchar(50)  NOT NULL,
    search_count     integer      NOT NULL DEFAULT 1,
    last_searched_at timestamp    NOT NULL,
    CONSTRAINT search_keyword_pkey PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_search_keyword      ON search_keyword (keyword);
CREATE INDEX IF NOT EXISTS idx_search_keyword_count       ON search_keyword (search_count DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
        CREATE EXTENSION IF NOT EXISTS pg_trgm;
        -- 三元组索引：ILIKE '%x%' 与 similarity() 都可命中，替代全表顺序扫描
        CREATE INDEX IF NOT EXISTS idx_product_name_trgm  ON product USING gin (name gin_trgm_ops);
        CREATE INDEX IF NOT EXISTS idx_product_tags_trgm  ON product USING gin (tags gin_trgm_ops);
        RAISE NOTICE 'pg_trgm 已就绪，商品检索走 GIN 索引';
    ELSE
        RAISE NOTICE 'pg_trgm 不可用，跳过三元组索引（检索仍可用，只是退化为顺序扫描）';
    END IF;
END $$;
