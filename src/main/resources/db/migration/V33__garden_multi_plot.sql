-- ============================================================================
-- V33：花田「多块地 + 兑换/转赠」的数据库层收口（I11 / I12 / I13）
--   uq_plot_exchange_redeem   同一块地只能有一条 redeem 流水：服务端的 claimMature 已经是
--                             mature→redeemed 的条件 UPDATE，但两个请求可能在同一瞬间都读到 mature，
--                             索引把「一束花兑出两张券」这条路彻底堵死
--   uq_plot_exchange_pending  同一块地同时只允许一条 pending 转赠：giftConditional 要求地块处于 mature，
--                             而「送出→婉拒→再送出」会留下多行历史，只有 pending 这一条必须是唯一的
--   idx_plot_exchange_coupon  兑换记录面板按 user_coupon_id 反查券包当前状态（I12 现读不回写），
--                             此前只有 (user_id, created_at) 与 (state, created_at)，反查会走全表
-- 说明：
--   1) 多块地的核心约束早在 V23 就重建过了——原 uq_flower_plot_growing 是「一人只有一条 growing」，
--      会员开第二块地必然撞索引，所以那一版把它换成 uq_flower_plot_growing_slot (user_id, slot_no)
--      WHERE status='growing'，本文件不重复建，只补 plot_exchange 这一层。
--   2) 「第二块地需会员」不写在数据库里：它取决于 user.member_level，是会随续费变化的外部事实，
--      用触发器锁死会让降级用户的历史地块变成脏数据，所以只在 GardenPolicy.canOpenSlot 判定。
--   3) 全部 CREATE ... IF NOT EXISTS，时间写 now()，重复执行不报错也不产生第二行。
-- ============================================================================

-- 先清掉历史脏数据里可能存在的「同块地两条 pending」：把更早的那些按 canceled 收尾，
-- 否则下面的唯一索引会直接建不起来（canceled 的退回语义与婉拒一致，不影响券账，pending 的券本就未发出）
UPDATE plot_exchange
SET state = 'canceled', updated_at = now()
WHERE state = 'pending'
  AND id NOT IN (
      SELECT keep.id FROM (
          SELECT DISTINCT ON (plot_id) id FROM plot_exchange WHERE state = 'pending' ORDER BY plot_id, created_at DESC
      ) keep
  );

-- 流水收尾后把地也还回去：服务端的 cancelGift/declineGift 都是「plot 回 mature + 券没发过」，
-- 只改流水不改地块会留下一株「等确认但其实没人等」的花，撤回按钮会点不出东西
UPDATE flower_plot p
SET gift_state = 'canceled', status = 'mature', updated_at = now()
WHERE p.gift_state = 'pending'
  AND NOT EXISTS (SELECT 1 FROM plot_exchange e WHERE e.plot_id = p.id AND e.state = 'pending');

CREATE UNIQUE INDEX IF NOT EXISTS uq_plot_exchange_redeem
    ON plot_exchange (plot_id) WHERE type = 'redeem';

CREATE UNIQUE INDEX IF NOT EXISTS uq_plot_exchange_pending
    ON plot_exchange (plot_id) WHERE state = 'pending';

CREATE INDEX IF NOT EXISTS idx_plot_exchange_coupon
    ON plot_exchange (user_coupon_id) WHERE user_coupon_id IS NOT NULL;

-- I13：收礼方「待我确认」的列表按 gift_user_id + pending 过滤再按 updated_at 倒序取前若干条，
-- V23 的 (gift_user_id, gift_state) 索引只能完成过滤，排序仍要落一次 filesort；
-- 这里补一条把顺序也带上的 partial index，待确认量随活动涨起来才不会拖慢花田首屏
CREATE INDEX IF NOT EXISTS idx_flower_plot_gift_pending
    ON flower_plot (gift_user_id, updated_at) WHERE gift_state = 'pending';
