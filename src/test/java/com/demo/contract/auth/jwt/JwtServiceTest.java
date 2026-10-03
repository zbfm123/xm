package com.demo.contract.auth.jwt;

import com.demo.contract.auth.domain.IssuedToken;
import com.demo.contract.auth.domain.Role;
import com.demo.contract.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtService 单元测试：不依赖 Spring 容器、不依赖网络与数据库。
 *
 * <p>覆盖重点是<b>失败路径</b>：过期、篡改、换密钥、issuer 不符。
 * 签发成功是显然的，失败处理才是容易写错的地方。
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-must-be-at-least-32-bytes-long";
    private static final String OTHER_SECRET = "another-secret-also-32-bytes-long-for-hmac-256";

    private JwtProperties properties;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        properties = new JwtProperties(SECRET, 120, "contract-review", 5, 15);
        jwtService = new JwtService(properties);
    }

    @Test
    @DisplayName("签发后能解析出全部声明，且 jti 非空")
    void shouldRoundTripAllClaims() {
        IssuedToken issued = jwtService.issue(42L, 7L, Role.LEGAL_LEAD, "法务主管（演示）");

        JwtService.ParsedToken parsed = jwtService.parse(issued.token());

        assertThat(parsed.userId()).isEqualTo(42L);
        assertThat(parsed.tenantId()).isEqualTo(7L);
        assertThat(parsed.role()).isEqualTo(Role.LEGAL_LEAD);
        assertThat(parsed.displayName()).isEqualTo("法务主管（演示）");
        assertThat(parsed.jti()).isNotBlank();
        assertThat(parsed.expiresAt()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("剩余有效秒数与配置一致")
    void shouldReportConfiguredLifetime() {
        IssuedToken issued = jwtService.issue(1L, 1L, Role.LEGAL_STAFF, "甲");
        assertThat(issued.expiresIn()).isEqualTo(120 * 60);
    }

    @Test
    @DisplayName("过期令牌解析失败，且原因区分为 EXPIRED（与签名错误区分开）")
    void shouldRejectExpiredToken() {
        // 直接构造一个已过期的令牌：用同一密钥与 issuer 签名，
        // 因此唯一的问题就是过期——这样才能确认失败原因确实被归类为 EXPIRED。
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        String expiredToken = Jwts.builder()
                .subject("1")
                .issuer("contract-review")
                .id(UUID.randomUUID().toString())
                .claim(JwtService.CLAIM_TENANT_ID, 1L)
                .claim(JwtService.CLAIM_ROLE, Role.LEGAL_STAFF.name())
                .claim(JwtService.CLAIM_DISPLAY_NAME, "甲")
                .issuedAt(Date.from(now.minusSeconds(7200)))
                .expiration(Date.from(now.minusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parse(expiredToken))
                .isInstanceOf(JwtService.InvalidTokenException.class)
                .satisfies(e -> assertThat(((JwtService.InvalidTokenException) e).getProblem())
                        .isEqualTo(JwtService.TokenProblem.EXPIRED));
    }

    @Test
    @DisplayName("issuer 不匹配的令牌被拒绝")
    void shouldRejectForeignIssuer() {
        String token = jwtService.issue(1L, 1L, Role.LEGAL_STAFF, "甲").token();

        JwtService wrongIssuer = new JwtService(
                new JwtProperties(SECRET, 120, "someone-else", 5, 15));

        assertThatThrownBy(() -> wrongIssuer.parse(token))
                .isInstanceOf(JwtService.InvalidTokenException.class)
                .satisfies(e -> assertThat(((JwtService.InvalidTokenException) e).getProblem())
                        .isEqualTo(JwtService.TokenProblem.MALFORMED));
    }

    @Test
    @DisplayName("换密钥签发的令牌无法通过校验")
    void shouldRejectTokenSignedWithAnotherSecret() {
        JwtService other = new JwtService(
                new JwtProperties(OTHER_SECRET, 120, "contract-review", 5, 15));
        String foreignToken = other.issue(1L, 1L, Role.LEGAL_STAFF, "甲").token();

        assertThatThrownBy(() -> jwtService.parse(foreignToken))
                .isInstanceOf(JwtService.InvalidTokenException.class)
                .satisfies(e -> assertThat(((JwtService.InvalidTokenException) e).getProblem())
                        .isEqualTo(JwtService.TokenProblem.MALFORMED));
    }

    @Test
    @DisplayName("载荷被篡改（即使只改一个字符）必须失败")
    void shouldRejectTamperedPayload() {
        String token = jwtService.issue(1L, 1L, Role.LEGAL_STAFF, "甲").token();

        // 改动 payload 段中的一个字符，签名随即不再匹配
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        int idx = payload.length / 2;
        payload[idx] = payload[idx] == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("结构损坏的字符串解析失败而不是抛未知异常")
    void shouldRejectMalformedString() {
        assertThatThrownBy(() -> jwtService.parse("not-a-jwt"))
                .isInstanceOf(JwtService.InvalidTokenException.class);
        assertThatThrownBy(() -> jwtService.parse(""))
                .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    @Test
    @DisplayName("密钥不足 32 字节时立即失败，而不是运行时才报错")
    void shouldRejectShortSecret() {
        assertThatThrownBy(() -> new JwtProperties("too-short", 120, "iss", 5, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("有效期非正数时配置校验失败")
    void shouldRejectNonPositiveExpiry() {
        assertThatThrownBy(() -> new JwtProperties(SECRET, 0, "iss", 5, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expire-minutes");
    }

    @Test
    @DisplayName("角色值非法时解析失败，不允许静默降级")
    void shouldRejectUnknownRole() {
        assertThatThrownBy(() -> Role.from("SUPER_ADMIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知角色");
    }
}
