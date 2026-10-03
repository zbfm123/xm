package com.demo.contract.parse;

/**
 * 解析/上传失败异常。
 *
 * <p>与 {@code AuthException} 同构：携带错误码，由全局异常处理器统一映射为 HTTP 响应。
 * 这样前端只需要一套错误解析逻辑。
 */
public class ContractException extends RuntimeException {

    private final ParseErrorCode code;

    public ContractException(ParseErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public ContractException(ParseErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public ParseErrorCode getCode() {
        return code;
    }
}
