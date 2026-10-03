package com.demo.contract.aireview.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DeepSeek 客户端测试。
 *
 * <p>用 MockWebServer 起真实 HTTP 服务端，而不是 mock 掉 {@link AiClient} 接口。
 * 理由：mock 我自己的接口只能验证"调用发生过"，
 * <b>测不到 HTTP 状态码映射、超时、响应体解析这些真正容易出错的地方</b>。
 *
 * <p>重点覆盖异常分支：限流、5xx、超时、响应结构非法、超额拒绝。
 */
class DeepSeekClientTest {

    private MockWebServer server;
    private AiCostGuard costGuard;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private DeepSeekClient client(int maxRetries, int maxCalls, boolean enabled) {
        costGuard = new AiCostGuard(maxCalls, 5.0, enabled);
        return new DeepSeekClient(
                RestClient.builder(),
                new ObjectMapper(),
                costGuard,
                enabled,
                server.url("/").toString(),
                enabled ? "test-key" : "",
                "deepseek-chat",
                2,                    // 超时 2 秒，便于测超时
                maxRetries);
    }

    private static String okBody(String content) {
        return """
                {"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":%s}}]}
                """.formatted(new ObjectMapper().valueToTree(content).toString());
    }

    // ==================================================================
    // 正常路径
    // ==================================================================

