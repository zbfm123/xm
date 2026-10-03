package com.demo.contract.aireview.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 客户端（OpenAI 兼容接口）。
 *
 * <p>为什么自写而不用 Spring AI（决策 D-07）：只有 DeepSeek 一个供应商，
 * "屏蔽供应商差异"这个最大卖点价值为零；而自写客户端约 100 行、
 * 没有版本不确定性、供应商耦合集中在这一个类里。
 *
 * <p>三条纪律：
 * <ol>
 *   <li><b>失败最多重试 1 次</b>，且只对超时/限流/5xx 重试。
 *       schema 非法重试没有意义——同样的提示词会得到同样的坏输出。</li>
 *   <li><b>调用前先过成本守卫</b>，超限直接失败而不是继续调。</li>
 *   <li><b>日志不打提示词全文与响应全文</b>——合同正文属于商业秘密。</li>
 * </ol>
 *
 * <p>请求里带 {@code response_format: json_object}：让模型尽量输出合法 JSON。
 * 但<b>仍然必须做 schema 校验</b>——这个参数只是提高概率，不是保证。
 */
@Component
public class DeepSeekClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AiCostGuard costGuard;

    private final boolean enabled;
    private final String apiKey;
    private final String model;
    private final int maxRetries;

    public DeepSeekClient(RestClient.Builder builder,
                          ObjectMapper objectMapper,
                          AiCostGuard costGuard,
                          @Value("${app.ai.enabled}") boolean enabled,
                          @Value("${app.ai.base-url}") String baseUrl,
                          @Value("${app.ai.api-key}") String apiKey,
                          @Value("${app.ai.model}") String model,
                          @Value("${app.ai.timeout-seconds}") int timeoutSeconds,
                          @Value("${app.ai.max-retries}") int maxRetries) {
        this.objectMapper = objectMapper;
        this.costGuard = costGuard;
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.maxRetries = Math.max(0, maxRetries);

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {{
                    setConnectTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                    setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
                }})
                .build();

        if (enabled && this.apiKey.isEmpty()) {
            // 显式告警而不是静默失败：配置错了应当立刻看见
            log.error("app.ai.enabled=true 但没有配置 API Key（DEEPSEEK_API_KEY），"
                    + "调用会返回 AI_UNAVAILABLE。请设置环境变量或改用 Mock 模式（AI_ENABLED=false）");
        } else {
            log.info("AI 客户端初始化: enabled={} model={} provider={}", enabled, model, providerName());
        }
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        if (!enabled) {
            throw new AiCallException(AiErrorCode.AI_UNAVAILABLE,
                    "AI 通道未启用（app.ai.enabled=false）。这是降级状态而非错误："
                            + "规则审查与人工流程应当照常工作");
        }
        if (apiKey.isEmpty()) {
            throw new AiCallException(AiErrorCode.AI_UNAVAILABLE,
                    "未配置 API Key，AI 通道不可用");
        }

        // 调用前先申请额度：超限直接失败，问题在第一次异常调用时就暴露
        costGuard.acquireOrThrow();

        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)),
                "temperature", 0.0,                    // 尽量确定性，便于复现与测试
                "response_format", Map.of("type", "json_object"));

        int attempt = 0;
        while (true) {
            try {
                String response = doCall(body);
                costGuard.recordUsage(systemPrompt.length() + userContent.length(), response.length());
                return response;
            } catch (AiCallException e) {
                // 只对可重试的错误重试，且最多 maxRetries 次
                if (e.isRetryable() && attempt < maxRetries) {
                    attempt++;
                    costGuard.acquireOrThrow();
                    log.warn("AI 调用失败，第 {} 次重试: code={}", attempt, e.getCode());
                    continue;
                }
                throw e;
            }
        }
    }

    private String doCall(Map<String, Object> body) {
        try {
            String raw = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            return extractContent(raw);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            // ⚠️ 日志只记状态码与响应长度，不记响应体全文
            log.warn("AI 调用返回错误状态: status={} bodyLength={}", status,
                    e.getResponseBodyAsString().length());
            if (status == 429) {
                throw new AiCallException(AiErrorCode.AI_RATE_LIMITED, "被限流（HTTP 429）", e);
            }
            if (status >= 500) {
                throw new AiCallException(AiErrorCode.AI_SERVER_ERROR, "服务端错误 " + status, e);
            }
            throw new AiCallException(AiErrorCode.AI_SERVER_ERROR,
                    "调用失败，HTTP " + status, e);

        } catch (ResourceAccessException e) {
            // 连接超时 / 读超时都落在这里
            throw new AiCallException(AiErrorCode.AI_TIMEOUT, "调用超时", e);

        } catch (AiCallException e) {
            throw e;

        } catch (RuntimeException e) {
            // ⚠️ 必须单独识别超时。
            //
            // SimpleClientHttpRequestFactory 在读超时时抛的是
            // SocketTimeoutException，而它<b>不是 ResourceAccessException</b>，
            // 会直接落到这个兜底分支里被错标成 AI_SERVER_ERROR。
            //
            // 后果不只是错误码不准：AI_SERVER_ERROR 被标为"可重试"，
            // 而超时重试会再耗一次额度与一个超时周期。更糟的是排查时
            // "服务端错误"会把注意力引向对方服务，而真实原因是本地超时配置。
            if (isTimeout(e)) {
                throw new AiCallException(AiErrorCode.AI_TIMEOUT, "调用超时", e);
            }
            throw new AiCallException(AiErrorCode.AI_SERVER_ERROR,
                    "调用异常: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 递归判断异常链里是否含超时。包装层次因 HTTP 客户端实现而异，不能只看最外层。 */
    private boolean isTimeout(Throwable e) {
        Throwable current = e;
        int depth = 0;
        while (current != null && depth++ < 10) {
            if (current instanceof java.net.SocketTimeoutException
                    || current instanceof java.net.http.HttpTimeoutException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 从 OpenAI 兼容响应里取出 content。
     *
     * <p>结构不对时抛 {@code SCHEMA_INVALID} 而<b>不是返回空字符串</b>：
     * 返回空字符串会让上层的 JSON 解析失败，最终报出来的错误与原��无关，排查成本高得多。
     */
    private String extractContent(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "响应为空");
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                        "响应缺少 choices 数组");
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                        "响应缺少 choices[0].message.content");
            }
            String text = content.asText();
            if (text.isBlank()) {
                throw new AiCallException(AiErrorCode.SCHEMA_INVALID, "模型返回内容为空");
            }
            return text;
        } catch (AiCallException e) {
            throw e;
        } catch (Exception e) {
            throw new AiCallException(AiErrorCode.SCHEMA_INVALID,
                    "响应不是合法 JSON", e);
        }
    }

    @Override
    public boolean isAvailable() {
        return enabled && !apiKey.isEmpty();
    }

    @Override
    public String providerName() {
        return enabled ? "deepseek(" + model + ")" : "deepseek(disabled)";
    }

    /** 供健康检查展示成本状态。 */
    public String costSummary() {
        return costGuard.summary();
    }
}
