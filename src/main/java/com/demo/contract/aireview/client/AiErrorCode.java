package com.demo.contract.aireview.client;

/**
 * 模型调用失败的原因分类。
 *
 * <p>分类的意义在于<b>调用方该做什么不同</b>：
 * 超时与限流可以重试一次，schema 非法重试也没用（同样的提示词会得到同样的坏输出），
 * 预算耗尽只能等，未启用则要检查配置。
 */
public enum AiErrorCode {

    /** 未配置 API Key 或显式关闭。**这不是错误，是降级状态**（不变式 I-04）。 */
    AI_UNAVAILABLE,

    /** 调用超时。重试一次后仍失败则转人工。 */
    AI_TIMEOUT,

    /** 被限流（HTTP 429）。退避后重试一次。 */
    AI_RATE_LIMITED,

    /** 服务端错误（5xx）。 */
    AI_SERVER_ERROR,

    /**
     * 返回内容不符合约定的结构。
     *
     * <p>⚠️ <b>整体丢弃，不做部分采纳、不用默认值补齐。</b>
     * 部分采纳会产出结构不完整的脏数据，而它看起来是正常的。
     */
    SCHEMA_INVALID,

    /** 超出单份合同的调用次数上限。 */
    CALL_LIMIT_EXCEEDED,

    /** 超出日预算。 */
    BUDGET_EXCEEDED
}
