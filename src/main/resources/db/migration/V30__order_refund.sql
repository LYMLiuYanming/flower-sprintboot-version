-- ============================================================================
-- V30：订单售后闭环（C06/C07 取消原因、C19/C20 退款申请与审核、C18 自动确认收货）
--   order.refund_status      退款单状态镜像（pending/reviewing/refunded/rejected/cancelled），
--                            列表筛选与详情页徽标都要按它判定，不回填历史单即「无退款申请」
--   order.refund_amount      申请退给用户的金额，口径固定为实付 pay_amount，页面不再另算
--   order.rollback_at        资源回退（库存/销量/积分/券/运力）完成时间：一条
--                            「WHERE rollback_at IS NULL」的条件更新做幂等闸门，
--                            取消与退款两条路径同时进来也只会回退一次
--   order.deliver_time       签收时间（用户确认或 15 天自动确认），C26 的「完成 N 天后引导评价」用它
--   order_refund             退款申请与审核留痕：一张单可被驳回后重新申请，故不用唯一约束锁死
-- 全部语句幂等（IF NOT EXISTS / now() 默认值），可重复执行。
-- ============================================================================

ALTER TABLE "order" ADD COLUMN IF NOT EXISTS refund_status      varchar(20);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS refund_amount      numeric(10,2);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS refund_reason      varchar(200);
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS refund_requested_at timestamp;
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS refunded_at        timestamp;
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS rollback_at        timestamp;
ALTER TABLE "order" ADD COLUMN IF NOT EXISTS deliver_time       timestamp;

CREATE TABLE IF NOT EXISTS order_refund (
    id          uuid          NOT NULL,
    order_id    uuid          NOT NULL,
    user_id     uuid          NOT NULL,
    amount      numeric(10,2) NOT NULL,
    reason      varchar(200),
    detail      varchar(500),
    status      varchar(20)   NOT NULL,
    reviewer    varchar(60),
    review_note varchar(200),
    accepted_at timestamp,
    settled_at  timestamp,
    created_at  timestamp     NOT NULL DEFAULT now(),
    updated_at  timestamp     NOT NULL DEFAULT now(),
    CONSTRAINT order_refund_pkey PRIMARY KEY (id),
    CONSTRAINT fk_order_refund_order FOREIGN KEY (order_id) REFERENCES "order" (id)
);

-- 一笔订单同时只允许一张在途退款单：并发双击提交时第二条 INSERT 直接撞唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS uk_order_refund_open
    ON order_refund (order_id) WHERE status IN ('pending', 'reviewing');
CREATE INDEX IF NOT EXISTS idx_order_refund_order  ON order_refund (order_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_refund_status ON order_refund (status, created_at);

-- 我的订单列表的组合筛选与排序（C01/C03）：user_id 打头，时间/金额排序都走索引
CREATE INDEX IF NOT EXISTS idx_order_user_created ON "order" (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_user_amount  ON "order" (user_id, pay_amount DESC);
-- 15 天自动确认收货的扫描口径：只看已发货且有发货时间的单
CREATE INDEX IF NOT EXISTS idx_order_shipped_scan ON "order" (ship_time) WHERE status = 'shipped';
