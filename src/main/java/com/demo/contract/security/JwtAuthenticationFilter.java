package com.demo.contract.security;

import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.jwt.JwtService;
import com.demo.contract.auth.TokenBlacklist;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>职责只有三件：取令牌 → 校验 → 把身份放进上下文。它<b>不做授权判断</b>
 * （那是 SecurityFilterChain 的职责），也不处理错误响应（那是 EntryPoint 的职责）。
 *
 * <p>两个容易出错的地方，这里刻意处理了：
 * <ol>
 *   <li><b>finally 中清理 ThreadLocal</b>：Tomcat 线程池会复用线程。不清理的话，
 *       下一个请求可能读到上一个用户的租户 id——这是最严重的一类串号 bug。</li>
 *   <li><b>令牌无效时不抛异常，而是不带身份继续往下走</b>：最后由
 *       {@link RestAuthenticationEntryPoint} 统一返回 401。
 *       若在此处抛异常，异常会绕过 Security 的异常处理链，导致响应体格式不一致。</li>
 * </ol>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenBlacklist blacklist;

    public JwtAuthenticationFilter(JwtService jwtService, TokenBlacklist blacklist) {
        this.jwtService = jwtService;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        try {
            if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                authenticate(token, request);
            }
            chain.doFilter(request, response);
        } finally {
            // 必须清理，否则线程复用会造成租户串号（不变式 I-01 的执行侧保障）
            CurrentUser.clear();
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate(String token, HttpServletRequest request) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(token);
        } catch (JwtService.InvalidTokenException e) {
            // 记录原因便于排查，但不把细节返回给客户端
            request.setAttribute(AuthAttributes.FAILURE_REASON, e.getProblem().name());
            return;
        }

        if (blacklist.isRevoked(parsed.jti())) {
            request.setAttribute(AuthAttributes.FAILURE_REASON, "REVOKED");
            return;
        }

        CurrentUser.set(parsed.userId(), parsed.tenantId(), parsed.displayName(), parsed.role());

        var authentication = new UsernamePasswordAuthenticationToken(
                parsed.userId(),
                null,
                List.of(new SimpleGrantedAuthority(parsed.role().authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            return null;
        }
        String token = header.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    /** 请求属性名常量，避免字符串散落各处。 */
    public static final class AuthAttributes {
        public static final String FAILURE_REASON = "auth.failureReason";

        private AuthAttributes() {
        }
    }
}
