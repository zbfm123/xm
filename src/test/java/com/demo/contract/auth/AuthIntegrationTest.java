package com.demo.contract.auth;

import com.demo.contract.auth.domain.Role;
import com.demo.contract.auth.domain.User;
import com.demo.contract.auth.mapper.UserMapper;
import com.demo.contract.support.RedisTestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证链路集成测试：HTTP 层 → 过滤器 → Service → H2 数据库。
 *
 * <p>用 H2 + 内存 Redis，不依赖本机 MySQL 与 Redis。
 *
 * <p>覆盖的验收项：A-01 的认证部分，以及登录失败、锁定、令牌失效等异常路径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class AuthIntegrationTest {

    /**
     * 测试用户的口令。
     *
     * <p>命名刻意不叫 {@code PASSWORD}、也不用看起来像真口令的值：
     * 常量名叫 "PASSWORD" 会让人误以为项目里存着一个真实口令，
     * 而 push.ps1 的敏感信息扫描也会（正确地）把它拦下来。
     * 用 <b>显式表明用途</b> 的名字与值，既不自欺也不制造假警报。
     */
    private static final String TEST_USER_SECRET = "unit-test-placeholder-only";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String username;

    @BeforeEach
    void setUp() {
        RedisTestConfig.clear();
        username = "it_user_" + System.nanoTime();

        User u = new User();
        u.setTenantId(1L);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(TEST_USER_SECRET));
        u.setDisplayName("集成测试用户");
        u.setRole(Role.LEGAL_STAFF);
        u.setEnabled(true);
        userMapper.insert(u);
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("登录成功返回令牌与用户信息，且响应体中不含密码哈希")
    void loginShouldReturnTokenWithoutPasswordHash() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, TEST_USER_SECRET)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value(username))
                .andExpect(jsonPath("$.user.role").value("LEGAL_STAFF"))
                .andReturn().getResponse().getContentAsString();

        // 密码哈希绝不能出现在响应里
        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$");
    }

    @Test
    @DisplayName("带有效令牌访问 /me 返回当前用户")
    void meShouldReturnCurrentUser() throws Exception {
        String token = loginAndGetToken(username, TEST_USER_SECRET);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.tenantId").value(1));
    }

    // ------------------------------------------------------------------
    // 失败路径
    // ------------------------------------------------------------------

    @Test
    @DisplayName("密码错误返回 401 且错误码为 BAD_CREDENTIALS")
    void wrongPasswordShouldReturnBadCredentials() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, "wrong-secret")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("用户不存在时不泄露账号是否存在（与密码错误同一个错误码）")
    void unknownUserShouldNotLeakExistence() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("no_such_user_at_all", TEST_USER_SECRET)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    @DisplayName("连续失败达到阈值后账号锁定，此后即使密码正确也被拒绝")
    void repeatedFailuresShouldLockAccount() throws Exception {
        // 配置为 5 次；前 4 次是普通失败
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(username, "wrong-" + i)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
        }

        // 第 5 次触发锁定
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, "wrong-final")))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.unlockAt").isNumber());

        // 锁定后正确密码也必须被拒绝——否则锁定没有意义
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, TEST_USER_SECRET)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    @DisplayName("登录成功会清零失败计数")
    void successShouldResetFailureCounter() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(username, "wrong")))
                .andExpect(status().isUnauthorized());

        loginAndGetToken(username, TEST_USER_SECRET);

        assertThat(userMapper.findByUsername(username).getFailedCount()).isZero();
    }

    @Test
    @DisplayName("不带令牌访问受保护接口返回 401")
    void missingTokenShouldReturnUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("令牌被篡改返回 401 且错误码为 TOKEN_INVALID")
    void tamperedTokenShouldReturnInvalid() throws Exception {
        String token = loginAndGetToken(username, TEST_USER_SECRET);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    }

    @Test
    @DisplayName("登出后原令牌立即失效；重复登出仍返回 204（幂等，因为目标已达成）")
    void logoutShouldRevokeTokenAndBeIdempotent() throws Exception {
        String token = loginAndGetToken(username, TEST_USER_SECRET);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 再次登出：不能因为令牌已失效就报 401。
        // 用户的重试或前端重复调用都应得到"已经登出"这个同样成功的结果。
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    @DisplayName("登出时带的是垃圾令牌也返回 204——不泄露令牌是否曾经有效")
    void logoutWithGarbageTokenShouldStillReturnNoContent() throws Exception {
        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("登出只影响自己的令牌，其他会话不受影响")
    void logoutShouldNotAffectOtherSessions() throws Exception {
        String first = loginAndGetToken(username, TEST_USER_SECRET);
        String second = loginAndGetToken(username, TEST_USER_SECRET);

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());

        // 第二个令牌仍然有效
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("登录参数为空时返回 400 并指出字段")
    void blankCredentialsShouldFailValidation() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fields.username").exists())
                .andExpect(jsonPath("$.fields.password").exists());
    }

    @Test
    @DisplayName("健康检查无需登录（白名单生效）")
    void healthShouldBePublic() throws Exception {
        mockMvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------

    private String loginJson(String user, String pwd) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", user, "password", pwd));
    }

    private String loginAndGetToken(String user, String pwd) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(user, pwd)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
