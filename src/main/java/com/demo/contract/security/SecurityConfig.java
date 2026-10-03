package com.demo.contract.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置。
 *
 * <p>设计取向是<b>默认拒绝</b>：{@code anyRequest().authenticated()} 收口，
 * 只把确实需要匿名的端点（健康检查、登录）显式放开。
 * 反过来写（默认放行、逐个加保护）迟早会漏掉新加的接口。
 *
 * <p>无状态：{@code SessionCreationPolicy.STATELESS}。身份完全来自 JWT，
 * 服务端不存会话，因此可以水平扩容而不需要粘性会话。
 *
 * <p>这里<b>刻意不配置表单登录与 HTTP Basic</b>：接口是给前端 JS 调的，
 * 留着它们只会多出两个可被探测的认证入口。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * 免认证的路径白名单。新增公开接口必须在这里登记，否则默认被拦。
     *
     * <p>为什么 {@code /api/auth/logout} 也在白名单里（这一步想过才定的）：
     * 登出应当是<b>幂等</b>的。如果要求已认证，那么"用已失效令牌再登出一次"
     * 会被过滤器拦成 401，而不是返回 204。但此时用户的诉求已经达成了——
     * 令牌本来就失效，失败响应只会让前端多写一个无意义的分支。
     *
     * <p>安全性没有因此降低：登出在语义上不需要"已认证身份"，
     * <b>令牌本身就是凭证</b>——控制器解析它，有效就拉黑，无效就什么都不做。
     * 它并不返回任何受保护数据。
     */
    private static final String[] PUBLIC_PATHS = {
            "/api/health",
            "/api/auth/login",
            "/api/auth/logout",
            // 前端页面：零构建的静态壳，所有数据仍走需要认证的接口。
            // 放行静态文件不降低安全性——页面本身不含任何业务数据。
            "/",
            "/index.html",
            "/favicon.ico",
            "/app/**",
            // 示例合同 PDF 生成（仅在 dev/test 环境注册，见 SampleContractController）
            "/api/debug/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt：自带盐、可调强度，是密码存储的稳妥默认值
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter,
                                           RestAuthenticationEntryPoint entryPoint) throws Exception {
        http
                // 前后端分离 + JWT，不使用 Cookie 传递身份，因此 CSRF 不适用。
                // ⚠️ 若将来改为 Cookie 存令牌，这里必须重新开启，否则会有 CSRF 漏洞。
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .authorizeHttpRequests(auth -> auth
                        // 预检请求必须放行，否则浏览器跨域调用会在 OPTIONS 阶段就被拦掉
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
