-- ============================================================================
-- V47：消息通知中心（U01/U07/U08/U20/U21/U23）
--   user_message          站内信：类型 trade/marketing/system/ticket + 跳转 + 已读状态
--                         dedup_key 唯一索引是「重复请求不双发」的最终防线——事件可能因
--                         重试/并发被投递两次，撞唯一索引的那条直接丢弃
--   message_template      模板与变量槽（U07）：缺参时整条不落库，由服务端判定
--   notification_pref     类别 × 渠道矩阵（U08）：短信/邮件默认关闭且服务端永不外呼
--   notification_profile  免打扰时段（U09）：默认 22:00-08:00，跨午夜由 NotificationPolicy 判定
--   scheduled_message     延时消息（U20/U21）：status='pending'→'sending' 条件 UPDATE 抢占
--   message_guard_event   跳转地址越权计数（U05）：外链/javascript: 拒绝入库并留痕
--   broadcast_campaign    后台群发台账（U23）：发送/跳过/失败分类计数
--   message_archive_stat  90 天清理前的归档计数（U10）
-- 约定：全部语句幂等（IF NOT EXISTS / WHERE NOT EXISTS），时间统一 now()，可重复执行。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. user_message：站内信主体
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_message (
    id            uuid         NOT NULL,
    user_id       uuid         NOT NULL,
    category      varchar(20)  NOT NULL,
    title         varchar(200) NOT NULL,
    content       text,
    link_url      varchar(500),
    biz_type      varchar(40),
    biz_id        varchar(64),
    template_code varchar(40),
    -- 触达幂等键：同一业务事件重复发布时只有第一条能落库
    dedup_key     varchar(140),
    is_read       boolean      NOT NULL DEFAULT false,
    read_at       timestamp,
    created_at    timestamp    NOT NULL DEFAULT now(),
    updated_at    timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT user_message_pkey PRIMARY KEY (id),
    CONSTRAINT fk_user_message_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT ck_user_message_category CHECK (category IN ('trade', 'marketing', 'system', 'ticket'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_message_dedup ON user_message (dedup_key);
-- U03/U04：未读角标与列表首屏都按 (user_id, is_read, created_at) 打头
CREATE INDEX IF NOT EXISTS idx_user_message_unread    ON user_message (user_id, is_read, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_user_message_category  ON user_message (user_id, category, created_at DESC);
-- U10 保留期清理按创建时间扫描，避免按用户逐行删
CREATE INDEX IF NOT EXISTS idx_user_message_created   ON user_message (created_at);

COMMENT ON COLUMN user_message.dedup_key IS '触达幂等键：业务事件 + 收件人，重复发布撞唯一索引即丢弃';

-- ---------------------------------------------------------------------------
-- 2. message_template：模板与变量槽（U07）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_template (
    id          uuid          NOT NULL,
    code        varchar(40)   NOT NULL,
    category    varchar(20)   NOT NULL,
    title_tpl   varchar(200)  NOT NULL,
    content_tpl text          NOT NULL,
    var_keys    varchar(300),
    enabled     boolean       NOT NULL DEFAULT true,
    remark      varchar(200),
    created_at  timestamp     NOT NULL DEFAULT now(),
    updated_at  timestamp     NOT NULL DEFAULT now(),
    CONSTRAINT message_template_pkey PRIMARY KEY (id),
    CONSTRAINT ck_message_template_category CHECK (category IN ('trade', 'marketing', 'system', 'ticket'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_message_template_code ON message_template (code);

-- 变量槽口径：{orderNo} 这类花括号占位，var_keys 逗号分隔声明必填项。
-- 缺任意一个必填槽位时服务端整条不落库（见 MessageRenderPolicy），避免产出「订单  已发货」半成品。
INSERT INTO message_template (id, code, category, title_tpl, content_tpl, var_keys, enabled, remark, created_at, updated_at)
SELECT gen_random_uuid(), t.code, t.category, t.title_tpl, t.content_tpl, t.var_keys, true, t.remark, now(), now()
FROM (VALUES
    ('order_paid', 'trade', '订单 {orderNo} 支付成功',
     '我们已收到您的付款 ¥{amount}，花艺师将按 {deliveryHint} 安排制作与配送。',
     'orderNo,amount,deliveryHint', '支付成功事件（U06）'),
    ('order_shipped', 'trade', '订单 {orderNo} 已发出',
     '配送单号 {expressNo}（{expressCompany}），预计 {expectText} 送达，收花人 {receiverName}。',
     'orderNo,expressNo,expressCompany,expectText,receiverName', '发货事件（U06）'),
    ('order_delivered', 'trade', '订单 {orderNo} 已签收',
     '花礼已送达 {receiverName}。7 天内可在订单详情申请售后，满意的话欢迎写一条评价。',
     'orderNo,receiverName', '签收事件（U06）'),
    ('refund_result', 'trade', '退款{resultText}',
     '订单 {orderNo} 的退款申请{resultText}，金额 ¥{amount}。{note}',
     'orderNo,resultText,amount', '退款结论事件（U06）'),
    ('coupon_expiring', 'marketing', '优惠券「{couponName}」即将到期',
     '该券将于 {expireDate} 到期，剩 {daysLeft} 天。下单时会自动列出可用券。',
     'couponName,expireDate,daysLeft', '券到期事件（U06）'),
    ('card_expiring', 'marketing', '礼品卡 {cardNo} 即将到期',
     '卡号 {cardNo} 余额 ¥{balance}，将于 {expireDate} 到期，请尽快使用。',
     'cardNo,balance,expireDate', '礼品卡到期（M 组事件调用 MessagePublisher）'),
    ('card_transferred', 'system', '礼品卡已转赠给 {receiver}',
     '您转赠的礼品卡 {cardNo} 已由 {receiver} 接收，转赠码同步失效。',
     'cardNo,receiver', '礼品卡转赠（M 组事件调用 MessagePublisher）'),
    ('subscription_due', 'trade', '订阅花束 {dayText}后送达',
     '订阅计划「{planName}」下一期将于 {nextDate} 配送，如需改期请在订阅详情调整。',
     'planName,nextDate,dayText', '订阅提醒（N 组事件调用 MessagePublisher）'),
    ('ticket_replied', 'ticket', '工单 {ticketNo} 有新回复',
     '客服已回复您的工单「{title}」：{excerpt}',
     'ticketNo,title,excerpt', '工单回复事件（U06，工单服务内部发布）'),
    ('ticket_resolved', 'ticket', '工单 {ticketNo} 已处理完成',
     '处理结论：{solution}。如未解决可在工单详情点「重新打开」继续跟进。',
     'ticketNo,solution', '工单解决通知（U13/U24）'),
    ('care_reminder', 'system', '花礼养护提醒（第 {dayNo} 天）',
     '{content}',
     'dayNo,content', '养护提醒（U20/U22，正文按花材知识库生成）'),
    ('broadcast_default', 'marketing', '{title}',
     '{content}',
     'title,content', '后台群发直接填写文案，不走模板插值（U23）')
) AS t(code, category, title_tpl, content_tpl, var_keys, remark)
WHERE NOT EXISTS (SELECT 1 FROM message_template m WHERE m.code = t.code);

-- ---------------------------------------------------------------------------
-- 3. notification_pref：类别 × 渠道矩阵（U08）
--    inbox 是本地唯一可用渠道；sms/email 行默认 false 且服务端不做任何外呼。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_pref (
    id         uuid         NOT NULL,
    user_id    uuid         NOT NULL,
    category   varchar(20)  NOT NULL,
    channel    varchar(20)  NOT NULL,
    enabled    boolean      NOT NULL DEFAULT false,
    created_at timestamp    NOT NULL DEFAULT now(),
    updated_at timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT notification_pref_pkey PRIMARY KEY (id),
    CONSTRAINT uk_notification_pref UNIQUE (user_id, category, channel),
    CONSTRAINT fk_notification_pref_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT ck_notification_pref_channel CHECK (channel IN ('inbox', 'sms', 'email'))
);

CREATE INDEX IF NOT EXISTS idx_notification_pref_user ON notification_pref (user_id, category);

-- 存量用户补齐矩阵：站内信默认开，短信/邮件默认关（未开通），保证偏好页首屏就有真实数据
INSERT INTO notification_pref (id, user_id, category, channel, enabled, created_at, updated_at)
SELECT gen_random_uuid(), u.id, c.category, ch.channel,
       (ch.channel = 'inbox'), now(), now()
FROM "user" u
CROSS JOIN (VALUES ('trade'), ('marketing'), ('system'), ('ticket')) AS c(category)
CROSS JOIN (VALUES ('inbox'), ('sms'), ('email')) AS ch(channel)
WHERE NOT EXISTS (SELECT 1 FROM notification_pref p
                  WHERE p.user_id = u.id AND p.category = c.category AND p.channel = ch.channel);

-- ---------------------------------------------------------------------------
-- 4. notification_profile：免打扰时段（U09）
--    dnd_start > dnd_end 即跨午夜窗口（22:00-08:00），判定逻辑在 NotificationPolicy 单测覆盖。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notification_profile (
    user_id          uuid      NOT NULL,
    dnd_enabled      boolean   NOT NULL DEFAULT true,
    dnd_start        time      NOT NULL DEFAULT '22:00:00',
    dnd_end          time      NOT NULL DEFAULT '08:00:00',
    marketing_paused boolean   NOT NULL DEFAULT false,
    created_at       timestamp NOT NULL DEFAULT now(),
    updated_at       timestamp NOT NULL DEFAULT now(),
    CONSTRAINT notification_profile_pkey PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_profile_user FOREIGN KEY (user_id) REFERENCES "user" (id)
);

INSERT INTO notification_profile (user_id, dnd_enabled, dnd_start, dnd_end, marketing_paused, created_at, updated_at)
SELECT u.id, true, '22:00:00', '08:00:00', false, now(), now()
FROM "user" u
WHERE NOT EXISTS (SELECT 1 FROM notification_profile p WHERE p.user_id = u.id);

-- ---------------------------------------------------------------------------
-- 5. scheduled_message：延时消息（U20/U21）
--    发送前必须用 status='pending'→'sending' 的条件 UPDATE 抢占，影响行数 0 即跳过，
--    因此任务重入、重启、多副本同时扫都不会重发。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS scheduled_message (
    id            uuid         NOT NULL,
    user_id       uuid         NOT NULL,
    category      varchar(20)  NOT NULL,
    template_code varchar(40),
    title         varchar(200) NOT NULL,
    content       text,
    link_url      varchar(500),
    payload       text,
    due_at        timestamp    NOT NULL,
    status        varchar(20)  NOT NULL DEFAULT 'pending',
    attempts      integer      NOT NULL DEFAULT 0,
    max_attempts  integer      NOT NULL DEFAULT 3,
    last_error    varchar(300),
    sent_at       timestamp,
    dedup_key     varchar(140),
    biz_type      varchar(40),
    biz_id        varchar(64),
    -- U22：false 表示没命中花材知识库、走的是通用文案，页面要标注「未定制」而不是留空
    customised    boolean      NOT NULL DEFAULT true,
    created_at    timestamp    NOT NULL DEFAULT now(),
    updated_at    timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_message_pkey PRIMARY KEY (id),
    CONSTRAINT fk_scheduled_message_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT ck_scheduled_message_status CHECK (status IN ('pending', 'sending', 'sent', 'failed', 'cancelled'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_scheduled_message_dedup ON scheduled_message (dedup_key);
CREATE INDEX IF NOT EXISTS idx_scheduled_message_due ON scheduled_message (status, due_at);
CREATE INDEX IF NOT EXISTS idx_scheduled_message_user ON scheduled_message (user_id, due_at);

COMMENT ON COLUMN scheduled_message.customised IS 'U22：false 表示未命中花材养护知识库、使用通用文案';

-- ---------------------------------------------------------------------------
-- 6. message_guard_event：跳转地址越权留痕（U05）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_guard_event (
    id         uuid         NOT NULL,
    reason     varchar(60)  NOT NULL,
    raw_url    varchar(600),
    source     varchar(40),
    operator   varchar(60),
    created_at timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT message_guard_event_pkey PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_message_guard_created ON message_guard_event (created_at DESC);

-- ---------------------------------------------------------------------------
-- 7. broadcast_campaign：后台群发台账（U23）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS broadcast_campaign (
    id            uuid         NOT NULL,
    title         varchar(200) NOT NULL,
    content       text,
    segment_code  varchar(40)  NOT NULL,
    segment_name  varchar(60),
    category      varchar(20)  NOT NULL DEFAULT 'marketing',
    status        varchar(20)  NOT NULL DEFAULT 'draft',
    target_count  integer      NOT NULL DEFAULT 0,
    sent_count    integer      NOT NULL DEFAULT 0,
    skipped_pref  integer      NOT NULL DEFAULT 0,
    skipped_dnd   integer      NOT NULL DEFAULT 0,
    skipped_cap   integer      NOT NULL DEFAULT 0,
    -- 营销周末停发被拦下的人数（U09）：与「退订」「上限」区分开，运营才知道该不该下周补发
    skipped_weekend integer    NOT NULL DEFAULT 0,
    failed_count  integer      NOT NULL DEFAULT 0,
    error_note    varchar(300),
    created_by    varchar(60),
    created_by_id uuid,
    started_at    timestamp,
    finished_at   timestamp,
    created_at    timestamp    NOT NULL DEFAULT now(),
    updated_at    timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT broadcast_campaign_pkey PRIMARY KEY (id),
    CONSTRAINT ck_broadcast_campaign_status CHECK (status IN ('draft', 'running', 'done', 'failed'))
);

CREATE INDEX IF NOT EXISTS idx_broadcast_campaign_created ON broadcast_campaign (created_at DESC);

-- ---------------------------------------------------------------------------
-- 8. message_archive_stat：90 天保留清理的归档计数（U10）
--    物理删除前按用户 + 月份分桶聚合，删除动作本身不再留明细。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_archive_stat (
    id            uuid        NOT NULL,
    user_id       uuid        NOT NULL,
    bucket_month  varchar(7)  NOT NULL,
    category      varchar(20) NOT NULL,
    message_count integer     NOT NULL,
    archived_at   timestamp   NOT NULL DEFAULT now(),
    CONSTRAINT message_archive_stat_pkey PRIMARY KEY (id),
    CONSTRAINT fk_message_archive_user FOREIGN KEY (user_id) REFERENCES "user" (id)
);

CREATE INDEX IF NOT EXISTS idx_message_archive_month ON message_archive_stat (bucket_month, user_id);
