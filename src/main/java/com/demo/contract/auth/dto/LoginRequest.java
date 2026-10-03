package com.demo.contract.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 *
 * <p>{@code password} 上限定长度不是形式主义：BCrypt 只取前 72 字节，
 * 超长输入既无意义又会浪费 CPU（这也是一个轻量的拒绝服务面）。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名过长")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 72, message = "密码过长")
        String password
) {
}
