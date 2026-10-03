package com.demo.contract.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.demo.contract.auth.domain.IssuedToken;
import com.demo.contract.auth.domain.Role;
import com.demo.contract.config.JwtProperties;

/**
 * JWT 的签发与解析。
 *
 * <p>载荷里放什么、不放什么：
 * <ul>
 *   <li>放：{@code sub}=用户 id、{@code tid}=租户 id、{@code role}、{@code name}、{@code jti}、{@code iss}、{@code exp}</li>
 *   <li><b>不放</b>：任何凭据、邮箱、真实姓名。JWT 只是<b>签名</b>而非<b>加密</b>，任何人都能解开看内容。</li>
 * </ul>
 *
 * <p>{@code jti} 的作用是配合登出：JWT 本身无状态，登出只能把 {@code jti} 写进 Redis 黑名单，
 * 并让黑名单条目的 TTL 等于该 Token 的剩余有效期，避免黑名单无限膨胀。
 */
@Component
public class JwtService {

    public static final String CLAIM_TENANT_ID = "tid";
    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_DISPLAY_NAME = "name";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签发 Token。
     *
     * @param userId    用户 id，作为 {@code sub}
     * @param tenantId  租户 id
     * @param role      角色
     * @param displayName 显示名（仅用于前端展示，非敏感信息）
     */
    public IssuedToken issue(Long userId, Long tenantId, Role role, String displayName) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(properties.expireMinutes() * 60);

        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .issuer(properties.issuer())
                .id(UUID.randomUUID().toString())
                .claim(CLAIM_TENANT_ID, tenantId)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_DISPLAY_NAME, displayName)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, exp, properties.expireMinutes() * 60);
    }

    /**
     * 校验并解析 Token。
     *
     * <p>失败一律抛 {@link InvalidTokenException}，并携带明确原因，
     * 让上层能区分"过期"与"签名不对"——<b>这两种情况对用户的提示和处理方式不同。</b>
     */
    public ParsedToken parse(String token) {
        try {
            Claims c = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new ParsedToken(
                    Long.valueOf(c.getSubject()),
                    c.get(CLAIM_TENANT_ID, Number.class).longValue(),
                    Role.from(c.get(CLAIM_ROLE, String.class)),
                    c.get(CLAIM_DISPLAY_NAME, String.class),
                    c.getId(),
                    c.getExpiration().toInstant()
            );
        } catch (ExpiredJwtException e) {
            throw new InvalidTokenException(TokenProblem.EXPIRED, "令牌已过期", e);
        } catch (JwtException | IllegalArgumentException e) {
            // 签名不符、结构损坏、issuer 不匹配、角色值非法都落在这里
            throw new InvalidTokenException(TokenProblem.MALFORMED, "令牌无效", e);
        }
    }

    /** 令牌解析失败的原因分类。 */
    public enum TokenProblem {
        /** 已过期，前端应引导重新登录。 */
        EXPIRED,
        /** 签名/结构/issuer/角色不合法，属于异常情况，需记录日志。 */
        MALFORMED
    }

    public static class InvalidTokenException extends RuntimeException {
        private final TokenProblem problem;

        public InvalidTokenException(TokenProblem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }

        public TokenProblem getProblem() {
            return problem;
        }
    }

    /**
     * 解析结果。
     *
     * @param jti Token 唯一标识，登出时写入黑名单
     */
    public record ParsedToken(Long userId, Long tenantId, Role role, String displayName,
                              String jti, Instant expiresAt) {
    }
}
