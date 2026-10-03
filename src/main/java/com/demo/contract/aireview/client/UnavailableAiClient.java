package com.demo.contract.aireview.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 永远不可用的 AI 通道。
 *
 * <p><b>为什么需要这个类：Mock 桩永远可用，因此没法演示降级。</b>
 *
 * <p>不变式 I-04（AI 不可用时规则与人工流程不受影响）是三个「绝不砍」项之一，
 * 但它需要一个<b>可复现的</b>不可用状态才能演示与验收。
 * 之前的困境是：
 * <ul>
 *   <li>Mock 桩设计成"永远可成功"，这样正常路径不受影响——但它掩盖了降级路径</li>
 *   <li>靠"不配 API Key"来降级也不行：{@link AiClientConfig} 会回退到 Mock，
 *       于是不会抛出 {@code AI_UNAVAILABLE}</li>
 *   <li>靠拔网线或耗尽额度更不行：不可复现，演示现场会翻车</li>
 * </ul>
 *
 * <p>所以显式提供一个"就是不可用"的通道。它让降级路径变成**确定性的**：
 * 设 {@code AI_CLIENT_MODE=unavailable} 就能稳定复现，
 * 并且可以写进集成测试与演示脚本。
 *
 * <p>它的失败消息刻意写得具体：告诉使用者这是被显式打开的模拟开关，
 * 而不是真的出故障了——否则排查时会浪费大量时间在一个"故意"的状态上。
 */
public class UnavailableAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(UnavailableAiClient.class);

    public UnavailableAiClient() {
        log.warn("AI 通道被显式设为「不可用」（AI_CLIENT_MODE=unavailable）。"
                + "这是用于演示与验收降级路径的开关："
                + "规则校验与要素抽取不受影响，人工复核流程照常可用");
    }

    @Override
    public String complete(String systemPrompt, String userContent) {
        throw new AiCallException(AiErrorCode.AI_UNAVAILABLE,
                "AI 通道被显式设为不可用（AI_CLIENT_MODE=unavailable），"
                        + "用于演示降级路径。这不是故障：规则结论与要素抽取均已保留，"
                        + "可继续走人工复核");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String providerName() {
        return "unavailable(降级演示)";
    }
}
