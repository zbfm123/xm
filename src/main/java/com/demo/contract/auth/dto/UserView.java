package com.demo.contract.auth.dto;

/**
 * 返回给前端的用户信息。
 *
 * <p>这是一个<b>投影</b>（projection），不是实体：存在的唯一目的就是确保
 * {@code passwordHash} 之类的字段不可能被序列化出去。
 * 直接把 {@code User} 实体返回给前端是很常见的泄露路径。
 *
 * @param id          用户 id
 * @param tenantId    租户 id（前端展示"当前租户"用）
 * @param username    登录名
 * @param displayName 显示名
 * @param role        角色名
 */
public record UserView(
        Long id,
        Long tenantId,
        String username,
        String displayName,
        String role
) {
}
