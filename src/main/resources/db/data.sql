-- ===================================================================
-- 合同智能审查平台 · 初始数据
--
-- ⚠️ 纪律：这里只能有虚构数据。
--    真实姓名、真实公司、真实合同原文一律不得进入仓库。
--
-- 密码说明：下面两个 hash 对应明文口令在 docs/08-interview-and-demo-script.md
--          的演示准备里记录（仅演示用，且是本地环境）。
--          算法：BCrypt，强度 10。
-- ===================================================================

INSERT INTO tenant (id, code, name)
VALUES (1, 't-demo', '演示租户（虚构）')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO tenant (id, code, name)
VALUES (2, 't-other', '对照租户（用于验证隔离）')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 法务专员：只能看/改本租户数据
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (1, 'staff01', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        '法务专员（演示）', 'LEGAL_STAFF', 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

-- 法务主管：可终审低置信度条目
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (1, 'lead01', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        '法务主管（演示）', 'LEGAL_LEAD', 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);

-- 对照租户的用户：用于验证 A-01 跨租户隔离
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (2, 'other01', '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        '对照租户用户（演示）', 'LEGAL_STAFF', 1)
ON DUPLICATE KEY UPDATE display_name = VALUES(display_name);
