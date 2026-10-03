package com.demo.contract.aireview.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * AI 客户端的装配决策。
 *
 * <p><b>为什么用显式配置类而不是在两个实现上打 {@code @ConditionalOnProperty}：</b>
 *
 * <p>我先用了 {@code @ConditionalOnProperty}，结果两个实现<b>同时被注册了</b>，
 * 报 {@code NoUniqueBeanDefinitionException: expected single matching bean but found 2}，
 * 整个应用上下文起不来。
 *
 * <p>与其去追条件注解在测试上下文里的求值细节，
 * 不如让"二选一"这件事在<b>代码结构上显式可见</b>：
 * 这里有且只有一个 {@code AiClient} bean，选谁由这一个 {@code if} 决定。
 *
 * <p>教训：<b>当"互斥"是硬要求时，用显式的分支表达它，而不是依赖注解的求值时机。</b>
 * 条件注解的错误方式是"两个都在"或"一个都没有"，
 * 而这两种情况都要等到上下文启动失败才被发现。
 */
@Configuration
public class AiClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AiClientConfig.class);

    /**
     * 唯一的 {@code AiClient} bean。
     *
     * <p>{@code app.ai.enabled=false}（默认）时返回 Mock 桩：
     * 这既是测试开关，也是演示降级开关（不变式 I-04）。
     */
    @Bean
    public AiClient aiClient(RestClient.Builder builder,
                             ObjectMapper objectMapper,
                             AiCostGuard costGuard,
                             @Value("${app.ai.enabled}") boolean enabled,
                             @Value("${app.ai.base-url}") String baseUrl,
                             @Value("${app.ai.api-key}") String apiKey,
                             @Value("${app.ai.model}") String model,
                             @Value("${app.ai.timeout-seconds}") int timeoutSeconds,
                             @Value("${app.ai.max-retries}") int maxRetries) {

        if (!enabled) {
            log.info("AI 通道 = Mock 桩（零额度消耗）。"
                    + "切换真实调用：设 AI_ENABLED=true 与 DEEPSEEK_API_KEY");
            return new MockAiClient(objectMapper);
        }

        if (apiKey == null || apiKey.isBlank()) {
            // 启用却没配 Key：这是配置错误，必须显式可见。
            // 但仍然返回一个可用的 bean——退回 Mock 而不是让应用起不来，
            // 因为"AI 不可用"在本项目里是一种被设计过的状态（I-04），不是致命错误。
            log.error("AI_ENABLED=true 但未配置 DEEPSEEK_API_KEY，"
                    + "已回退到 Mock 桩。真实调用不会发生。");
            return new MockAiClient(objectMapper);
        }

        log.info("AI 通道 = DeepSeek 真实调用（model={}）", model);
        return new DeepSeekClient(builder, objectMapper, costGuard,
                true, baseUrl, apiKey, model, timeoutSeconds, maxRetries);
    }
}
