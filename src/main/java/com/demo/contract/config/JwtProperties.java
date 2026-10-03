package com.demo.contract.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录与 JWT 相关配置，前缀 {@code app.jwt}。
 *
 * @param secret        签名密钥，<b>长度至少 32 字节</b>（HS256 要求），生产必须用环境变量覆盖
 * @param expireMinutes Token 有效期（分钟）
 * @param issuer        签发者标识
 * @param maxFailures   连续失败多少次后锁定账号
 * @param lockMinutes   锁定时长（分钟）
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String secret,
        long expireMinutes,
        String issuer,
        int maxFailures,
        long lockMinutes
) {

    public JwtProperties {
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            // 显式失败：密钥太短会让 HS256 抛异常，不如在启动时就说清楚原因
            throw new IllegalArgumentException(
                    "app.jwt.secret 至少需要 32 字节（HS256 要求）。当前长度="
                            + (secret == null ? 0 : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
        }
        if (expireMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.expire-minutes 必须大于 0");
        }
        if (maxFailures <= 0) {
            throw new IllegalArgumentException("app.jwt.max-failures 必须大于 0");
        }
        if (lockMinutes <= 0) {
            throw new IllegalArgumentException("app.jwt.lock-minutes 必须大于 0");
        }
    }
}
