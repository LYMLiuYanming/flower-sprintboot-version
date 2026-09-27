-- ============================================================================
-- V2：新增业务表（收货地址 / 商品收藏 / 订单评价）与既有表的新增列
-- 说明：本环境 dev profile 仍由 Hibernate 参与建表，脚本全部使用 IF [NOT] EXISTS，
--       因此无论先跑迁移还是先由 Hibernate 建表，结果一致且不报错。
--       受控环境（prod）以本目录脚本为唯一结构来源，ddl-auto=validate。
-- ============================================================================

DO $$
DECLARE
    has_user boolean := to_regclass('public."user"') IS NOT NULL;
BEGIN
    IF NOT has_user THEN
        RAISE NOTICE '核心表尚未建立（将由 JPA 初始化），跳过新增列部分';
        RETURN;
    END IF;

    -- 强制改密标记：管理员重置密码后置 true，用户改密成功即清除
    IF to_regclass('public."user"') IS NOT NULL THEN
        ALTER TABLE "user"
            ADD COLUMN IF NOT EXISTS must_change_password boolean NOT NULL DEFAULT false;
    END IF;

    -- 发货/物流与积分留痕
    IF to_regclass('public."order"') IS NOT NULL THEN
        ALTER TABLE "order"
            ADD COLUMN IF NOT EXISTS express_company varchar(50),
            ADD COLUMN IF NOT EXISTS express_no      varchar(60),
            ADD COLUMN IF NOT EXISTS ship_time       timestamp,
            ADD COLUMN IF NOT EXISTS finish_time     timestamp,
            ADD COLUMN IF NOT EXISTS cancel_reason   varchar(200),
            ADD COLUMN IF NOT EXISTS points_earned   integer NOT NULL DEFAULT 0,
            ADD COLUMN IF NOT EXISTS points_used     integer NOT NULL DEFAULT 0;
    END IF;

    CREATE TABLE IF NOT EXISTS address (
        id              uuid         NOT NULL,
        user_id         uuid         NOT NULL,
        receiver_name   varchar(50)  NOT NULL,
        receiver_phone  varchar(20)  NOT NULL,
        province        varchar(50),
        city            varchar(50),
        district        varchar(50),
        detail          varchar(255) NOT NULL,
        tag             varchar(20),
        is_default      boolean      NOT NULL DEFAULT false,
        created_at      timestamp    NOT NULL,
        updated_at      timestamp    NOT NULL,
        CONSTRAINT address_pkey PRIMARY KEY (id),
        CONSTRAINT fk_address_user FOREIGN KEY (user_id) REFERENCES "user" (id)
    );
    CREATE INDEX IF NOT EXISTS idx_address_user ON address (user_id, is_default);

    CREATE TABLE IF NOT EXISTS favorite (
        id          uuid NOT NULL,
        user_id     uuid NOT NULL,
        product_id  uuid NOT NULL,
        created_at  timestamp NOT NULL,
        CONSTRAINT favorite_pkey PRIMARY KEY (id),
        CONSTRAINT uk_favorite_user_product UNIQUE (user_id, product_id),
        CONSTRAINT fk_favorite_user FOREIGN KEY (user_id) REFERENCES "user" (id),
        CONSTRAINT fk_favorite_product FOREIGN KEY (product_id) REFERENCES product (id)
    );
    CREATE INDEX IF NOT EXISTS idx_favorite_user_created ON favorite (user_id, created_at);

    -- 评价落库后由服务层聚合回写 product.rating / review_count
    IF to_regclass('public.order_item') IS NOT NULL THEN
        CREATE TABLE IF NOT EXISTS review (
            id              uuid    NOT NULL,
            order_id        uuid    NOT NULL,
            order_item_id   uuid    NOT NULL,
            product_id      uuid    NOT NULL,
            user_id         uuid    NOT NULL,
            rating          integer NOT NULL,
            content         text,
            images          text,
            visible         boolean NOT NULL DEFAULT true,
            created_at      timestamp NOT NULL,
            CONSTRAINT review_pkey PRIMARY KEY (id),
            CONSTRAINT fk_review_order FOREIGN KEY (order_id) REFERENCES "order" (id),
            CONSTRAINT fk_review_order_item FOREIGN KEY (order_item_id) REFERENCES order_item (id),
            CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES product (id),
            CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES "user" (id)
        );
        CREATE INDEX IF NOT EXISTS idx_review_product_created ON review (product_id, created_at);
        CREATE INDEX IF NOT EXISTS idx_review_order ON review (order_id);
    END IF;
END $$;
