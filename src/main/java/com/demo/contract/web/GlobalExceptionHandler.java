package com.demo.contract.web;

import com.demo.contract.auth.AuthErrorCode;
import com.demo.contract.auth.AuthException;
import com.demo.contract.parse.ContractException;
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

    /**
     * 合同与解析相关异常。
     *
     * <p>状态码的选择依据是<b>调用方该做什么</b>，不是"错误听起来多严重"：
     * 文件太大/类型不符是调用方改参数就能解决的（400）；
     * 合同不存在是路径错了（404）；存储故障是服务端的问题（503，可重试）。
     */
    @ExceptionHandler(ContractException.class)
    public ResponseEntity<Map<String, Object>> handleContract(ContractException e,
                                                              HttpServletRequest request) {
        HttpStatus status = switch (e.getCode()) {
            case FILE_TOO_LARGE, MIME_MISMATCH, SIZE_MISMATCH, EMPTY_FILE, PDF_ENCRYPTED,
                 NO_EXTRACTABLE_TEXT, PARSE_FAILED -> HttpStatus.BAD_REQUEST;
            case CONTRACT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };

        if (status.is5xxServerError()) {
            log.error("合同处理失败: {} {}", request.getRequestURI(), e.getMessage(), e);
        } else {
            log.info("合同请求被拒绝: {} {} -> {}",
                    request.getRequestURI(), e.getCode(), e.getMessage());
        }

        return ResponseEntity.status(status)
                .body(base(e.getCode().name(), e.getMessage(), request));
    }

    /** 取不到租户上下文属于实现缺陷，明确记录为错误而不是普通 400。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e,
                                                                  HttpServletRequest request) {
        log.error("状态异常（疑似缺少登录上下文）: {} {}", request.getRequestURI(), e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(base("TENANT_CONTEXT_MISSING", "会话上下文缺失，请重新登录", request));
    }

    /**
     * 非法入参枚举值等：属于调用方错误，返回 400。
     *
     * <p>为什么单独处理：{@code ContractStatus.from()} 与 {@code Role.from()} 对未知值
     * 抛的是 {@link IllegalArgumentException}。不显式处理的话它会落到兜底分支变成 500，
     * 让"参数写错了"看起来像"服务端崩了"——<b>状态码应当反映谁该负责。</b>
     *
     * <p>注意这里返回的是通用文案：异常消息里可能带内部枚举名，
     * 对排查有用、对客户端无用，因此只记日志。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e,
                                                                     HttpServletRequest request) {
        log.info("请求参数不合法: {} {} -> {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.badRequest()
                .body(base("INVALID_PARAMETER", "请求参数不合法：" + e.getMessage(), request));
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
