package com.demo.contract.auth.domain;

/**
 * 用户角色。
 *
 * <p>三个角色的差异不只是"能不能改"，而是<b>谁有权终审低置信度结论</b>：
 * <ul>
 *   <li>{@link #LEGAL_STAFF} 法务专员：可复核普通条目，<b>无权终审低置信度条目</b></li>
 *   <li>{@link #LEGAL_LEAD} 法务主管：可终审低置信度条目（AI 误判的兜底人）</li>
 *   <li>{@link #DEMO_READONLY} 演示只读：任何写操作一律拒绝</li>
 * </ul>
 *
 * <p>权限判定在 T-018（人工复核）中实现，本枚举只提供角色标识与 Spring Security 的权限名。
 */
public enum Role {

    LEGAL_STAFF,
    LEGAL_LEAD,
    DEMO_READONLY;

    /** Spring Security 的权限名，统一加 ROLE_ 前缀。 */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Role from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("role 不能为空");
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            // 未知角色必须显式失败，不能默默降级为最低权限之外的东西
            throw new IllegalArgumentException("未知角色: " + value);
        }
    }
}
