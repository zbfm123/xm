package com.demo.contract.web;

import com.demo.contract.aireview.client.AiCallException;
import com.demo.contract.aireview.client.AiErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.demo.contract.support.RedisTestConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 异常到 HTTP 状态码的映射测试（T-019 / A-08）。
 *
 * <p><b>为什么要单独建一个测试而不是在集成测试里发真请求</b>：
 * 真实的 {@code /ai-review} 会先查合同是否存在，合同不存在时返回 404，
 * 根本走不到 AI 调用。要用真请求触发就需要一份已提交的合同数据，
 * 而带 {@code @Transactional} 的测试数据对另一个连接的 HTTP 请求不可见，
 * 去掉事务又会污染数据库、影响其他测试。
 *
 * <p>所以这里用一个<b>只在测试作用域注册</b>的控制器直接把各类
 * {@link AiCallException} 抛出来，专注验证映射本身。
 * 这样没有副作用，也不会因为上游的校验顺序变化而失效。
 *
 * <p>认证用 {@code @WithMockUser}，<b>不把测试路径加进生产的放行列表</b>——
 * 为了测试方便而放宽生产的安全配置是不划算的交换。
 *
 * <p>要证明的命题：<b>降级状态必须是一个能被识别的业务状态，
 * 而不是一个笼统的 500 故障。</b>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({RedisTestConfig.class, AiExceptionMappingTest.ThrowController.class})
@WithMockUser(username = "tester", roles = {"LEGAL_STAFF"})
class AiExceptionMappingTest {

    @Autowired private MockMvc mockMvc;

    /** 只用于测试：按 code 参数抛出对应的 AI 异常。 */
    @RestController
    @RequestMapping("/__test/ai")
    static class ThrowController {
        @GetMapping("/throw")
        public String throwing(@RequestParam("code") String code) {
            AiErrorCode errorCode = AiErrorCode.valueOf(code);
            throw new AiCallException(errorCode, "测试用异常：" + code);
        }
    }

    @Test
    @DisplayName("AI_UNAVAILABLE → 503 + 明确的错误码（可降级识别）")
    void unavailableShouldMapTo503() throws Exception {
        mockMvc.perform(get("/__test/ai/throw").param("code", "AI_UNAVAILABLE"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"))
                .andExpect(jsonPath("$.degradable").value(true))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("限额类 → 429，且标记为可降级（不是致命错误）")
    void quotaErrorsShouldMapTo429() throws Exception {
        mockMvc.perform(get("/__test/ai/throw").param("code", "BUDGET_EXCEEDED"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("BUDGET_EXCEEDED"))
                .andExpect(jsonPath("$.degradable").value(true));

        mockMvc.perform(get("/__test/ai/throw").param("code", "CALL_LIMIT_EXCEEDED"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.degradable").value(true));
    }

    @Test
    @DisplayName("上游故障类 → 502，可重试的标记为可重试")
    void upstreamErrorsShouldMapTo502() throws Exception {
        mockMvc.perform(get("/__test/ai/throw").param("code", "AI_TIMEOUT"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("AI_TIMEOUT"))
                .andExpect(jsonPath("$.retryable").value(true))
                // 超时不是"可降级继续"——它重试一次还有机会成功
                .andExpect(jsonPath("$.degradable").value(false));

        mockMvc.perform(get("/__test/ai/throw").param("code", "AI_RATE_LIMITED"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.retryable").value(true));

        mockMvc.perform(get("/__test/ai/throw").param("code", "AI_SERVER_ERROR"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    @DisplayName("schema 非法 → 502，但**不可重试**（重试只会浪费额度）")
    void schemaInvalidShouldNotBeRetryable() throws Exception {
        mockMvc.perform(get("/__test/ai/throw").param("code", "SCHEMA_INVALID"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("SCHEMA_INVALID"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    @DisplayName("每一种 AI 错误码都有明确的映射 —— 不允许任何一类落到兜底 500")
    void everyErrorCodeShouldHaveAnExplicitMapping() throws Exception {
        for (AiErrorCode code : AiErrorCode.values()) {
            var result = mockMvc.perform(get("/__test/ai/throw").param("code", code.name()))
                    .andReturn();
            int status = result.getResponse().getStatus();

            org.assertj.core.api.Assertions.assertThat(status)
                    .withFailMessage("""
                            错误码 %s 落到了兜底分支（HTTP %d）。

                            任何一类 AI 错误都必须有明确的状态码：
                            落到 500 会让客户端无法区分「AI 的问题（可降级/可重试）」
                            与「服务端崩了（只能等修复）」。
                            """, code, status)
                    .isIn(502, 503, 429);

            String body = result.getResponse().getContentAsString();
            org.assertj.core.api.Assertions.assertThat(body)
                    .withFailMessage("错误码 %s 的响应里没有回传错误码本身", code)
                    .contains(code.name());
        }
    }
}
