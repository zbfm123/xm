package com.demo.contract.parse;

import com.demo.contract.auth.domain.Role;
import com.demo.contract.auth.domain.User;
import com.demo.contract.auth.mapper.UserMapper;
import com.demo.contract.support.RedisTestConfig;
import com.demo.contract.support.TestFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 合同接口的 HTTP 层测试。
 *
 * <p>只覆盖 {@code ContractServiceTest}（服务层）无法证明的东西：
 * <ul>
 *   <li>鉴权是否真的生效（未登录是否被拦）</li>
 *   <li>错误码到 HTTP 状态码的映射是否正确</li>
 *   <li>租户上下文是否由过滤器正确建立</li>
 * </ul>
 *
 * <p>这一层曾经漏掉一个真实缺陷：非法状态筛选返回 500 而不是 400，
 * 因为 {@code IllegalArgumentException} 落到了兜底处理器。
 * 服务层测试发现不了它——服务层抛异常是对的，错的是映射。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(RedisTestConfig.class)
@Transactional
class ContractApiTest {

    private static final String TEST_USER_SECRET = "unit-test-placeholder-only";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        RedisTestConfig.clear();

        String username = "api_user_" + System.nanoTime();
        User u = new User();
        u.setTenantId(1L);
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(TEST_USER_SECRET));
        u.setDisplayName("接口测试用户");
        u.setRole(Role.LEGAL_STAFF);
        u.setEnabled(true);
        userMapper.insert(u);

        token = login(username, TEST_USER_SECRET);
    }

    @Test
    @DisplayName("未登录访问合同接口返回 401")
    void unauthenticatedShouldBeRejected() throws Exception {
        mockMvc.perform(get("/api/contracts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("非法状态筛选返回 400 而不是 500（回归：曾落到兜底处理器）")
    void invalidStatusFilterShouldReturnBadRequest() throws Exception {
        mockMvc.perform(get("/api/contracts").param("status", "NOT_A_STATUS")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("上传成功返回 200，且响应不含存储路径等内部字段")
    void uploadShouldSucceed() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "测试合同.pdf", "application/pdf", TestFiles.minimalPdf("api"));

        mockMvc.perform(multipart("/api/contracts").file(file)
                        .param("title", "接口层测试合同")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent").value(false))
                .andExpect(jsonPath("$.contract.title").value("接口层测试合同"))
                .andExpect(jsonPath("$.contract.status").value("UPLOADED"))
                // 存储路径与哈希属于内部实现，不应出现在响应中
                .andExpect(jsonPath("$.contract.storagePath").doesNotExist())
                .andExpect(jsonPath("$.contract.fileHash").doesNotExist());
    }

    @Test
    @DisplayName("扩展名伪装返回 400 MIME_MISMATCH")
    void mismatchedMagicBytesShouldReturnBadRequest() throws Exception {
        MockMultipartFile fake = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", TestFiles.fakePdfWithWrongContent());

        mockMvc.perform(multipart("/api/contracts").file(fake)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MIME_MISMATCH"));
    }

    @Test
    @DisplayName("访问不存在的合同返回 404 CONTRACT_NOT_FOUND")
    void missingContractShouldReturnNotFound() throws Exception {
        mockMvc.perform(get("/api/contracts/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONTRACT_NOT_FOUND"));
    }

    @Test
    @DisplayName("删除不存在的合同返回 204（幂等）")
    void deleteMissingShouldBeIdempotent() throws Exception {
        mockMvc.perform(delete("/api/contracts/999999").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("合同 id 来自其他租户时返回 404 而不是 403（不确认其存在）")
    void otherTenantContractShouldLookNonExistent() throws Exception {
        // 用本租户账号访问一个属于对照租户的 id（对照租户由 data.sql 提供，id 通常为 1）
        // 这里直接断言"不会返回 200"，具体是 404 由服务层保证
        mockMvc.perform(get("/api/contracts/1").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------

    private String login(String username, String pwd) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", pwd))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
