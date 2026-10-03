-- ===================================================================
-- H2 测试库建表脚本（MODE=MySQL）
--
-- 与 db/schema.sql 的差异：去掉 MySQL 专有的 ENGINE / COMMENT 子句。
-- ⚠️ 这意味着"改表结构时要同时改两份文件" —— 这是不用 Flyway 的代价之一，
--    已登记在 docs/07 的已知限制里。等补上 Flyway 后本文件即可删除。
-- ===================================================================

CREATE TABLE IF NOT EXISTS tenant (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id     BIGINT       NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(32)  NOT NULL,
    enabled       TINYINT      NOT NULL DEFAULT 1,
    failed_count  INT          NOT NULL DEFAULT 0,
    locked_until  TIMESTAMP    NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_tenant_username UNIQUE (tenant_id, username)
);

-- 与 db/schema.sql 保持一致（去掉 MySQL 专有的 ENGINE / COMMENT / ON UPDATE）
CREATE TABLE IF NOT EXISTS contract (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id         BIGINT       NOT NULL,
    title             VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_hash         CHAR(64)     NOT NULL,
    text_hash         CHAR(64)     NULL,
    storage_path      VARCHAR(512) NOT NULL,
    file_size         BIGINT       NOT NULL,
    status            VARCHAR(32)  NOT NULL,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS contract_text (
    id                  BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id           BIGINT      NOT NULL,
    contract_id         BIGINT      NOT NULL,
    text                CLOB        NOT NULL,
    text_hash           CHAR(64)    NOT NULL,
    offset_map          CLOB        NULL,
    no_extractable_text TINYINT     NOT NULL DEFAULT 0,
    created_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_contract_text_contract UNIQUE (contract_id)
);
