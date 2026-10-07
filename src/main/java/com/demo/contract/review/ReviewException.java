package com.demo.contract.review;

/**
 * 人工复核被拒绝时抛出。
 *
 * <p>与 {@code ContractException} / {@code AuthException} 同构：携带错误码，
 * 由 {@code GlobalExceptionHandler} 统一映射为 HTTP 响应，
 * 前端只需要一套错误解析逻辑。
 */
public class ReviewException extends RuntimeException {

    private final ReviewErrorCode code;

    public ReviewException(ReviewErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ReviewErrorCode getCode() {
        return code;
    }
}
