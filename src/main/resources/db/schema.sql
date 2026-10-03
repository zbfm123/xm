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
--   Day 2 / T-006  contract
--   Day 2 / T-009  contract_text
--   Day 3 / T-015  contract_element
--   Day 3 / T-012  rule_finding
--   Day 4 / T-016  ai_finding
--   Day 4 / T-017  review_task
--   Day 4 / T-018  review_action（只追加）
-- ===================================================================
