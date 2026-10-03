-- ===================================================================
-- 合同智能审查平台 · 建表脚本（MySQL 8）
--
-- 约定：
--   1. 全部使用 CREATE TABLE IF NOT EXISTS，脚本可重复执行（替代 Flyway）
--   2. 每张业务表必须有 tenant_id，这是不变式 I-01 的存储侧基础
--   3. 审计类表（review_action）只追加，不设 update 逻辑
--
-- 本文件按天增长：Day 1 只建 auth 相关表，后续任务逐步追加。
-- ===================================================================

-- -------------------------------------------------------------------
-- 租户（Day 1 / T-003）
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '租户ID',
    code        VARCHAR(64)  NOT NULL COMMENT '租户编码',
    name        VARCHAR(128) NOT NULL COMMENT '租户名称',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '租户';

-- -------------------------------------------------------------------
-- 用户（Day 1 / T-003）
-- 密码只存 BCrypt 哈希，永不存明文、永不外传
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希',
    display_name  VARCHAR(64)  NOT NULL COMMENT '显示名',
    role          VARCHAR(32)  NOT NULL COMMENT '角色：LEGAL_STAFF/LEGAL_LEAD/DEMO_READONLY',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    failed_count  INT          NOT NULL DEFAULT 0 COMMENT '连续登录失败次数',
    locked_until  DATETIME     NULL COMMENT '锁定截止时间',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username),
    KEY idx_user_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '用户';

-- ===================================================================
-- 以下表按后续任务追加（不要提前建，避免与未实现的功能不一致）：
--   无
-- ===================================================================

-- -------------------------------------------------------------------
-- 审查任务（Day 4 / T-017）
--
-- 为什么需要一张独立的任务表，而不是复用 contract.status：
--   1. **可重入**：同一份合同可以有多次审查（改判后重跑），
--      任务表保留每次的历史，contract.status 只是"当前"状态
--   2. **幂等键**：重复提交要返回同一个 taskId，
--      这需要一个稳定的唯一键，contract 上放不下
--   3. **职责不同**：contract.status 描述**数据**状态（已解析/已校验），
--      task.status 描述**流程**状态（处理中/待人工复核）
--
-- idempotency_key 的语义与 review_action 一致：
--   同一个键只产生一个任务，重复提交返回既有的 taskId。
--   没有它，用户双击"开始审查"就会建出两个任务、两套结论。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT       NOT NULL COMMENT '所属租户',
    contract_id     BIGINT       NOT NULL COMMENT '合同ID',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键：重复提交返回同一 taskId',
    status          VARCHAR(32)  NOT NULL COMMENT '见 ReviewTaskStatus',
    status_reason   VARCHAR(512) NULL COMMENT '状态说明，例如降级原因',
    ai_available    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '本次执行时 AI 通道是否可用',
    total_findings  INT          NOT NULL DEFAULT 0 COMMENT 'AI 候选结论总数',
    reviewed_count  INT          NOT NULL DEFAULT 0 COMMENT '已人工复核的条数',
    created_by      BIGINT       NOT NULL COMMENT '创建人',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_task_idem (tenant_id, idempotency_key),
    KEY idx_review_task_contract (tenant_id, contract_id, id),
    KEY idx_review_task_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '审查任务（状态机）';

