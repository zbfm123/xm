package com.demo.contract.auth;

import com.demo.contract.auth.domain.CurrentUser;
import com.demo.contract.auth.domain.IssuedToken;
import com.demo.contract.auth.domain.User;
import com.demo.contract.auth.dto.LoginRequest;
import com.demo.contract.auth.dto.LoginResponse;
import com.demo.contract.auth.dto.UserView;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>端点与鉴权要求（由 {@code SecurityConfig} 的白名单控制）：
 * <ul>
 *   <li>{@code POST /api/auth/login} —— 匿名</li>
 *   <li>{@code POST /api/auth/logout} —— 需登录</li>
 *   <li>{@code GET  /api/auth/me} —— 需登录</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request.username(), request.password());
        User user = result.user();
        IssuedToken issued = result.token();

        UserView view = new UserView(
                user.getId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), user.getRole().name());

        return ResponseEntity.ok(LoginResponse.bearer(
                issued.token(), issued.expiresIn(), issued.expiresAt().getEpochSecond(), view));
    }

    /**
     * 登出。
     *
     * <p>幂等：重复登出、令牌已过期都返回 204。
     * 因为"让这个令牌失效"这个目标已经达成了，报错只会让前端多写无用分支。
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authService.logout(authorization.substring(7).trim());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserView> me() {
        User user = authService.currentUser(CurrentUser.require());
        return ResponseEntity.ok(new UserView(
                user.getId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), user.getRole().name()));
    }
}
