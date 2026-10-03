package com.demo.contract.aireview.client;

/**
 * 模型调用异常。
 *
 * <p>与项目里其他异常同构：携带明确错误码，由调用方决定是重试、降级还是转人工。
 * <b>不在这里做重试</b>——重试策略属于客户端，但"能不能重试"的判断属于调用方。
 */
public class AiCallException extends RuntimeException {

    private final AiErrorCode code;

    public AiCallException(AiErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AiCallException(AiErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public AiErrorCode getCode() {
        return code;
    }

    /** 该错误是否值得重试。schema 非法重试没有意义，超时/限流才有意义。 */
    public boolean isRetryable() {
        return code == AiErrorCode.AI_TIMEOUT
                || code == AiErrorCode.AI_RATE_LIMITED
                || code == AiErrorCode.AI_SERVER_ERROR;
    }
}
