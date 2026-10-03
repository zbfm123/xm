package com.demo.contract.auth.domain;

import java.time.Instant;

/**
 * 登录成功后返回的令牌与其元信息。
 *
 * @param token      JWT 字符串
 * @param expiresAt  过期时刻（UTC）
 * @param expiresIn  剩余有效秒数，便于前端安排刷新
 */
public record IssuedToken(String token, Instant expiresAt, long expiresIn) {
}
