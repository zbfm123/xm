package com.demo.contract.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 已登出令牌的黑名单。
 *
 * <p>JWT 是<b>无状态</b>的：签发之后服务端不再持有它，所以"登出"无法通过删除服务端记录实现。
 * 通行做法是维护一份黑名单，请求时先查黑名单。
 *
 * <p>关键设计：<b>黑名单条目的 TTL 等于该 Token 的剩余有效期</b>。
 * 过期之后 Token 自己就失效了，再留在黑名单里只是白白占内存。
 * 这样可以保证黑名单的大小有上界（最多 = 一个有效期窗口内的登出量）。
 */
@Component
public class TokenBlacklist {

    private static final String KEY_PREFIX = "auth:revoked:";

    private final StringRedisTemplate redis;

    public TokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 把令牌加入黑名单。
     *
     * @param jti       Token 唯一标识
     * @param expiresAt Token 自身过期时刻
     */
    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // 已经过期的令牌无需拉黑
            return;
        }
        redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + jti));
    }
}
