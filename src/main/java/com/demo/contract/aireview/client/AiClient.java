package com.demo.contract.aireview.client;

/**
 * 模型客户端抽象。
 *
 * <p>存在的唯一理由是<b>可测试性</b>：把模型调用封在接口后面，
 * 测试注入一个桩实现就能覆盖超时、限流、非法 JSON 三类异常分支，
 * 不需要网络、不消耗额度。
 *
 * <p>同一个开关（{@code app.ai.enabled=false}）还兼作演示时的降级手段——
 * <b>一个设计同时解决测试与降级两件事</b>，这是它比引入 Spring AI 更划算的地方。
 */
public interface AiClient {

    /**
     * 调用模型，要求返回符合约定结构的 JSON 文本。
     *
     * @param systemPrompt 系统提示词（角色与输出约定）
     * @param userContent  用户内容（待分析的文本）
     * @return 模型返回的原始文本（由调用方负责 schema 校验）
     * @throws AiCallException 调用失败或未启用
     */
    String complete(String systemPrompt, String userContent);

    /** 当前是否真实可调用。false 时调用会抛 {@code AI_UNAVAILABLE}，调用方据此走降级。 */
    boolean isAvailable();

    /** 实现名称，用于日志与调试台展示。 */
    String providerName();
}
