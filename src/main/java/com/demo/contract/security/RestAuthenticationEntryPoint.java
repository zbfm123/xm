package com.demo.contract.security;

import com.demo.contract.auth.AuthErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未认证请求的 401 响应。
 *
 * <p>存在的意义是<b>让所有失败路径返回同一种响应格式</b>。
 * 如果让 Spring Security 用默认行为，未带令牌的请求会返回一个 HTML 错误页，
 * 而令牌过期的请求走另一条路径——前端就得写两套解析逻辑。
 *
 * <p>另外：这里会根据 {@link JwtAuthenticationFilter} 留下的失败原因区分
 * {@code TOKEN_EXPIRED} / {@code TOKEN_REVOKED} / {@code UNAUTHENTICATED}，
 * 让前端能决定是"引导重新登录"还是"提示已被登出"。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object reason = request.getAttribute(JwtAuthenticationFilter.AuthAttributes.FAILURE_REASON);

        AuthErrorCode code;
        String message;
        if ("EXPIRED".equals(reason)) {
            code = AuthErrorCode.TOKEN_EXPIRED;
            message = "登录已过期，请重新登录";
        } else if ("REVOKED".equals(reason)) {
            code = AuthErrorCode.TOKEN_REVOKED;
            message = "登录状态已失效，请重新登录";
        } else if ("MALFORMED".equals(reason)) {
            code = AuthErrorCode.TOKEN_INVALID;
            message = "令牌无效";
        } else {
            code = AuthErrorCode.UNAUTHENTICATED;
            message = "请先登录";
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code.name());
        body.put("message", message);
        body.put("path", request.getRequestURI());
        body.put("time", OffsetDateTime.now().toString());

        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