    @Test
    @DisplayName("成功调用能取出 content，并记录用量")
    void shouldReturnContentAndRecordUsage() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(okBody("{\"findings\":[]}")));

        DeepSeekClient client = client(1, 5, true);
        String result = client.complete("你是审查助手", "合同正文");

        assertThat(result).isEqualTo("{\"findings\":[]}");
        assertThat(costGuard.totalCalls()).isEqualTo(1);
        assertThat(costGuard.estimatedTokensTotal()).isGreaterThan(0);
    }

    @Test
    @DisplayName("请求体为 OpenAI 兼容结构，且带 response_format=json_object")
    void requestShapeShouldBeOpenAiCompatible() throws InterruptedException {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(okBody("{}")));

        client(0, 5, true).complete("系统提示词", "用户内容");

        var recorded = server.takeRequest();
        String body = recorded.getBody().readUtf8();

        assertThat(recorded.getPath()).isEqualTo("/chat/completions");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        assertThat(body).contains("\"model\":\"deepseek-chat\"");
        // temperature=0 是为了可复现
        assertThat(body).contains("\"temperature\":0");
        assertThat(body).contains("response_format");
        assertThat(body).contains("json_object");
        // 系统与用户消息都在
        assertThat(body).contains("系统提示词").contains("用户内容");
    }

    // ==================================================================
    // 未启用 / 降级
    // ==================================================================

    @Test
    @DisplayName("未启用时抛 AI_UNAVAILABLE —— 这是降级状态而非错误")
    void disabledShouldThrowUnavailable() {
        DeepSeekClient client = client(1, 5, false);

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.AI_UNAVAILABLE));
        assertThat(client.isAvailable()).isFalse();
        // 未启用时不应产生任何调用记录
        assertThat(costGuard.totalCalls()).isZero();
    }

    @Test
    @DisplayName("启用但缺 Key 时同样抛 AI_UNAVAILABLE，而不是发出无凭据的请求")
    void enabledWithoutKeyShouldFailFast() {
        costGuard = new AiCostGuard(5, 5.0, true);
        DeepSeekClient client = new DeepSeekClient(
                RestClient.builder(), new ObjectMapper(), costGuard,
                true, server.url("/").toString(), "   ", "deepseek-chat", 2, 1);

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.AI_UNAVAILABLE));
        assertThat(server.getRequestCount()).isZero();
    }

    // ==================================================================
    // 异常分支
    // ==================================================================

    @Test
    @DisplayName("HTTP 429 → AI_RATE_LIMITED，重试用尽后抛出")
    void rateLimitedShouldMapToRateLimited() {
        // 重试 1 次 → 共 2 次请求
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate\"}"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate\"}"));

        DeepSeekClient client = client(1, 10, true);

        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> {
                    AiCallException ex = (AiCallException) e;
                    assertThat(ex.getCode()).isEqualTo(AiErrorCode.AI_RATE_LIMITED);
                    // 限流值得重试
                    assertThat(ex.isRetryable()).isTrue();
                });

        assertThat(server.getRequestCount())
                .withFailMessage("限流没有按配置重试：期望 2 次请求")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("HTTP 500 → AI_SERVER_ERROR，且可重试")
    void serverErrorShouldBeRetryable() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));

        assertThatThrownBy(() -> client(1, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> {
                    AiCallException ex = (AiCallException) e;
                    assertThat(ex.getCode()).isEqualTo(AiErrorCode.AI_SERVER_ERROR);
                    assertThat(ex.isRetryable()).isTrue();
                });
    }

    @Test
    @DisplayName("读超时 → AI_TIMEOUT")
    void timeoutShouldMapToTimeout() {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(okBody("{}"))
                // MockWebServer 3.x/4.x 的签名是 (long, TimeUnit)，没有 Duration 重载
                .setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS));

        assertThatThrownBy(() -> client(0, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.AI_TIMEOUT));
    }

    @Test
    @DisplayName("响应结构非法 → SCHEMA_INVALID，且不重试（重试没有意义）")
    void malformedResponseShouldBeSchemaInvalidAndNotRetried() {
        // 合法 JSON，但缺少 choices
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"x\"}"));

        assertThatThrownBy(() -> client(3, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> {
                    AiCallException ex = (AiCallException) e;
                    assertThat(ex.getCode()).isEqualTo(AiErrorCode.SCHEMA_INVALID);
                    // schema 非法重试只会得到同样的坏输出，因此不重试
                    assertThat(ex.isRetryable())
                            .withFailMessage("schema 非法被标成可重试，会浪费额度")
                            .isFalse();
                });

        assertThat(server.getRequestCount())
                .withFailMessage("schema 非法不应重试，期望只请求 1 次")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("响应根本不是 JSON → SCHEMA_INVALID")
    void nonJsonResponseShouldBeSchemaInvalid() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/plain")
                .setBody("这不是 JSON"));

        assertThatThrownBy(() -> client(0, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.SCHEMA_INVALID));
    }

    @Test
    @DisplayName("content 为空字符串 → SCHEMA_INVALID（不返回空串让上层去猜）")
    void emptyContentShouldBeSchemaInvalid() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(okBody("   ")));

        assertThatThrownBy(() -> client(0, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.SCHEMA_INVALID));
    }

    @Test
    @DisplayName("响应为空体 → SCHEMA_INVALID")
    void emptyBodyShouldBeSchemaInvalid() {
        server.enqueue(new MockResponse().setResponseCode(200).setBody(""));

        assertThatThrownBy(() -> client(0, 10, true).complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.SCHEMA_INVALID));
    }

    // ==================================================================
    // 成本守卫
    // ==================================================================

    @Test
    @DisplayName("单合同调用次数超限时直接失败，不继续调用")
    void callLimitShouldStopFurtherCalls() {
        // 上限 2 次；重试 1 次意味着一次 complete 最多用 2 次额度
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate"));
        server.enqueue(new MockResponse().setResponseCode(429).setBody("rate"));

        DeepSeekClient client = client(1, 2, true);

        // 第一次 complete 会用掉 2 次额度（原调用 + 重试），此时已达上限
        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.AI_RATE_LIMITED));

        // 第二次 complete 应当被守卫直接拒绝，且不发出任何请求
        int requestsBefore = server.getRequestCount();
        assertThatThrownBy(() -> client.complete("s", "u"))
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.CALL_LIMIT_EXCEEDED));

        assertThat(server.getRequestCount())
                .withFailMessage("超限后仍然发出了请求，守卫形同虚设")
                .isEqualTo(requestsBefore);
    }

    @Test
    @DisplayName("beginContract 重置单合同计数，允许下一份合同继续调用")
    void beginContractShouldResetCounter() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(okBody("{}")));
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(okBody("{}")));

        DeepSeekClient client = client(0, 1, true);

        client.complete("s", "u");
        assertThatThrownBy(() -> client.complete("s", "u"))
                .satisfies(e -> assertThat(((AiCallException) e).getCode())
                        .isEqualTo(AiErrorCode.CALL_LIMIT_EXCEEDED));

        // 换一份合同：额度重置
        costGuard.beginContract();
        assertThat(client.complete("s", "u")).isEqualTo("{}");
    }

    @Test
    @DisplayName("Mock 模式下不限额：桩调用不花钱，限制它只会妨碍开发")
    void mockModeShouldNotEnforceLimits() {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json").setBody(okBody("{}")));

        // enabled=false 时守卫不限额；但客户端本身也会因未启用而拒绝调用，
        // 因此这里直接验证守卫行为
        costGuard = new AiCostGuard(1, 0.0001, false);
        for (int i = 0; i < 50; i++) {
            costGuard.acquireOrThrow();
        }
        assertThat(costGuard.totalCalls()).isEqualTo(50);
        assertThat(costGuard.limitsEnforced()).isFalse();
    }

    @Test
    @DisplayName("日预算耗尽时明确拒绝，绝不降级成'未发现风险'")
    void budgetExhaustedShouldFailExplicitly() {
        // 预算极小，且强制启用限额
        costGuard = new AiCostGuard(100, 0.000001, true);
        costGuard.acquireOrThrow();
        costGuard.recordUsage(100_000, 10_000);   // 记账后必然超预算

        assertThatThrownBy(() -> costGuard.acquireOrThrow())
                .isInstanceOf(AiCallException.class)
                .satisfies(e -> {
                    AiCallException ex = (AiCallException) e;
                    assertThat(ex.getCode()).isEqualTo(AiErrorCode.BUDGET_EXCEEDED);
                    // 预算耗尽不是"可重试"——它需要人工处理
                    assertThat(ex.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("providerName 反映当前通道，便于演示时一眼确认")
    void providerNameShouldReflectMode() {
        assertThat(client(1, 5, true).providerName()).contains("deepseek");
        assertThat(client(1, 5, false).providerName()).contains("disabled");
    }

    @Test
    @DisplayName("成本摘要能读出累计用量")
    void costSummaryShouldBeReadable() {
        assertThat(client(1, 5, true).costSummary()).contains("累计调用");
        assertThat(client(1, 5, false).costSummary()).contains("Mock");
    }
}
