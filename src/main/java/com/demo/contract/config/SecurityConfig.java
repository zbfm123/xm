package com.demo.contract.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 安全基础配置（Day 1 占位版）。
 *
 * <p>本类现在只做两件事：提供 BCrypt 编码器、放行健康检查端点。
 * 完整的 JWT 过滤器链属于 T-004，届时本类会被改造 —— 这是刻意的增量实现，
 * 不在 Day 1 提前搭一个用不上的过滤器链（Lean Mode：不为假想需求建框架）。
 *
 * <p>⚠️ 当前状态是「全部放行」，仅用于 Day 1 骨架自检，**不可用于演示**。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
