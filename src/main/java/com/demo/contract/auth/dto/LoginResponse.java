package com.demo.contract.auth.dto;

/**
 * 登录成功响应。
 *
 * @param token      JWT
 * @param tokenType  固定为 Bearer，便于前端拼装请求头
 * @param expiresIn  有效秒数
 * @param expiresAt  过期时刻（epoch 秒）
 * @param user       当前用户信息
 */
public record LoginResponse(
        String token,
        String tokenType,
        long expiresIn,
        long expiresAt,
        UserView user
) {
    public static LoginResponse bearer(String token, long expiresIn, long expiresAt, UserView user) {
        return new LoginResponse(token, "Bearer", expiresIn, expiresAt, user);
    }
}
