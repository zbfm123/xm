package com.demo.contract.web;

import com.demo.contract.auth.AuthErrorCode;
import com.demo.contract.auth.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理。
 *
 * <p>目标是<b>让所有错误响应格式一致</b>：{@code {code, message, path, time}}。
 * 前端只需要一套解析逻辑；同时保证 500 类错误不把堆栈泄露给客户端。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, Object>> handleAuth(AuthException e, HttpServletRequest request) {
        HttpStatus status = switch (e.getCode()) {
            case BAD_CREDENTIALS, UNAUTHENTICATED, TOKEN_EXPIRED, TOKEN_INVALID, TOKEN_REVOKED ->
                    HttpStatus.UNAUTHORIZED;
            case ACCOUNT_LOCKED -> HttpStatus.LOCKED;          // 423
            case ACCOUNT_DISABLED -> HttpStatus.FORBIDDEN;      // 403
            case TENANT_CONTEXT_MISSING -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        Map<String, Object> body = base(e.getCode().name(), e.getMessage(), request);
        if (e.getUnlockAtEpochSeconds() != null) {
            body.put("unlockAt", e.getUnlockAtEpochSeconds());
        }

        // 锁定是业务事件，记 INFO；令牌问题可能意味着攻击，记 WARN
        if (e.getCode() == AuthErrorCode.ACCOUNT_LOCKED) {
            log.info("账号锁定: {} {}", request.getRequestURI(), e.getMessage());
        } else if (e.getCode() == AuthErrorCode.TOKEN_INVALID) {
            log.warn("令牌异常: {} {}", request.getRequestURI(), e.getMessage());
        }

        return ResponseEntity.status(status).body(body);
    }

    /** 参数校验失败：把所有字段错误一次性返回，避免前端逐个试。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e,
                                                               HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> fields.putIfAbsent(fe.getField(), fe.getDefaultMessage()));

        Map<String, Object> body = base("VALIDATION_FAILED", "请求参数不合法", request);
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 兜底。
     *
     * <p><b>这里刻意返回泛化消息</b>：异常的细节（可能含表名、路径、SQL 片段）只进服务端日志，
     * 不返回给客户端。否则一个未预期的异常就会变成信息泄露。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("未处理异常: {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(base("INTERNAL_ERROR", "服务器内部错误", request));
    }

    private Map<String, Object> base(String code, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("time", OffsetDateTime.now().toString());
        return body;
    }
}
