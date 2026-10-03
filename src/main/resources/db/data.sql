-- ===================================================================
-- 合同智能审查平台 · 初始数据
--
-- ⚠️ 纪律：这里只能有虚构数据。
--    真实姓名、真实公司、真实合同原文一律不得进入仓库。
--
-- 演示口令（本地演示环境专用，不是真实凭据）：
--    三个账号的密码统一为  Demo@2026
--    算法：BCrypt，强度 10。哈希由 BCryptPasswordEncoder 生成并已自校验。
--    之所以把明文写在这里：否则没人知道哈希对应什么口令，演示时登录不上
--    还得反过来猜。这是本地演示数据，公开它不构成风险。
-- ===================================================================

INSERT INTO tenant (id, code, name)
VALUES (1, 't-demo', '演示租户（虚构）')
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO tenant (id, code, name)
VALUES (2, 't-other', '对照租户（用于验证隔离）')
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 法务专员：可复核普通条目，无权终审低置信度结论
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (1, 'staff01', '$2a$10$d1/ii8js8hSQX3TFqvzdHeIDeOij1q61J/pcvLEg7gfgUq0GwN2Qu',
        '法务专员（演示）', 'LEGAL_STAFF', 1)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash),
                        display_name  = VALUES(display_name),
                        role          = VALUES(role);

-- 法务主管：可终审低置信度条目（AI 误判的兜底人）
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (1, 'lead01', '$2a$10$d1/ii8js8hSQX3TFqvzdHeIDeOij1q61J/pcvLEg7gfgUq0GwN2Qu',
        '法务主管（演示）', 'LEGAL_LEAD', 1)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash),
                        display_name  = VALUES(display_name),
                        role          = VALUES(role);

-- 对照租户的用户：用于验证 A-01 跨租户隔离
INSERT INTO sys_user (tenant_id, username, password_hash, display_name, role, enabled)
VALUES (2, 'other01', '$2a$10$d1/ii8js8hSQX3TFqvzdHeIDeOij1q61J/pcvLEg7gfgUq0GwN2Qu',
        '对照租户用户（演示）', 'LEGAL_STAFF', 1)
ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash),
                        display_name  = VALUES(display_name),
                        role          = VALUES(role);
