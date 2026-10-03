package com.demo.contract.auth;

/**
 * 认证失败异常。携带错误码，由全局异常处理器（后续任务）映射为 HTTP 响应。
 */
public class AuthException extends RuntimeException {

    private final AuthErrorCode code;
    private final Long unlockAtEpochSeconds;

    public AuthException(AuthErrorCode code, String message) {
        this(code, message, null);
    }

    public AuthException(AuthErrorCode code, String message, Long unlockAtEpochSeconds) {
        super(message);
        this.code = code;
        this.unlockAtEpochSeconds = unlockAtEpochSeconds;
    }

    public AuthErrorCode getCode() {
        return code;
    }

    /** 仅 {@link AuthErrorCode#ACCOUNT_LOCKED} 时有值，方便前端显示倒计时。 */
    public Long getUnlockAtEpochSeconds() {
        return unlockAtEpochSeconds;
    }
}
