-- ============================================================================
-- V48：客服工单与 SLA（U11/U13/U14/U16/U17/U18/U19/U24/U25/U26）
--   ticket            工单主体：类型/优先级/状态机/处理人/SLA 截止/解决方案/满意度/补偿闸门
--   ticket_message    顾客与客服的往返时间线，附件走本地 URL
--   ticket_sla_rule   类型 × 优先级 → 首响/解决时限（分钟）
--   faq_entry         规则式 FAQ 词表（U19，无模型无外呼）
--   ticket_agent      客服数据范围与主管标记（U26）：主管看全量，普通客服只看分配给自己的
-- 幂等口径同 V47：全部 IF NOT EXISTS / WHERE NOT EXISTS，时间写 now()。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. ticket
--    compensation_type 非空即已补偿，二次补偿由条件 UPDATE 拦下（U17）；
--    satisfaction 非空即已评分，重复评分同理（U24）；
--    source_type + source_id 唯一索引保证「差评自动开单」重放幂等（U18）。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket (
    id                     uuid          NOT NULL,
    ticket_no              varchar(24)   NOT NULL,
    user_id                uuid          NOT NULL,
    order_id               uuid,
    -- 提交时快照的联系电话：订单后续改址/换人不影响工单核身口径，出网一律脱敏（U16）
    contact_phone          varchar(20),
    category               varchar(20)   NOT NULL,
    title                  varchar(200)  NOT NULL,
    description            text,
    images                 text,
    priority               varchar(20)   NOT NULL DEFAULT 'normal',
    status                 varchar(20)   NOT NULL DEFAULT 'open',
    assignee_id            uuid,
    assignee_name          varchar(60),
    sla_rule_id            uuid,
    first_response_due_at  timestamp,
    resolve_due_at         timestamp,
    first_response_at      timestamp,
    resolved_at            timestamp,
    closed_at              timestamp,
    reopen_count           integer       NOT NULL DEFAULT 0,
    escalated              boolean       NOT NULL DEFAULT false,
    escalated_at           timestamp,
    escalate_note          varchar(200),
    solution               text,
    compensation_type      varchar(20),
    compensation_ref       varchar(64),
    compensated_at         timestamp,
    compensation_note      varchar(200),
    satisfaction           integer,
    satisfaction_note      varchar(300),
    satisfaction_at        timestamp,
    source_type            varchar(20)   NOT NULL DEFAULT 'manual',
    source_id              varchar(64),
    created_at             timestamp     NOT NULL DEFAULT now(),
    updated_at             timestamp     NOT NULL DEFAULT now(),
    CONSTRAINT ticket_pkey PRIMARY KEY (id),
    CONSTRAINT fk_ticket_user FOREIGN KEY (user_id) REFERENCES "user" (id),
    CONSTRAINT ck_ticket_category CHECK (category IN ('quality', 'delivery', 'refund', 'card', 'subscription', 'other')),
    CONSTRAINT ck_ticket_priority CHECK (priority IN ('low', 'normal', 'high', 'urgent')),
    CONSTRAINT ck_ticket_status   CHECK (status IN ('open', 'assigned', 'processing', 'resolved', 'closed')),
    CONSTRAINT ck_ticket_source   CHECK (source_type IN ('manual', 'review', 'order')),
    CONSTRAINT ck_ticket_compensation CHECK (compensation_type IS NULL
                                             OR compensation_type IN ('coupon', 'refund', 'none'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ticket_no ON ticket (ticket_no);
-- U18 差评自动开单幂等：同一来源只可能有一张工单，重放时 INSERT 撞唯一索引即跳过
-- （source_id 为空的手动工单不受约束：PostgreSQL 唯一索引允许多行 NULL）
CREATE UNIQUE INDEX IF NOT EXISTS uk_ticket_source ON ticket (source_type, source_id);
-- U15 队列默认按 SLA 剩余时间升序：未结单 + 解决时限打头
CREATE INDEX IF NOT EXISTS idx_ticket_sla_queue ON ticket (status, resolve_due_at);
CREATE INDEX IF NOT EXISTS idx_ticket_assignee  ON ticket (assignee_id, status, resolve_due_at);
CREATE INDEX IF NOT EXISTS idx_ticket_user      ON ticket (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ticket_order     ON ticket (order_id);
CREATE INDEX IF NOT EXISTS idx_ticket_created   ON ticket (created_at);

COMMENT ON COLUMN ticket.reopen_count IS 'U13：closed→open 只允许一次，跃迁前用条件 UPDATE 校验计数';
COMMENT ON COLUMN ticket.escalated IS 'U14：逾期升级标记，避免定时任务重复升级同一张单';

-- ---------------------------------------------------------------------------
-- 2. ticket_message：往返时间线（U16）
--    author_type：customer / agent / system；system 行记录状态跃迁与升级，时间线不再拼文案。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_message (
    id           uuid      NOT NULL,
    ticket_id    uuid      NOT NULL,
    author_type  varchar(20) NOT NULL,
    author_id    uuid,
    author_name  varchar(60),
    content      text      NOT NULL,
    attachments  text,
    -- 客服内部备注：顾客侧时间线不返回这一行，用于门店与花艺师之间的协调记录
    internal_note       boolean   NOT NULL DEFAULT false,
    -- 顾客还没看到的客服/系统回复：工单列表红点据此来
    unread_for_customer boolean   NOT NULL DEFAULT false,
    created_at   timestamp NOT NULL DEFAULT now(),
    CONSTRAINT ticket_message_pkey PRIMARY KEY (id),
    CONSTRAINT fk_ticket_message_ticket FOREIGN KEY (ticket_id) REFERENCES ticket (id) ON DELETE CASCADE,
    CONSTRAINT ck_ticket_message_author CHECK (author_type IN ('customer', 'agent', 'system'))
);

CREATE INDEX IF NOT EXISTS idx_ticket_message_ticket ON ticket_message (ticket_id, created_at);

-- ---------------------------------------------------------------------------
-- 3. ticket_sla_rule：类型 × 优先级 → 首响 / 解决时限（U14）
--    同一 (category, priority) 只允许一条启用规则，命中判定不依赖排序巧合。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_sla_rule (
    id                     uuid         NOT NULL,
    category               varchar(20)  NOT NULL,
    priority               varchar(20)  NOT NULL,
    first_response_minutes integer      NOT NULL,
    resolve_minutes        integer      NOT NULL,
    enabled                boolean      NOT NULL DEFAULT true,
    remark                 varchar(200),
    created_at             timestamp    NOT NULL DEFAULT now(),
    updated_at             timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT ticket_sla_rule_pkey PRIMARY KEY (id),
    CONSTRAINT uk_ticket_sla_rule UNIQUE (category, priority),
    CONSTRAINT ck_sla_rule_category CHECK (category IN ('quality', 'delivery', 'refund', 'card', 'subscription', 'other')),
    CONSTRAINT ck_sla_rule_priority CHECK (priority IN ('low', 'normal', 'high', 'urgent'))
);

-- 门店客服工作时段按 09:00-21:00 排，urgent 给 30 分钟首响是「当日必回」，
-- 其余优先级按小时级递减；解决时限 refund/card/subscription 涉及资金，给到 48 小时内。
INSERT INTO ticket_sla_rule (id, category, priority, first_response_minutes, resolve_minutes, enabled, remark, created_at, updated_at)
SELECT gen_random_uuid(), c.category, p.priority,
       CASE p.priority WHEN 'urgent'  THEN 30
                      WHEN 'high'    THEN 60
                      WHEN 'normal'  THEN 120
                      ELSE 240 END
           - CASE c.category WHEN 'refund' THEN 30 ELSE 0 END,
       CASE p.priority WHEN 'urgent'  THEN 240
                      WHEN 'high'    THEN 720
                      WHEN 'normal'  THEN 1440
                      ELSE 2880 END
           - CASE c.category WHEN 'refund'       THEN 480
                             WHEN 'card'         THEN 480
                             WHEN 'subscription' THEN 240
                             ELSE 0 END,
       true,
       CASE c.category WHEN 'quality'      THEN '花材与制作质量，优先当日闭环'
                       WHEN 'delivery'     THEN '配送时效问题，需与门店排班核对'
                       WHEN 'refund'       THEN '涉及资金，首响与解决时限都更紧'
                       WHEN 'card'         THEN '礼品卡与储值，涉及余额核对'
                       WHEN 'subscription' THEN '订阅周期与改期'
                       ELSE '其他常规咨询' END,
       now(), now()
FROM (VALUES ('quality'), ('delivery'), ('refund'), ('card'), ('subscription'), ('other')) AS c(category)
CROSS JOIN (VALUES ('urgent'), ('high'), ('normal'), ('low')) AS p(priority)
WHERE NOT EXISTS (SELECT 1 FROM ticket_sla_rule r
                  WHERE r.category = c.category AND r.priority = p.priority);

-- ---------------------------------------------------------------------------
-- 4. faq_entry：规则式问答（U19）
--    keywords 逗号分隔，匹配走本地词表；hit_count 用于把「答不上来的问题」排进后台。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS faq_entry (
    id             uuid         NOT NULL,
    question       varchar(300) NOT NULL,
    answer         text         NOT NULL,
    keywords       varchar(300) NOT NULL,
    category       varchar(20)  NOT NULL DEFAULT 'other',
    hit_count      integer      NOT NULL DEFAULT 0,
    helpful_count  integer      NOT NULL DEFAULT 0,
    sort_order     integer      NOT NULL DEFAULT 0,
    enabled        boolean      NOT NULL DEFAULT true,
    created_at     timestamp    NOT NULL DEFAULT now(),
    updated_at     timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT faq_entry_pkey PRIMARY KEY (id),
    CONSTRAINT ck_faq_category CHECK (category IN ('quality', 'delivery', 'refund', 'card', 'subscription', 'other'))
);

CREATE INDEX IF NOT EXISTS idx_faq_enabled_sort ON faq_entry (enabled, sort_order DESC);

INSERT INTO faq_entry (id, question, answer, keywords, category, sort_order, enabled, created_at, updated_at)
SELECT gen_random_uuid(), f.q, f.a, f.k, f.c, f.s, true, now(), now()
FROM (VALUES
    ('鲜花收到后先做什么？',
     '拆掉外包装后保留扎带，把花枝底部斜剪 2-3 厘米，花头除外整枝泡清水 1 小时（深水醒花），再去除水位线以下的叶子插瓶。做完这一步花瓣会重新硬挺。',
     '醒花,剪根,收到,怎么处理,开箱', 'quality', 90),
    ('鲜花的花期能撑几天？怎么让它久一点',
     '常见切花瓶插期：玫瑰 5-8 天、康乃馨 10-14 天、百合 7-12 天、郁金香 5-7 天。延长花期靠三件事：每天换水并再剪根 1 厘米、水位以下的叶子全部去掉、远离空调出风口与果盘（水果释放的乙烯会加速衰败）。',
     '花期,几天,持久,延长,枯萎,养护', 'quality', 85),
    ('配送范围与运费怎么算？',
     '同城支持当日达与预约定时达，运费按配送方式与整单重量计算，结算页会实时给出。超出同城范围的订单走次日达或空运冷链，页面会标注预计送达时间。',
     '配送,运费,范围,多久,送达,当日达', 'delivery', 80),
    ('订单一直没发货，能催一下吗？',
     '已付款订单进入备货后会有物流轨迹。如果超过预计送达时间仍未发出，可在订单详情申请退款，或在此提交「配送问题」工单，客服会按 SLA 时限优先响应并同步门店排班。',
     '催发货,没发货,什么时候发,延迟,发货', 'delivery', 75),
    ('退款多久到账？',
     '退款申请经门店受理后进入审核，确认打款即原路退回，银行卡一般 1-3 个工作日到账。已发货的花礼需要先签收再申请，取消订单只在待付款阶段可用。',
     '退款,到账,多久,怎么退,申请退', 'refund', 70),
    ('贺卡可以写自己的话吗？',
     '结算页可选贺卡样式并填写正文与署名，正文在下单时快照保存，之后修改模板不影响已下单的贺卡。贺卡有字数上限，超出会在提交时提示。',
     '贺卡,留言,署名,写字,卡片', 'other', 60),
    ('礼品卡余额不足怎么办？',
     '礼品卡可先抵扣部分金额，剩余部分用其他方式支付。卡号与密码只在本卡有效期内可用，到期前会收到站内信提醒。',
     '礼品卡,余额,储值,不够,抵扣', 'card', 55),
    ('订阅制花束可以改期或跳过吗？',
     '订阅计划可在订阅详情调整下一期的送达日期或跳过一次；周期内改期不额外收费。临近配送日期的调整需要门店确认，处理结果会以站内信通知。',
     '订阅,改期,跳过,周期,暂停', 'subscription', 50),
    ('花材有损伤或者和页面不符怎么办？',
     '签收后 7 天内可在订单详情申请售后，附上照片有助于门店快速判定。低星评价会自动生成工单，客服会主动联系处理。',
     '损伤,不符,缺件,坏花,不一样,售后', 'quality', 45),
    ('工单处理进度在哪里看？',
     '「我的工单」列表点开任意一条即可看到完整往返时间线，客服每次回复都会同步一条站内信。工单关闭后仍可评一次满意度，未解决可点「重新打开」继续跟进。',
     '工单,进度,处理,回复,状态', 'other', 40)
) AS f(q, a, k, c, s)
WHERE NOT EXISTS (SELECT 1 FROM faq_entry e WHERE e.question = f.q);

-- ---------------------------------------------------------------------------
-- 5. ticket_agent：客服数据范围（U26）
--    supervisor=true 看全量队列；普通客服只看分配给自己的工单，越权访问按 404 处理并留痕。
--    初始把现存的管理员账号登记为主管，保证上线即可用，再按需收口。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ticket_agent (
    user_id     uuid         NOT NULL,
    display_name varchar(60),
    supervisor  boolean      NOT NULL DEFAULT false,
    enabled     boolean      NOT NULL DEFAULT true,
    created_at  timestamp    NOT NULL DEFAULT now(),
    updated_at  timestamp    NOT NULL DEFAULT now(),
    CONSTRAINT ticket_agent_pkey PRIMARY KEY (user_id),
    CONSTRAINT fk_ticket_agent_user FOREIGN KEY (user_id) REFERENCES "user" (id)
);

INSERT INTO ticket_agent (user_id, display_name, supervisor, enabled, created_at, updated_at)
SELECT u.id, u.full_name, true, true, now(), now()
FROM "user" u
WHERE u.user_type = 'admin'
  AND NOT EXISTS (SELECT 1 FROM ticket_agent a WHERE a.user_id = u.id);