-- -------------------------------------------------------------------
-- 人工复核动作（Day 4 / T-018）—— **只追加，永不修改、永不删除**
--
-- 这是本项目三个「绝不砍」项之一（不变式 I-06）。表结构本身承担了三重保证：
--
--   1. 没有 updated_at / updated_by 列 —— 没有"修改"这个概念
--   2. 没有软删除列 —— 撤销也是**追加一条反向动作**，而不是改原记录
--   3. 建了 BEFORE UPDATE / BEFORE DELETE 触发器，从数据库层面拒绝改与删
--
-- 为什么需要第 3 条：Mapper 里不写 update/delete 只是"我们不做"，
-- 任何一次手工 SQL、运维脚本、将来新同事写的代码都能绕过它。
-- 触发器把"不能改"变成数据库强制的约束。
--
-- 哈希链：
--   record_hash = SHA-256(前一条哈希 | 幂等键 | 合同 | 结论 | 动作 | 操作人 | 时间)
--   任何一条被改动，其后所有记录的哈希都会对不上，verifyChain 会指出断点位置。
--
-- ⚠️ 哈希链防的是**篡改**，不是**抵赖**：知道算法的人可以重算整条链。
--    真正的不可抵赖需要外部时间戳或签名服务。这是已知限制，必须在面试时主动说明。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_action (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    tenant_id       BIGINT        NOT NULL COMMENT '所属租户',
    contract_id     BIGINT        NOT NULL COMMENT '合同ID',
    finding_id      BIGINT        NULL COMMENT '被复核的 AI 结论ID；合同级动作可空',
    idempotency_key VARCHAR(128)  NOT NULL COMMENT '幂等键：重放同一请求不产生新记录',
    action_code     VARCHAR(32)   NOT NULL COMMENT 'ACCEPT/REJECT/ESCALATE/NEED_INFO/CONFIRM_NO_RISK',
    reason          VARCHAR(1024) NULL COMMENT '复核理由',
    operator_id     BIGINT        NOT NULL COMMENT '操作人ID',
    operator_name   VARCHAR(64)   NOT NULL COMMENT '操作人名称（冗余保存：用户改名不影响历史记录）',
    previous_status VARCHAR(32)   NULL COMMENT '动作前的结论状态',
    new_status      VARCHAR(32)   NOT NULL COMMENT '动作后的结论状态',
    record_hash     CHAR(64)      NOT NULL COMMENT '本条记录哈希（含前一条哈希）',
    previous_hash   CHAR(64)      NOT NULL COMMENT '前一条记录哈希；链首为 64 个 0',
    created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '毫秒精度：同秒内多条也能定序',
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_action_idem (tenant_id, idempotency_key),
    KEY idx_review_action_contract (tenant_id, contract_id, id),
    KEY idx_review_action_finding (tenant_id, finding_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '人工复核动作（只追加）';

-- -------------------------------------------------------------------
-- 抽取到的合同要素（Day 4 / T-015）
--
-- quote / char_start / char_end 三者是"可核验"的关键：
--   没有原文引文与区间的要素，人工无法核对，也就不该被采信（不变式 I-02）。
--
-- status 与 rule 模块的 ElementStatus 对应：
--   CONFIRMED / LOW_CONFIDENCE 有值；UNKNOWN / CONFLICT 无值。
--   **对齐失败的要素会以 UNKNOWN 落库，而不是被丢弃**——
--   必须能看出"这个字段尝试抽过但失败了"，否则与"从没抽过"无法区分。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_element (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户',
    contract_id   BIGINT       NOT NULL COMMENT '合同ID',
    field_key     VARCHAR(64)  NOT NULL COMMENT '字段名，对应 ElementField',
    element_value VARCHAR(512) NULL COMMENT '抽取值；无值时为空',
    quote         VARCHAR(1024) NULL COMMENT '支撑该值的原文引文',
    char_start    INT          NULL COMMENT '原文区间起点',
    char_end      INT          NULL COMMENT '原文区间终点（开区间）',
    confidence    DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '置信度 0~1',
    match_level   VARCHAR(16)  NULL COMMENT '证据对齐级别 EXACT/NORMALIZED/FUZZY；未命中为空',
    status        VARCHAR(32)  NOT NULL COMMENT 'CONFIRMED/LOW_CONFIDENCE/UNKNOWN/CONFLICT',
    status_reason VARCHAR(512) NULL COMMENT '状态原因，例如缺哪个字段、为何对齐失败',
    source        VARCHAR(16)  NOT NULL DEFAULT 'AI' COMMENT 'LLM 或 REGEX',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_element_contract_field (contract_id, field_key),
    KEY idx_element_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '抽取到的合同要素';

-- -------------------------------------------------------------------
-- AI 风险审查结论（Day 4 / T-016）
--
-- 三条硬约束体现在表结构里：
--   1. status 的初始值**永远不是"已生效"**——AI 结论只是候选
--   2. char_start/char_end 为空的条目 state 必为对齐失败类，不进报告正文
--   3. model_version / prompt_version 必填——结论要可追溯
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_finding (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      BIGINT       NOT NULL COMMENT '所属租户',
    contract_id    BIGINT       NOT NULL COMMENT '合同ID',
    risk_type      VARCHAR(64)  NOT NULL COMMENT '风险类型枚举',
    quote          VARCHAR(2048) NOT NULL COMMENT '模型给出的原文引文',
    char_start     INT          NULL COMMENT '对齐后的原文区间起点；为空表示无法定位',
    char_end       INT          NULL COMMENT '对齐后的原文区间终点',
    confidence     DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '综合置信度',
    match_level    VARCHAR(16)  NULL COMMENT '证据对齐级别',
    status         VARCHAR(32)  NOT NULL COMMENT 'PENDING/LOW_CONFIDENCE/EVIDENCE_MISMATCH/EVIDENCE_AMBIGUOUS',
    status_reason  VARCHAR(512) NULL COMMENT '状态原因',
    model_version  VARCHAR(64)  NOT NULL COMMENT '模型版本，用于追溯',
    prompt_version VARCHAR(32)  NOT NULL COMMENT '提示词版本，进缓存键',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_ai_finding_tenant_contract (tenant_id, contract_id),
    KEY idx_ai_finding_contract_status (contract_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT 'AI 风险审查结论（候选）';

-- -------------------------------------------------------------------
-- 规则结论（Day 3 / T-011~T-012）
--
-- result 三态：HIT / PASS / UNDETERMINED
--   UNDETERMINED（无法判定）必须能落库并与 PASS 区分开，
--   否则"信息不足"会在持久化这一层被悄悄折叠成"没问题"。
--
-- error_message 非空表示这条规则自身执行失败——
--   它需要和"合同有问题"（result=HIT）分开看，因此单独一列。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rule_finding (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户（不变式 I-01）',
    contract_id   BIGINT       NOT NULL COMMENT '合同ID',
    rule_code     VARCHAR(64)  NOT NULL COMMENT '规则编码',
    rule_name     VARCHAR(128) NOT NULL COMMENT '规则名称',
    severity      VARCHAR(16)  NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
    result        VARCHAR(16)  NOT NULL COMMENT 'HIT/PASS/UNDETERMINED',
    evidence      VARCHAR(512) NULL COMMENT '命中依据（人可读）',
    char_start    INT          NULL COMMENT '证据在归一化文本中的起点',
    char_end      INT          NULL COMMENT '证据在归一化文本中的终点（开区间）',
    detail        VARCHAR(1024) NULL COMMENT '补充说明：缺失字段、计算过程等',
    error_message VARCHAR(512) NULL COMMENT '规则自身执行失败的信息，非空表示这条规则坏了',
    checked_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '执行时间',
    PRIMARY KEY (id),
    KEY idx_rule_finding_tenant_contract (tenant_id, contract_id),
    KEY idx_rule_finding_contract_result (contract_id, result)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '规则结论';

-- -------------------------------------------------------------------
-- 合同主记录（Day 2 / T-006）
--
-- file_hash：上传文件字节的 SHA-256 —— 存储去重与上传幂等的依据
-- text_hash：归一化文本的 SHA-256 —— AI 结果缓存的键
-- 两者都需要：同内容的合同可能是不同文件（重新导出后字节不同但文本相同）
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '合同ID',
    tenant_id         BIGINT       NOT NULL COMMENT '所属租户（不变式 I-01）',
    title             VARCHAR(255) NOT NULL COMMENT '合同标题',
    original_filename VARCHAR(255) NOT NULL COMMENT '上传时的原文件名',
    file_hash         CHAR(64)     NOT NULL COMMENT '文件字节 SHA-256，幂等键',
    text_hash         CHAR(64)     NULL COMMENT '归一化文本 SHA-256，AI 缓存键',
    storage_path      VARCHAR(512) NOT NULL COMMENT '本地存储相对路径',
    file_size         BIGINT       NOT NULL COMMENT '文件字节数',
    status            VARCHAR(32)  NOT NULL COMMENT '状态，见 ContractStatus',
    deleted           TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '软删除标记',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_contract_tenant_status (tenant_id, status, deleted),
    KEY idx_contract_tenant_file_hash (tenant_id, file_hash),
    KEY idx_contract_text_hash (text_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '合同主记录';

-- -------------------------------------------------------------------
-- 合同正文（Day 2 / T-009）
--
-- original_text：提取后、归一化前的原文。**必须保存**：
--   偏移映射只告诉我们"归一化下标 → 原文下标"，
--   但要取出原文片段（证据高亮、人工核对）就必须有原文本身。
--   只在归一化文本上存映射是没用的——那等于把映射映射回自己。
--
-- offset_map：归一化坐标 → 原文坐标的映射。
--   必须保存它，否则下游"AI 结论要能定位到原文"无法实现（不变式 I-02）。
-- -------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS contract_text (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT      NOT NULL COMMENT '所属租户',
    contract_id         BIGINT      NOT NULL COMMENT '合同ID',
    text                LONGTEXT    NOT NULL COMMENT '归一化文本',
    original_text       LONGTEXT    NOT NULL COMMENT '归一化前的原文，用于按原文坐标取证据片段',
    text_hash           CHAR(64)    NOT NULL COMMENT '归一化文本 SHA-256',
    offset_map          LONGTEXT    NULL COMMENT '归一化坐标→原文坐标映射(逗号分隔整数)',
    no_extractable_text TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否无可提取文本(疑似扫描件)',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contract_text_contract (contract_id),
    KEY idx_contract_text_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '合同正文';
